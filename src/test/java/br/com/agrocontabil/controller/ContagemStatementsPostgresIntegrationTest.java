package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.PostgresIntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contagem de statements dos endpoints do benchmark de p95 (issue #153): dashboard, listagem de
 * notas, apuração (faixa de 12 meses) e calendário fiscal. A contagem de comandos SQL por
 * requisição não pode crescer com o número de notas (N+1): compara-se uma base pequena com outra
 * seis vezes maior e a contagem não pode crescer. A listagem varia também o tamanho da página,
 * porque é ele (e não o total no banco) que multiplicaria um N+1 por linha.
 *
 * <p>Os itens das notas são carregados em lotes ({@code default_batch_fetch_size}, #81), então a
 * página de 50 linhas custa um lote a mais que a de 5: o teste permite exatamente essa diferença
 * e nada além.
 *
 * <p>Contra PostgreSQL real, porque a apuração usa SQL nativo ({@code AT TIME ZONE}) que o H2 não
 * roda. A contagem é a do Hibernate ({@code Statistics#getPrepareStatementCount}): consultas
 * feitas direto por {@code JdbcTemplate} não entrariam, e nenhum desses endpoints usa essa via. O
 * teste mede <b>quantidade</b> de comandos, não tempo; o p95 é do script k6 em
 * {@code docs/performance/}.
 */
@DisplayName("Contagem de statements sem N+1 nos endpoints do benchmark (#153)")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ContagemStatementsPostgresIntegrationTest extends PostgresIntegrationTestBase {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
  private static final int NOTAS_BASE_PEQUENA = 10;
  private static final int NOTAS_BASE_GRANDE = 60;
  private static final int PAGINA_PEQUENA = 5;
  private static final int PAGINA_GRANDE = 50;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CadastroService cadastroService;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Statistics estatisticas;
  private CadastroResponse produtor;
  private String token;
  private int notasInseridas;

  @BeforeEach
  void setUp() throws Exception {
    limparDominio();
    estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor Contagem", "52998224725", "contagem@fazenda.com.br",
        SENHA));
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"contagem@fazenda.com.br\",\"senha\":\"%s\"}".formatted(SENHA)))
        .andExpect(status().isOk()).andReturn();
    token = "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
    notasInseridas = 0;
  }

  @Test
  @DisplayName("dashboard: mesma contagem com 10 e com 60 notas")
  void dashboard() throws Exception {
    comparar("/api/dashboard", "/api/dashboard", PAGINA_PEQUENA, PAGINA_PEQUENA);
  }

  @Test
  @DisplayName("listagem de notas: página de 50 linhas custa só o lote a mais da de 5")
  void listagemDeNotas() throws Exception {
    comparar("/api/notas?page=0&size=" + PAGINA_PEQUENA, "/api/notas?page=0&size=" + PAGINA_GRANDE,
        PAGINA_PEQUENA, PAGINA_GRANDE);
  }

  @Test
  @DisplayName("apuração dos últimos 12 meses: mesma contagem com 10 e com 60 notas")
  void apuracao() throws Exception {
    comparar("/api/apuracao?ultimos=12", "/api/apuracao?ultimos=12", PAGINA_PEQUENA,
        PAGINA_PEQUENA);
  }

  @Test
  @DisplayName("calendário fiscal: mesma contagem com 10 e com 60 notas e guias")
  void calendario() throws Exception {
    String caminho = "/api/calendario-fiscal/" + produtor.usuarioId() + "/vencimentos";
    comparar(caminho, caminho, PAGINA_PEQUENA, PAGINA_PEQUENA);
  }

  /**
   * Mede a requisição na base pequena e na grande e exige que a contagem não cresça (além dos lotes). As linhas só entram
   * na mensagem de falha.
   */
  private void comparar(String caminhoPequeno, String caminhoGrande, int linhasPequena,
      int linhasGrande) throws Exception {
    inserirNotas(NOTAS_BASE_PEQUENA);
    aquecer(caminhoPequeno);
    long pequena = contar(() -> chamar(caminhoPequeno));

    inserirNotas(NOTAS_BASE_GRANDE - NOTAS_BASE_PEQUENA);
    aquecer(caminhoGrande);
    long grande = contar(() -> chamar(caminhoGrande));

    // Os itens de cada nota vêm em lotes (default_batch_fetch_size, #81): a página maior pode custar
    // ceil(linhas / lote) - ceil(linhas pequenas / lote) lotes a mais, e só isso. Um N+1 de verdade
    // somaria uma consulta por nota (dezenas a mais aqui).
    int lote = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
        .getSessionFactoryOptions().getDefaultBatchFetchSize();
    long lotesAMais = lotes(linhasGrande, lote) - lotes(linhasPequena, lote);

    assertThat(pequena).as("statements com " + NOTAS_BASE_PEQUENA + " notas").isPositive();
    assertThat(grande)
        .as("statements com %d notas (%d linhas) contra %d notas (%d linhas), lote de %d: a "
            + "contagem não pode crescer com o volume (N+1)",
            NOTAS_BASE_GRANDE, linhasGrande, NOTAS_BASE_PEQUENA, linhasPequena, lote)
        .isLessThanOrEqualTo(pequena + lotesAMais);
  }

  private static long lotes(int linhas, int lote) {
    return lote <= 1 ? linhas : (linhas + lote - 1) / lote;
  }

  /** Primeira chamada: carrega caches estáticos (referências, alíquotas) fora da medição. */
  private void aquecer(String caminho) {
    chamar(caminho);
  }

  private void chamar(String caminho) {
    try {
      mockMvc.perform(get(caminho).header(HttpHeaders.AUTHORIZATION, token))
          .andExpect(status().isOk());
    } catch (Exception e) {
      throw new IllegalStateException("GET " + caminho + " falhou", e);
    }
  }

  private long contar(Runnable operacao) {
    estatisticas.clear();
    operacao.run();
    return estatisticas.getPrepareStatementCount();
  }

  /**
   * Notas AUTORIZADA espalhadas pelos últimos 12 meses (metade própria, metade importada, para a
   * apuração ter débito e crédito), com dois itens cada, e uma guia por nota (calendário).
   */
  private void inserirNotas(int quantidade) {
    UUID produtorId = produtor.usuarioId();
    YearMonth atual = YearMonth.now(BRASILIA);
    for (int i = 0; i < quantidade; i++) {
      int numero = ++notasInseridas;
      UUID notaId = UUID.randomUUID();
      boolean importada = numero % 2 == 0;
      OffsetDateTime emissao = atual.minusMonths(numero % 12).atDay(10).atTime(10, 0)
          .atZone(BRASILIA).toOffsetDateTime();
      jdbc.update("""
          INSERT INTO nota_fiscal (id, chave_acesso, numero, serie, modelo, natureza_operacao,
            data_emissao, produtor_id, destinatario_nome, destinatario_cpf_cnpj, dest_uf,
            valor_total_produtos, valor_total_nota, valor_total_ibs, valor_total_cbs,
            valor_total_credito_ibs, valor_total_credito_cbs, status, tipo_documento, origem,
            version, created_at, updated_at)
          VALUES (?, ?, ?, 1, '55', 'Venda de producao rural', ?, ?, 'Comprador Sintetico',
            '39053344705', 'MG', 1000.00, 1000.00, 1.00, 9.00, ?, ?, 'AUTORIZADA', 'NFE', ?,
            0, now(), now())
          """, notaId, "%044d".formatted(numero), (long) numero, emissao, produtorId,
          importada ? BigDecimal.valueOf(1) : BigDecimal.ZERO,
          importada ? BigDecimal.valueOf(9) : BigDecimal.ZERO,
          importada ? "IMPORTADA" : "PROPRIA");
      for (int item = 1; item <= 2; item++) {
        jdbc.update("""
            INSERT INTO nota_fiscal_item (id, nota_fiscal_id, numero_item, descricao, ncm, cfop,
              unidade, quantidade, valor_unitario, valor_total, aliquota_ibs, valor_ibs,
              aliquota_cbs, valor_cbs, natureza_operacao, created_at, updated_at)
            VALUES (?, ?, ?, 'Soja em graos', '12019000', ?, 'KG', 250.0000, 2.0000, 500.00,
              0.1000, 0.50, 0.9000, 4.50, ?, now(), now())
            """, UUID.randomUUID(), notaId, item, importada ? "1101" : "5101",
            importada ? "CREDITO" : "DEBITO");
      }
      jdbc.update("""
          INSERT INTO guias_fiscais (id, produtor_id, tipo_guia, data_vencimento, valor, status,
            created_at, updated_at)
          VALUES (?, ?, 'FUNRURAL', ?, 150.00, 'PENDENTE', now(), now())
          """, UUID.randomUUID(), produtorId,
          java.sql.Date.valueOf(atual.plusMonths(numero % 6).atDay(20)));
    }
  }
}

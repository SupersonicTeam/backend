package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Leituras de número de um cliente pelo escritório (issue #255), pela cadeia real com JWT:
 * apuração, relatório de conformidade (JSON e PDF), créditos e dashboard em
 * {@code /api/contador/produtores/{produtorId}/...}.
 *
 * <p>Dois produtores com números diferentes no mesmo mês: o contador só vê os do cliente da
 * carteira ativa, e cada rota devolve os números <em>dele</em> — não os de quem está autenticado,
 * que era o defeito das rotas do produtor para o contador (403) e do mock sem header (semente).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Apuracao, conformidade, creditos e dashboard do cliente pelo contador (#255)")
class CarteiraIndicadoresIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String BASE = "/api/contador/produtores/{p}";
  private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
  private static final AtomicLong NUMERO = new AtomicLong(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private Clock clock;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private CadastroResponse contador;
  private String tokenA;
  private String tokenContador;
  private String tokenOutroEscritorio;
  private YearMonth mes;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Outro Escritorio", "11444777000161", "o@escritorio.com.br",
        SENHA));
    // So o produtor A esta na carteira do escritorio.
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtorA.usuarioId(), contador.usuarioId());

    // Mes passado, sem apuracao persistida: a rota projeta ao vivo a partir das notas.
    mes = YearMonth.now(clock).minusMonths(1);
    saida(produtorA.usuarioId(), "100.00", "900.00");
    entrada(produtorA.usuarioId(), "10.00", "90.00");
    saida(produtorB.usuarioId(), "7.00", "63.00");
    entrada(produtorB.usuarioId(), "1.00", "9.00");

    tokenA = token("a@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
    tokenOutroEscritorio = token("o@escritorio.com.br");
  }

  @Test
  @DisplayName("apuracao do mes: numeros do cliente da carteira, nao os de quem pede")
  void apuracaoDoMes() throws Exception {
    mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value(mes.toString()))
        .andExpect(jsonPath("$.debitoIbs").value(100.00))
        .andExpect(jsonPath("$.debitoCbs").value(900.00))
        .andExpect(jsonPath("$.creditoIbs").value(10.00))
        .andExpect(jsonPath("$.totalNotas").value(2));

    // A mesma consulta do proprio produtor bate com a do escritorio.
    String doProdutor = mockMvc.perform(get("/api/apuracao").param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String doEscritorio = mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andReturn().getResponse().getContentAsString();
    assertThat(objectMapper.readTree(doEscritorio)).isEqualTo(objectMapper.readTree(doProdutor));
  }

  @Test
  @DisplayName("apuracao por faixa e agrupada: lista crescente; combinacao invalida 400")
  void apuracaoDaFaixa() throws Exception {
    mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("ultimos", "6")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(6))
        .andExpect(jsonPath("$[4].periodo").value(mes.toString()))
        .andExpect(jsonPath("$[4].debitoIbs").value(100.00));

    mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("de", mes.toString()).param("ate", mes.toString())
            .param("agrupamento", "ANO")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].periodo").value(String.valueOf(mes.getYear())))
        .andExpect(jsonPath("$[0].debitoCbs").value(900.00));

    mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("periodo", mes.toString()).param("ultimos", "6")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FAIXA_APURACAO_INVALIDA"));
    mockMvc.perform(get(BASE + "/apuracao", produtorA.usuarioId())
            .param("periodo", "2026-13")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("relatorio de conformidade (JSON e PDF), creditos e dashboard do cliente")
  void relatorioCreditosDashboard() throws Exception {
    mockMvc.perform(get(BASE + "/relatorios/conformidade", produtorA.usuarioId())
            .param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.debitoTotal").value(1000.00))
        .andExpect(jsonPath("$.creditoTotal").value(100.00))
        .andExpect(jsonPath("$.saldoAPagar").value(900.00));

    MvcResult pdf = mockMvc.perform(
            get(BASE + "/relatorios/conformidade/pdf", produtorA.usuarioId())
                .param("periodo", mes.toString())
                .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"conformidade-" + mes + ".pdf\""))
        .andReturn();
    assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

    mockMvc.perform(get(BASE + "/creditos", produtorA.usuarioId())
            .param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.creditoIbs").value(10.00))
        .andExpect(jsonPath("$.creditoCbs").value(90.00))
        .andExpect(jsonPath("$.quantidadeNotas").value(1));

    mockMvc.perform(get(BASE + "/dashboard", produtorA.usuarioId())
            .param("periodo", mes.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.produtor.nome").value("Produtor A"))
        .andExpect(jsonPath("$.periodo").value(mes.toString()));
  }

  @Test
  @DisplayName("fora da carteira, de outro escritorio ou inexistente: 404 em todas as rotas")
  void foraDaCarteira() throws Exception {
    for (String rota : rotas()) {
      mockMvc.perform(get(BASE + rota, produtorB.usuarioId()).param("periodo", mes.toString())
              .header(HttpHeaders.AUTHORIZATION, tokenContador))
          .andExpect(status().isNotFound());
      mockMvc.perform(get(BASE + rota, produtorA.usuarioId()).param("periodo", mes.toString())
              .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
          .andExpect(status().isNotFound());
      mockMvc.perform(get(BASE + rota, UUID.randomUUID()).param("periodo", mes.toString())
              .header(HttpHeaders.AUTHORIZATION, tokenContador))
          .andExpect(status().isNotFound());
    }
    // A faixa tambem: a autorizacao vem antes da conferencia da combinacao.
    mockMvc.perform(get(BASE + "/apuracao", produtorB.usuarioId())
            .param("periodo", mes.toString()).param("ultimos", "6")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("vinculo suspenso ou encerrado: 404 em todas as rotas")
  void vinculoNaoAtivo() throws Exception {
    for (String situacao : new String[] {"SUSPENSO", "ENCERRADO"}) {
      jdbcTemplate.update("UPDATE carteira_vinculo SET situacao = ? WHERE produtor_id = ?",
          situacao, produtorA.usuarioId());
      for (String rota : rotas()) {
        mockMvc.perform(get(BASE + rota, produtorA.usuarioId()).param("periodo", mes.toString())
                .header(HttpHeaders.AUTHORIZATION, tokenContador))
            .andExpect(status().isNotFound());
      }
    }
  }

  @Test
  @DisplayName("produtor recebe 403 em todas as rotas, inclusive para os proprios dados")
  void produtorRecebe403() throws Exception {
    for (String rota : rotas()) {
      mockMvc.perform(get(BASE + rota, produtorA.usuarioId()).param("periodo", mes.toString())
              .header(HttpHeaders.AUTHORIZATION, tokenA))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    }
  }

  private static String[] rotas() {
    return new String[] {"/apuracao", "/relatorios/conformidade", "/relatorios/conformidade/pdf",
        "/creditos", "/dashboard"};
  }

  // -------------------------------------------------------------------------

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private void saida(UUID produtorId, String ibs, String cbs) {
    NotaFiscal nota = baseNota(produtorId);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setValorTotalIbs(new BigDecimal(ibs));
    nota.setValorTotalCbs(new BigDecimal(cbs));
    notaFiscalRepository.saveAndFlush(nota);
  }

  private void entrada(UUID produtorId, String ibs, String cbs) {
    NotaFiscal nota = baseNota(produtorId);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setEmitenteNome("Insumos do Campo SA");
    nota.setEmitenteCpfCnpj("11222333000181");
    nota.setChaveAcesso(String.format("%044d", NUMERO.get()));
    nota.setValorTotalCreditoIbs(new BigDecimal(ibs));
    nota.setValorTotalCreditoCbs(new BigDecimal(cbs));
    notaFiscalRepository.saveAndFlush(nota);
  }

  private NotaFiscal baseNota(UUID produtorId) {
    Produtor produtor = produtorRepository.findById(produtorId).orElseThrow();
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua A").numero("1").municipio("Cuiaba")
        .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build();
    return NotaFiscal.builder()
        .numero(NUMERO.getAndIncrement()).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(mes.atDay(15).atTime(LocalTime.NOON).atZone(BRT).toOffsetDateTime())
        .status(StatusNotaFiscal.AUTORIZADA)
        .produtor(produtor)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}

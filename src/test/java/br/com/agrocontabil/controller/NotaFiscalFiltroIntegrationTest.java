package br.com.agrocontabil.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Filtros de {@code GET /api/notas} (issue #174), pela pilha real (controller → service →
 * {@code Specification} → H2) com dois produtores.
 *
 * <p>O ponto central: os filtros entram <strong>sobre</strong> o recorte do produtor autenticado,
 * nunca no lugar dele. Uma nota de B que casaria com todos os filtros de A não pode aparecer, nem no
 * conteúdo nem no {@code totalElements}.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class NotaFiscalFiltroIntegrationTest {

  private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
    produtorB = produtorRepository.save(
        Produtor.builder().nomeCompleto("Joao Pecuarista").cpfCnpj("12345678909").build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("Sem filtro: só as notas do produtor autenticado, total do produtor")
  void semFiltroListaSoAsDoProdutor() throws Exception {
    nota(produtorA, 1L, "Cooperativa Agrícola", "Venda", dia(2026, 9, 10));
    nota(produtorA, 2L, "Laticínio Sul", "Venda", dia(2026, 9, 11));
    nota(produtorB, 3L, "Cooperativa Agrícola", "Venda", dia(2026, 9, 10));

    mockMvc.perform(como(produtorA, get("/api/notas")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].numero", containsInAnyOrder(1, 2)));
  }

  @Test
  @DisplayName("Filtros combinados de A nunca trazem a nota de B que casaria com todos eles")
  void filtrosNaoVazamNotaDeOutroProdutor() throws Exception {
    nota(produtorA, 10L, "Cooperativa Agrícola", "Venda de soja", dia(2026, 9, 10));
    nota(produtorB, 11L, "Cooperativa Agrícola", "Venda de soja", dia(2026, 9, 10));

    mockMvc.perform(como(produtorA, get("/api/notas"))
            .param("status", "RASCUNHO")
            .param("tipoDocumento", "NFE")
            .param("emitidaDe", "2026-09-01")
            .param("emitidaAte", "2026-09-30")
            .param("busca", "cooperativa"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[*].numero", contains(10)));

    mockMvc.perform(como(produtorB, get("/api/notas")).param("busca", "cooperativa"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[*].numero", contains(11)));
  }

  @Test
  @DisplayName("status repetido é OU; totalElements reflete o filtro")
  void statusRepetidoEhOu() throws Exception {
    comStatus(nota(produtorA, 1L, "A", "Venda", dia(2026, 9, 1)), StatusNotaFiscal.AUTORIZADA);
    comStatus(nota(produtorA, 2L, "B", "Venda", dia(2026, 9, 1)), StatusNotaFiscal.REJEITADA);
    nota(produtorA, 3L, "C", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas"))
            .param("status", "AUTORIZADA")
            .param("status", "REJEITADA")
            .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  @Test
  @DisplayName("tipoDocumento filtra NFE/NFA")
  void tipoDocumentoFiltra() throws Exception {
    nota(produtorA, 1L, "A", "Venda", dia(2026, 9, 1));
    NotaFiscal nfa = nota(produtorA, 2L, "B", "Venda", dia(2026, 9, 1));
    nfa.setTipoDocumento(TipoDocumentoFiscal.NFA);
    notaFiscalRepository.save(nfa);

    mockMvc.perform(como(produtorA, get("/api/notas")).param("tipoDocumento", "NFA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", contains(2)));
  }

  @Test
  @DisplayName("emitidaAte inclui o dia inteiro em Brasília; emitidaDe é inclusivo")
  void faixaDeDatasInclusivaNoDiaInteiro() throws Exception {
    nota(produtorA, 1L, "A", "Venda", LocalDateTime.of(2026, 9, 9, 23, 59));
    nota(produtorA, 2L, "B", "Venda", LocalDateTime.of(2026, 9, 10, 0, 0));
    nota(produtorA, 3L, "C", "Venda", LocalDateTime.of(2026, 9, 10, 23, 59, 59));
    nota(produtorA, 4L, "D", "Venda", LocalDateTime.of(2026, 9, 11, 0, 0));

    mockMvc.perform(como(produtorA, get("/api/notas"))
            .param("emitidaDe", "2026-09-10")
            .param("emitidaAte", "2026-09-10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].numero", containsInAnyOrder(2, 3)));
  }

  @Test
  @DisplayName("Faixa invertida: 400 FILTRO_NOTAS_INVALIDO no campo emitidaDe")
  void faixaInvertidaRetorna400() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/notas"))
            .param("emitidaDe", "2026-09-30")
            .param("emitidaAte", "2026-09-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_NOTAS_INVALIDO"))
        .andExpect(jsonPath("$.errors[0].field").value("emitidaDe"));
  }

  @Test
  @DisplayName("busca sem diferenciar maiúscula nem acento, no destinatário e na natureza")
  void buscaSemAcentoNemCaixa() throws Exception {
    nota(produtorA, 1L, "Cooperativa AGRÍCOLA do Sul", "Venda", dia(2026, 9, 1));
    nota(produtorA, 2L, "Laticínio", "Remessa para armazém", dia(2026, 9, 1));
    nota(produtorA, 3L, "Outro", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "agricola"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", contains(1)));
    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "ARMAZEM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", contains(2)));
  }

  @Test
  @DisplayName("busca acompanha a edição: o texto novo casa, o antigo não")
  void buscaAcompanhaEdicao() throws Exception {
    NotaFiscal nota = nota(produtorA, 1L, "Cooperativa", "Venda", dia(2026, 9, 1));
    nota.setDestinatarioNome("Armazém Central");
    notaFiscalRepository.save(nota);

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "armazem"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "cooperativa"))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @DisplayName("Termo numérico curto casa o número da nota por prefixo")
  void termoNumericoCurtoCasaNumeroPorPrefixo() throws Exception {
    nota(produtorA, 12L, "A", "Venda", dia(2026, 9, 1));
    nota(produtorA, 1234L, "B", "Venda", dia(2026, 9, 1));
    nota(produtorA, 312L, "C", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", containsInAnyOrder(12, 1234)));
  }

  @Test
  @DisplayName("Termo de texto com menos de 3 caracteres é ignorado")
  void termoCurtoEhIgnorado() throws Exception {
    nota(produtorA, 1L, "Cooperativa", "Venda", dia(2026, 9, 1));
    nota(produtorA, 2L, "Laticínio", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "co"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("Termo numérico curto com zero à esquerda é ignorado, como o texto curto")
  void termoNumericoCurtoComZeroEhIgnorado() throws Exception {
    nota(produtorA, 1L, "Cooperativa", "Venda", dia(2026, 9, 1));
    nota(produtorA, 2L, "Laticínio", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("status vazio (?status=) não filtra")
  void statusVazioNaoFiltra() throws Exception {
    nota(produtorA, 1L, "A", "Venda", dia(2026, 9, 1));
    nota(produtorA, 2L, "B", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("status", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  @DisplayName("Destinatário longo em escrita que o NFD expande (coreano) grava e é achado")
  void nomeQueExpandeNoNfdGravaEEhAchado() throws Exception {
    // 255 sílabas Hangul viravam 765 jamo no NFD e estouravam busca_normalizada (600).
    nota(produtorA, 1L, "한".repeat(255), "Venda de produção rural", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "한".repeat(3)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("Curinga do LIKE no termo é texto, não curinga")
  void curingaNoTermoEhLiteral() throws Exception {
    nota(produtorA, 1L, "Cooperativa", "Venda", dia(2026, 9, 1));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "%%%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @DisplayName("busca acima de 100 caracteres: 400 VALIDATION_ERROR")
  void buscaLongaRetorna400() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/notas")).param("busca", "a".repeat(101)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("busca"));
  }

  @Test
  @DisplayName("status inexistente: 400 no envelope, não 500")
  void statusInexistenteRetorna400() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/notas")).param("status", "INEXISTENTE"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("status"));
  }

  @Test
  @DisplayName("sort na lista branca ordena; fora dela, 400 FILTRO_NOTAS_INVALIDO em sort")
  void sortSoNaListaBranca() throws Exception {
    nota(produtorA, 2L, "A", "Venda", dia(2026, 9, 1));
    nota(produtorA, 1L, "B", "Venda", dia(2026, 9, 2));
    nota(produtorA, 3L, "C", "Venda", dia(2026, 9, 3));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("sort", "numero,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", contains(1, 2, 3)));
    mockMvc.perform(como(produtorA, get("/api/notas")).param("sort", "dataEmissao,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].numero", contains(3, 1, 2)));

    mockMvc.perform(como(produtorA, get("/api/notas")).param("sort", "xmlGerado,asc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_NOTAS_INVALIDO"))
        .andExpect(jsonPath("$.errors[0].field").value("sort"));
    mockMvc.perform(como(produtorA, get("/api/notas")).param("sort", "naoExiste"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("sort"));
  }

  // -------------------------------------------------------------------------

  private MockHttpServletRequestBuilder como(Produtor produtor,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private static LocalDateTime dia(int ano, int mes, int dia) {
    return LocalDateTime.of(ano, mes, dia, 10, 0);
  }

  private void comStatus(NotaFiscal nota, StatusNotaFiscal status) {
    nota.setStatus(status);
    notaFiscalRepository.save(nota);
  }

  private NotaFiscal nota(Produtor produtor, long numero, String destinatario, String natureza,
      LocalDateTime emissaoBrt) {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores").numero("100").municipio("Cuiaba")
        .codigoMunicipioIbge("5103403").uf("MT").cep("78000000")
        .build();
    return notaFiscalRepository.save(NotaFiscal.builder()
        .numero(numero).serie(1).modelo("55")
        .naturezaOperacao(natureza)
        .dataEmissao(emissaoBrt.atZone(BRT).toOffsetDateTime())
        .produtor(produtor)
        .destinatarioNome(destinatario).destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build());
  }
}

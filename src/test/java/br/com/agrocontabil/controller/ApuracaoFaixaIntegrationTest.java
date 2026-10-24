package br.com.agrocontabil.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
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
 * Faixa de {@code GET /api/apuracao} (issue #179) pela pilha real, com dois produtores.
 *
 * <p>Os meses de 2024 são todos passados: o resultado não depende da data em que o teste roda
 * (só os cenários de {@code ultimos} usam o mês corrente).
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ApuracaoFaixaIntegrationTest {

  private static final ZoneOffset BRT = ZoneOffset.ofHours(-3);

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private final AtomicLong numeros = new AtomicLong(1);
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
  @DisplayName("Faixa mês a mês: ordem crescente, sem buraco, mês sem nota com zeros e 0 notas")
  void faixaMesAMesSemBuraco() throws Exception {
    saida(produtorA, dia(2024, 2, 10), "100.00", "900.00");
    entrada(produtorA, dia(2024, 2, 20), "10.00", "90.00");
    saida(produtorA, dia(2024, 4, 5), "50.00", "450.00");

    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-04"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(4)))
        .andExpect(jsonPath("$[*].periodo", contains("2024-01", "2024-02", "2024-03", "2024-04")))
        .andExpect(jsonPath("$[0].totalNotas").value(0))
        .andExpect(jsonPath("$[0].debitoIbs").value(0.00))
        .andExpect(jsonPath("$[1].debitoIbs").value(100.00))
        .andExpect(jsonPath("$[1].creditoCbs").value(90.00))
        .andExpect(jsonPath("$[1].saldoIbs").value(90.00))
        .andExpect(jsonPath("$[1].saldoCbs").value(810.00))
        .andExpect(jsonPath("$[1].totalNotas").value(2))
        .andExpect(jsonPath("$[1].status").value("ABERTO"))
        .andExpect(jsonPath("$[1].parcial").value(false))
        .andExpect(jsonPath("$[2].totalNotas").value(0))
        .andExpect(jsonPath("$[3].totalNotas").value(1))
        .andExpect(jsonPath("$[0].id").doesNotExist());
  }

  @Test
  @DisplayName("Cada mês da faixa é igual ao que ?periodo= devolve para ele")
  void faixaIgualAConsultaMesAMes() throws Exception {
    saida(produtorA, dia(2024, 5, 3), "12.34", "56.78");
    entrada(produtorA, dia(2024, 5, 9), "1.11", "2.22");
    saida(produtorA, dia(2024, 6, 30), "7.00", "8.00");

    for (int i = 0; i < 2; i++) {
      String mes = YearMonth.of(2024, 5 + i).toString();
      mockMvc.perform(como(produtorA, get("/api/apuracao")).param("periodo", mes))
          .andExpect(jsonPath("$.debitoIbs").value(i == 0 ? 12.34 : 7.00))
          .andExpect(jsonPath("$.saldoIbs").value(i == 0 ? 11.23 : 7.00))
          .andExpect(jsonPath("$.totalNotas").value(i == 0 ? 2 : 1));
      mockMvc.perform(como(produtorA, get("/api/apuracao"))
              .param("de", "2024-05").param("ate", "2024-06"))
          .andExpect(jsonPath("$[" + i + "].debitoIbs").value(i == 0 ? 12.34 : 7.00))
          .andExpect(jsonPath("$[" + i + "].saldoIbs").value(i == 0 ? 11.23 : 7.00))
          .andExpect(jsonPath("$[" + i + "].totalNotas").value(i == 0 ? 2 : 1));
    }
  }

  @Test
  @DisplayName("Nota das 22h30 do último dia do mês (Brasília) fica naquele mês")
  void limiteDoMesEmBrasilia() throws Exception {
    // 2024-03-31T22:30-03:00 = 2024-04-01T01:30Z: em UTC cairia em abril.
    saida(produtorA, OffsetDateTime.of(2024, 3, 31, 22, 30, 0, 0, BRT), "5.00", "5.00");

    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-03").param("ate", "2024-04"))
        .andExpect(jsonPath("$[0].totalNotas").value(1))
        .andExpect(jsonPath("$[1].totalNotas").value(0));
  }

  @Test
  @DisplayName("Mês consolidado vem da apuração persistida, não das notas")
  void mesConsolidadoVemDaPersistida() throws Exception {
    saida(produtorA, dia(2024, 1, 10), "999.00", "999.00");
    apuracao(produtorA, "2024-01", StatusApuracao.FECHADO, "40.00", 3);

    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-01"))
        .andExpect(jsonPath("$[0].debitoIbs").value(40.00))
        .andExpect(jsonPath("$[0].totalNotas").value(3))
        .andExpect(jsonPath("$[0].status").value("FECHADO"))
        .andExpect(jsonPath("$[0].dataFechamento").exists());
  }

  @Test
  @DisplayName("A faixa de A nunca soma nota nem apuração de B")
  void faixaNaoVazaOutroProdutor() throws Exception {
    saida(produtorB, dia(2024, 2, 10), "100.00", "100.00");
    apuracao(produtorB, "2024-01", StatusApuracao.FECHADO, "40.00", 3);

    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].totalNotas").value(0))
        .andExpect(jsonPath("$[0].status").value("ABERTO"))
        .andExpect(jsonPath("$[1].totalNotas").value(0))
        .andExpect(jsonPath("$[1].debitoIbs").value(0.00));

    mockMvc.perform(como(produtorB, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-02"))
        .andExpect(jsonPath("$[0].totalNotas").value(3))
        .andExpect(jsonPath("$[1].totalNotas").value(1));
  }

  @Test
  @DisplayName("Trimestre: soma os meses; FECHADO só com todos fechados; parcial se a faixa corta")
  void agrupamentoPorTrimestre() throws Exception {
    apuracao(produtorA, "2024-01", StatusApuracao.FECHADO, "10.00", 1);
    apuracao(produtorA, "2024-02", StatusApuracao.FECHADO, "20.00", 2);
    apuracao(produtorA, "2024-03", StatusApuracao.FECHADO, "30.00", 3);
    apuracao(produtorA, "2024-04", StatusApuracao.FECHADO, "40.00", 4);
    saida(produtorA, dia(2024, 5, 10), "50.00", "50.00");

    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-05").param("agrupamento", "TRIMESTRE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].periodo").value("2024-T1"))
        .andExpect(jsonPath("$[0].inicio").value("2024-01"))
        .andExpect(jsonPath("$[0].fim").value("2024-03"))
        .andExpect(jsonPath("$[0].debitoIbs").value(60.00))
        .andExpect(jsonPath("$[0].totalNotas").value(6))
        .andExpect(jsonPath("$[0].status").value("FECHADO"))
        .andExpect(jsonPath("$[0].dataFechamento").exists())
        .andExpect(jsonPath("$[0].parcial").value(false))
        .andExpect(jsonPath("$[1].periodo").value("2024-T2"))
        .andExpect(jsonPath("$[1].inicio").value("2024-04"))
        .andExpect(jsonPath("$[1].fim").value("2024-05"))
        .andExpect(jsonPath("$[1].debitoIbs").value(90.00))
        .andExpect(jsonPath("$[1].totalNotas").value(5))
        .andExpect(jsonPath("$[1].status").value("ABERTO"))
        .andExpect(jsonPath("$[1].dataFechamento").doesNotExist())
        .andExpect(jsonPath("$[1].parcial").value(true));
  }

  @Test
  @DisplayName("Ano e semestre usam os rótulos 2024 e 2024-S1")
  void rotulosDeAnoESemestre() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2023-01").param("ate", "2024-12").param("agrupamento", "ANO"))
        .andExpect(jsonPath("$[*].periodo", contains("2023", "2024")))
        .andExpect(jsonPath("$[0].parcial").value(false));
    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2024-01").param("ate", "2024-12").param("agrupamento", "SEMESTRE"))
        .andExpect(jsonPath("$[*].periodo", contains("2024-S1", "2024-S2")));
  }

  @Test
  @DisplayName("ultimos=6 termina no mês corrente, que vem parcial")
  void ultimosTerminaNoMesCorrente() throws Exception {
    YearMonth atual = YearMonth.now(ZoneId.of("America/Sao_Paulo"));

    mockMvc.perform(como(produtorA, get("/api/apuracao")).param("ultimos", "6"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(6)))
        .andExpect(jsonPath("$[0].periodo").value(atual.minusMonths(5).toString()))
        .andExpect(jsonPath("$[5].periodo").value(atual.toString()))
        .andExpect(jsonPath("$[5].parcial").value(true))
        .andExpect(jsonPath("$[4].parcial").value(false));
  }

  @Test
  @DisplayName("60 meses passam; 61 não")
  void tetoDeSessentaMeses() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2020-01").param("ate", "2024-12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(60)));
    mockMvc.perform(como(produtorA, get("/api/apuracao"))
            .param("de", "2019-12").param("ate", "2024-12"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FAIXA_APURACAO_INVALIDA"))
        .andExpect(jsonPath("$.errors[0].field").value("ate"));
  }

  @Test
  @DisplayName("Combinações inválidas: 400 FAIXA_APURACAO_INVALIDA no campo certo")
  void combinacoesInvalidas() throws Exception {
    esperar400("ate", "de", "2024-05", "ate", null);
    esperar400("de", "de", "2024-06", "ate", "2024-01");
    esperar400("ultimos", "ultimos", "7", null, null);
    esperar400("ultimos", "ultimos", "6", "de", "2024-01");
    esperar400("periodo", "periodo", "2024-01", "ultimos", "6");
    esperar400("periodo", "periodo", "2024-01", "agrupamento", "ANO");
  }

  @Test
  @DisplayName("agrupamento inexistente: 400 no envelope")
  void agrupamentoInexistente() throws Exception {
    mockMvc.perform(como(produtorA, get("/api/apuracao")).param("ultimos", "6")
            .param("agrupamento", "QUINZENA"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("agrupamento"));
  }

  @Test
  @DisplayName("OpenAPI publica a resposta 200 como objeto ou lista de ApuracaoResponse")
  void openApiDocumentaObjetoOuLista() throws Exception {
    String resposta = "$.paths['/api/apuracao'].get.responses['200'].content['application/json']"
        + ".schema.oneOf";

    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(resposta + "[0].$ref").value("#/components/schemas/ApuracaoResponse"))
        .andExpect(jsonPath(resposta + "[1].type").value("array"))
        .andExpect(jsonPath(resposta + "[1].items.$ref")
            .value("#/components/schemas/ApuracaoResponse"));
  }

  // -------------------------------------------------------------------------

  private void esperar400(String campo, String p1, String v1, String p2, String v2)
      throws Exception {
    MockHttpServletRequestBuilder requisicao = como(produtorA, get("/api/apuracao"))
        .param(p1, v1);
    if (p2 != null && v2 != null) {
      requisicao.param(p2, v2);
    }
    mockMvc.perform(requisicao)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FAIXA_APURACAO_INVALIDA"))
        .andExpect(jsonPath("$.errors[0].field").value(campo));
  }

  private MockHttpServletRequestBuilder como(Produtor produtor,
      MockHttpServletRequestBuilder requisicao) {
    return requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtor.getId());
  }

  private static OffsetDateTime dia(int ano, int mes, int dia) {
    return OffsetDateTime.of(ano, mes, dia, 12, 0, 0, 0, BRT);
  }

  private void apuracao(Produtor dono, String periodo, StatusApuracao status, String debitoIbs,
      long notas) {
    BigDecimal debito = new BigDecimal(debitoIbs);
    apuracaoRepository.save(ApuracaoMensal.builder()
        .produtor(dono).periodo(periodo).status(status)
        .debitoIbs(debito).debitoCbs(BigDecimal.ZERO)
        .creditoIbs(BigDecimal.ZERO).creditoCbs(BigDecimal.ZERO)
        .saldoIbs(debito).saldoCbs(BigDecimal.ZERO)
        .totalNotas(notas)
        .dataFechamento(status == StatusApuracao.FECHADO
            ? OffsetDateTime.of(2024, 12, 1, 1, 0, 0, 0, BRT) : null)
        .build());
  }

  private void saida(Produtor dono, OffsetDateTime emissao, String ibs, String cbs) {
    NotaFiscal nota = base(dono, emissao);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setValorTotalIbs(new BigDecimal(ibs));
    nota.setValorTotalCbs(new BigDecimal(cbs));
    notaFiscalRepository.save(nota);
  }

  private void entrada(Produtor dono, OffsetDateTime emissao, String creditoIbs,
      String creditoCbs) {
    NotaFiscal nota = base(dono, emissao);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setChaveAcesso(String.format("%044d", numeros.get()));
    nota.setValorTotalCreditoIbs(new BigDecimal(creditoIbs));
    nota.setValorTotalCreditoCbs(new BigDecimal(creditoCbs));
    notaFiscalRepository.save(nota);
  }

  private NotaFiscal base(Produtor dono, OffsetDateTime emissao) {
    return NotaFiscal.builder()
        .numero(numeros.getAndIncrement()).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(emissao)
        .produtor(dono)
        .status(StatusNotaFiscal.AUTORIZADA)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}

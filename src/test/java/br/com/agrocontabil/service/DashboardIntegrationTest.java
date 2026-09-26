package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.DashboardResponse.NotaPorMes;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integração do dashboard (issue Agrocontabil#65 — Sprint 5) contra H2 (profile
 * {@code test}).
 *
 * <p>Exercita a pilha real ({@link DashboardService} + {@link ApuracaoService} +
 * {@link NotaFiscalRepository}). O objetivo principal é <strong>garantir a portabilidade</strong>
 * da query de série mensal — a agregação por {@code year()}/{@code month()} + {@code origem} roda
 * no H2 exatamente como rodará no PostgreSQL — e confirmar o zero-fill dos 6 meses, a exclusão de
 * notas não autorizadas/fora da janela e o mapeamento das últimas notas sem expor entidade JPA.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
class DashboardIntegrationTest {

  @Autowired private DashboardService dashboardService;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();

    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").estado("MT").build());

    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    // Limpa a autenticacao para nao vazar contexto entre testes (evita flakiness).
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Serie mensal agrega por mes/origem (year/month portavel) com zero-fill e exclusoes")
  void serieMensalAgregaPorMesEorigem() {
    OffsetDateTime maio = OffsetDateTime.of(2026, 5, 10, 9, 0, 0, 0, ZoneOffset.ofHours(-3));
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    OffsetDateTime janeiro = OffsetDateTime.of(2026, 1, 20, 8, 0, 0, 0, ZoneOffset.ofHours(-3));

    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, maio);        // saida maio
    salvarSaida(2L, StatusNotaFiscal.AUTORIZADA, julho);       // saida julho
    salvarEntradaImportada(3L, julho);                          // entrada julho
    salvarSaida(4L, StatusNotaFiscal.RASCUNHO, julho);         // rascunho -> excluida da serie
    salvarSaida(5L, StatusNotaFiscal.AUTORIZADA, janeiro);     // fora da janela [fev..jul] -> excluida

    DashboardResponse dash = dashboardService.montar("2026-07");

    List<NotaPorMes> serie = dash.notasPorMes();
    assertThat(serie).extracting(NotaPorMes::periodo)
        .containsExactly("2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07");
    assertThat(serie.get(3)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(0L, 1L); // maio: 1 saida autorizada
    assertThat(serie.get(5)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(1L, 1L); // julho: 1 entrada + 1 saida (rascunho nao conta)
    assertThat(serie.get(0)).extracting(NotaPorMes::entradas, NotaPorMes::saidas)
        .containsExactly(0L, 0L); // fevereiro vazio
  }

  @Test
  @DisplayName("Ultimas notas vem mais recentes primeiro, com contraparte por origem, sem CPF")
  void ultimasNotasMaisRecentesPrimeiro() {
    OffsetDateTime antiga = OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.ofHours(-3));
    OffsetDateTime recente = OffsetDateTime.of(2026, 7, 20, 9, 0, 0, 0, ZoneOffset.ofHours(-3));

    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, antiga);
    salvarEntradaImportada(2L, recente);

    DashboardResponse dash = dashboardService.montar("2026-07");

    assertThat(dash.ultimasNotas()).hasSize(2);
    // A entrada importada e mais recente -> primeiro; contraparte = emitente.
    assertThat(dash.ultimasNotas().get(0).origem()).isEqualTo("IMPORTADA");
    assertThat(dash.ultimasNotas().get(0).contraparte()).isEqualTo("Insumos do Campo SA");
    // A saida propria -> contraparte = destinatario.
    assertThat(dash.ultimasNotas().get(1).origem()).isEqualTo("PROPRIA");
    assertThat(dash.ultimasNotas().get(1).contraparte()).isEqualTo("Jose Comprador");
    // Nenhum documento sensivel do produtor no resumo.
    assertThat(dash.produtor().uf()).isEqualTo("MT");
    assertThat(dash.toString()).doesNotContain("98765432100");
  }

  @Test
  @DisplayName("KPIs do dashboard refletem a apuracao ao vivo do periodo (debito x credito)")
  void kpisRefletemApuracaoDoPeriodo() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho);   // debito 150/1350
    salvarEntradaImportada(2L, julho);                      // credito 30/270

    DashboardResponse dash = dashboardService.montar("2026-07");

    assertThat(dash.kpis().debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(dash.kpis().creditoIbs()).isEqualByComparingTo("30.00");
    assertThat(dash.kpis().saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(dash.kpis().saldoCbs()).isEqualByComparingTo("1080.00");
    assertThat(dash.kpis().totalNotas()).isEqualTo(2L);
    assertThat(dash.distribuicaoImpostos().ibs()).isEqualByComparingTo("150.00");
    assertThat(dash.distribuicaoImpostos().cbs()).isEqualByComparingTo("1350.00");
  }

  @Test
  @DisplayName("Financeiro consolida entradas (vendas) x saidas (compras) e desconta IBS/CBS do lucro")
  void financeiroConsolidaFluxoDeCaixaDoPeriodo() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho);    // venda: valor total 1000.00
    salvarEntradaImportada(2L, julho);                       // compra: valor total 1000.00

    DashboardResponse dash = dashboardService.montar("2026-07");

    // saldoIbs (120.00) + saldoCbs (1080.00) = 1200.00 a recolher no periodo.
    assertThat(dash.financeiro().totalEntradas()).isEqualByComparingTo("1000.00");
    assertThat(dash.financeiro().totalSaidas()).isEqualByComparingTo("1000.00");
    assertThat(dash.financeiro().saldo()).isEqualByComparingTo("0.00");
    assertThat(dash.financeiro().lucroEstimado()).isEqualByComparingTo("-1200.00");
  }

  @Test
  @DisplayName("Financeiro ignora nota nao autorizada e nota fora da janela do periodo")
  void financeiroIgnoraNaoAutorizadaEForaDaJanela() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    OffsetDateTime junho = OffsetDateTime.of(2026, 6, 20, 9, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho);
    salvarSaida(2L, StatusNotaFiscal.RASCUNHO, julho);      // nao autorizada -> ignorada
    salvarSaida(3L, StatusNotaFiscal.AUTORIZADA, junho);    // fora do periodo (julho) -> ignorada

    DashboardResponse dash = dashboardService.montar("2026-07");

    assertThat(dash.financeiro().totalEntradas()).isEqualByComparingTo("1000.00");
    assertThat(dash.financeiro().totalSaidas()).isEqualByComparingTo("0.00");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void salvarSaida(long numero, StatusNotaFiscal status, OffsetDateTime dataEmissao) {
    NotaFiscal nota = baseNota(numero, dataEmissao);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setStatus(status);
    nota.setValorTotalIbs(new BigDecimal("150.00"));
    nota.setValorTotalCbs(new BigDecimal("1350.00"));
    notaFiscalRepository.saveAndFlush(nota);
  }

  private void salvarEntradaImportada(long numero, OffsetDateTime dataEmissao) {
    NotaFiscal nota = baseNota(numero, dataEmissao);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setEmitenteNome("Insumos do Campo SA");
    nota.setEmitenteCpfCnpj("11222333000181");
    nota.setChaveAcesso(String.format("%044d", System.nanoTime()));
    nota.setValorTotalCreditoIbs(new BigDecimal("30.00"));
    nota.setValorTotalCreditoCbs(new BigDecimal("270.00"));
    notaFiscalRepository.saveAndFlush(nota);
  }

  private NotaFiscal baseNota(long numero, OffsetDateTime dataEmissao) {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua A").numero("1").municipio("Cuiaba")
        .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build();
    return NotaFiscal.builder()
        .numero(numero).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(dataEmissao)
        .produtor(produtor)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}

package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integração do fluxo crítico da apuração mensal (issue Agrocontabil#41 — RN004).
 *
 * <p>Exercita a pilha real (repositório de agregação + {@link ApuracaoService} + persistência da
 * {@link ApuracaoMensal}) contra H2 (profile {@code test}, schema via {@code @Sql}). Confirma a
 * regra de negócio da DoD: <strong>só saídas AUTORIZADAS</strong> compõem o débito e
 * <strong>só entradas IMPORTADAS</strong> compõem o crédito; RASCUNHO/REJEITADA/CANCELADA e outros
 * meses são excluídos. Verifica também o cálculo do saldo e o fechamento pelo job.
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
class ApuracaoIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  @Autowired private ApuracaoService apuracaoService;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private ProdutorRepository produtorRepository;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    apuracaoRepository.deleteAll();
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();

    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());

    // Autentica o produtor no contexto de seguranca (o AuthenticatedProdutorProvider o le dali).
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
  }

  @Test
  @DisplayName("Consolida so saidas AUTORIZADAS (debito) e entradas IMPORTADAS (credito) do mes")
  void consolidaSomenteAutorizadaEImportadaDoMes() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    OffsetDateTime agosto = OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    // Debito: saida AUTORIZADA em julho -> conta.
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho, "150.00", "1350.00");
    // Saida REJEITADA em julho -> NAO conta para debito.
    salvarSaida(2L, StatusNotaFiscal.REJEITADA, julho, "999.00", "999.00");
    // Saida AUTORIZADA em agosto (outro mes) -> NAO conta.
    salvarSaida(3L, StatusNotaFiscal.AUTORIZADA, agosto, "500.00", "500.00");
    // Credito: entrada IMPORTADA em julho -> conta.
    salvarEntradaImportada(julho, "30.00", "270.00");

    var resposta = apuracaoService.consolidar("2026-07");

    assertThat(resposta.debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(resposta.debitoCbs()).isEqualByComparingTo("1350.00");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("30.00");
    assertThat(resposta.creditoCbs()).isEqualByComparingTo("270.00");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("1080.00");
    assertThat(resposta.totalNotas()).isEqualTo(2L); // 1 saida autorizada + 1 entrada importada
    assertThat(resposta.status()).isEqualTo("ABERTO");

    // Persistiu exatamente uma apuracao para o produtor/periodo.
    Optional<ApuracaoMensal> persistida =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07");
    assertThat(persistida).isPresent();
    assertThat(persistida.get().getSaldoIbs()).isEqualByComparingTo("120.00");
  }

  @Test
  @DisplayName("Consultar periodo nao consolidado projeta ao vivo das notas sem persistir (issue #53)")
  void consultarProjetaAoVivoSemPersistir() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho, "150.00", "1350.00");
    salvarEntradaImportada(julho, "30.00", "270.00");

    var resposta = apuracaoService.consultar("2026-07");

    // Reflete as notas do mes mesmo sem o job de fechamento ter rodado.
    assertThat(resposta.debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(resposta.creditoIbs()).isEqualByComparingTo("30.00");
    assertThat(resposta.saldoIbs()).isEqualByComparingTo("120.00");
    assertThat(resposta.saldoCbs()).isEqualByComparingTo("1080.00");
    assertThat(resposta.totalNotas()).isEqualTo(2L);
    assertThat(resposta.status()).isEqualTo("ABERTO");

    // Projecao transitoria: nao cria nenhuma ApuracaoMensal.
    assertThat(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07"))
        .isEmpty();
  }

  @Test
  @DisplayName("Reconsolidar o mesmo periodo atualiza a apuracao existente (upsert, sem duplicar)")
  void reconsolidarAtualizaSemDuplicar() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho, "100.00", "100.00");

    apuracaoService.consolidar("2026-07");
    salvarSaida(2L, StatusNotaFiscal.AUTORIZADA, julho, "50.00", "50.00");
    var resposta = apuracaoService.consolidar("2026-07");

    assertThat(resposta.debitoIbs()).isEqualByComparingTo("150.00");
    assertThat(apuracaoRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("Consolidar apos fechamento lanca 409 e nao rebaixa o periodo (issue #54)")
  void consolidarSobreFechadoRejeitaSemRebaixar() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho, "100.00", "100.00");
    apuracaoService.fecharPeriodo("2026-07");

    assertThatThrownBy(() -> apuracaoService.consolidar("2026-07"))
        .isInstanceOf(ApuracaoPeriodoFechadoException.class);

    // Periodo permanece FECHADO — consolidar nao pode rebaixar fora do fluxo de reabertura (RN005).
    ApuracaoMensal a =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07").orElseThrow();
    assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
  }

  @Test
  @DisplayName("Job fecha o periodo do produtor (FECHADO) com data de fechamento")
  void jobFechaPeriodo() {
    OffsetDateTime julho = OffsetDateTime.of(2026, 7, 20, 8, 0, 0, 0, ZoneOffset.ofHours(-3));
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, julho, "80.00", "80.00");

    int fechadas = apuracaoService.fecharPeriodo("2026-07");

    assertThat(fechadas).isEqualTo(1);
    ApuracaoMensal a =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07").orElseThrow();
    assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
    assertThat(a.getDataFechamento()).isNotNull();
    assertThat(a.getSaldoIbs()).isEqualByComparingTo("80.00");
  }

  @Test
  @DisplayName("Reabre apuracao FECHADA do mes atual: volta a ABERTO com auditoria (quem/quando)")
  void reabreApuracaoFechadaComAuditoria() {
    // Periodo do mes corrente -> sempre dentro da janela de 3 meses (RN005), independe da data.
    String periodo = YearMonth.now(ZONE).toString();
    OffsetDateTime emissao = OffsetDateTime.now(ZONE);
    salvarSaida(1L, StatusNotaFiscal.AUTORIZADA, emissao, "80.00", "80.00");
    apuracaoService.fecharPeriodo(periodo);
    ApuracaoMensal fechada =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), periodo).orElseThrow();
    assertThat(fechada.getStatus()).isEqualTo(StatusApuracao.FECHADO);

    var resposta = apuracaoService.reabrir(fechada.getId());

    assertThat(resposta.status()).isEqualTo("ABERTO");
    ApuracaoMensal reaberta = apuracaoRepository.findById(fechada.getId()).orElseThrow();
    assertThat(reaberta.getStatus()).isEqualTo(StatusApuracao.ABERTO);
    assertThat(reaberta.getDataReabertura()).isNotNull();
    // Auditoria registra o ID do produtor autenticado (identidade mock; nunca CPF/CNPJ — LGPD).
    assertThat(reaberta.getReabertoPor()).isEqualTo(produtor.getId().toString());
    assertThat(reaberta.getReabertoPor()).doesNotContain(produtor.getCpfCnpj());
  }

  @Test
  @DisplayName("Refecha apuracao ABERTA de mes anterior na janela RN005; ignora fora da janela (issue #57)")
  void refechaReabertaDeMesAnteriorDentroDaJanela() {
    YearMonth mesCorrente = YearMonth.now(ZONE);
    YearMonth dentroDaJanela = mesCorrente.minusMonths(1); // reaberta -> deve refechar
    YearMonth foraDaJanela = mesCorrente.minusMonths(4);    // antiga -> nao deve ser tocada

    ApuracaoMensal reaberta = abertaEm(dentroDaJanela.toString());
    ApuracaoMensal antiga = abertaEm(foraDaJanela.toString());

    int refechadas = apuracaoService.refecharPeriodosReabertos();

    assertThat(refechadas).isEqualTo(1);
    ApuracaoMensal refechada = apuracaoRepository.findById(reaberta.getId()).orElseThrow();
    assertThat(refechada.getStatus()).isEqualTo(StatusApuracao.FECHADO);
    assertThat(refechada.getDataFechamento()).isNotNull();
    // Fora da janela de reabertura (RN005): permanece ABERTO — o job nao revisita meses tao antigos.
    assertThat(apuracaoRepository.findById(antiga.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.ABERTO);
  }

  @Test
  @DisplayName("Reabrir apuracao de outro produtor retorna 404 (anti-IDOR)")
  void reabrirDeOutroProdutorRetorna404() {
    Produtor outro = produtorRepository.save(
        Produtor.builder().nomeCompleto("Outro Produtor").cpfCnpj("11122233344").build());
    ApuracaoMensal doOutro = apuracaoRepository.save(ApuracaoMensal.builder()
        .produtor(outro)
        .periodo(YearMonth.now(ZONE).toString())
        .status(StatusApuracao.FECHADO)
        .debitoIbs(BigDecimal.ZERO).debitoCbs(BigDecimal.ZERO)
        .creditoIbs(BigDecimal.ZERO).creditoCbs(BigDecimal.ZERO)
        .saldoIbs(BigDecimal.ZERO).saldoCbs(BigDecimal.ZERO)
        .totalNotas(0L)
        .build());

    // O produtor autenticado (setUp) e "produtor", nao "outro".
    assertThatThrownBy(() -> apuracaoService.reabrir(doOutro.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(apuracaoRepository.findById(doOutro.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.FECHADO);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private ApuracaoMensal abertaEm(String periodo) {
    return apuracaoRepository.save(ApuracaoMensal.builder()
        .produtor(produtor)
        .periodo(periodo)
        .status(StatusApuracao.ABERTO)
        .debitoIbs(BigDecimal.ZERO).debitoCbs(BigDecimal.ZERO)
        .creditoIbs(BigDecimal.ZERO).creditoCbs(BigDecimal.ZERO)
        .saldoIbs(BigDecimal.ZERO).saldoCbs(BigDecimal.ZERO)
        .totalNotas(0L)
        .build());
  }

  private void salvarSaida(
      long numero, StatusNotaFiscal status, OffsetDateTime dataEmissao,
      String ibs, String cbs) {
    NotaFiscal nota = baseNota(numero, dataEmissao);
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setStatus(status);
    nota.setValorTotalIbs(new BigDecimal(ibs));
    nota.setValorTotalCbs(new BigDecimal(cbs));
    notaFiscalRepository.saveAndFlush(nota);
  }

  private void salvarEntradaImportada(OffsetDateTime dataEmissao, String creditoIbs,
      String creditoCbs) {
    NotaFiscal nota = baseNota(100L, dataEmissao);
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    // Chave de acesso ficticia de 44 digitos (unica) — apenas satisfaz a coluna; a apuracao
    // agrega por origem/status/data, nao pela chave.
    nota.setChaveAcesso(String.format("%044d", System.nanoTime()));
    nota.setValorTotalCreditoIbs(new BigDecimal(creditoIbs));
    nota.setValorTotalCreditoCbs(new BigDecimal(creditoCbs));
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

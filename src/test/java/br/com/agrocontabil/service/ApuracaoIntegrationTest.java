package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integração do fluxo crítico da apuração mensal (issue Agrocontabil#41 — RN004).
 *
 * <p>Exercita a pilha real (repositório de agregação + {@link ApuracaoService} + persistência da
 * {@link ApuracaoMensal}) contra H2 (profile {@code test}, schema via {@code @Sql}). Confirma a
 * regra de negócio da DoD: <strong>só saídas AUTORIZADAS</strong> compõem o débito e
 * <strong>só entradas IMPORTADAS</strong> compõem o crédito; RASCUNHO/REJEITADA/CANCELADA e outros
 * meses são excluídos. Verifica também o cálculo do saldo.
 *
 * <p>Os cenários que passam pelo fechamento do job ({@code fecharPeriodo} /
 * {@code refecharPeriodosReabertos}) vivem em {@link ApuracaoFechamentoIntegrationTest}: desde a
 * issue #100 cada produtor fecha numa transação {@code REQUIRES_NEW}, que não enxerga os dados não
 * commitados da transação de teste desta classe.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
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
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();

    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());

    // Autentica o produtor no contexto de seguranca (o AuthenticatedProdutorProvider o le dali).
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
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
  @DisplayName("Produtor nao reabre apuracao (RN005 e do contador, #224): 403, nada muda")
  void produtorNaoReabre() {
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

    // O produtor autenticado (setUp) e "produtor": nem a propria, nem a do outro.
    assertThatThrownBy(() -> apuracaoService.reabrir(doOutro.getId()))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    assertThat(apuracaoRepository.findById(doOutro.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.FECHADO);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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

package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Teste de integração ponta a ponta do recálculo por evento de domínio (issue Agrocontabil#64).
 *
 * <p><strong>Deliberadamente sem {@code @Transactional} de classe</strong> (diferente de
 * {@link ApuracaoIntegrationTest}): o {@code @TransactionalEventListener(AFTER_COMMIT)} só dispara
 * após o <em>commit</em> real da transação — com rollback automático por teste, o listener nunca
 * seria exercitado e o teste daria falso-positivo. Cada operação roda em sua própria transação via
 * {@link TransactionTemplate}, replicando o comportamento real do {@code @Transactional} dos
 * services de produção. O listener não é {@code @Async}, então dispara de forma síncrona, na
 * mesma thread, antes de {@link TransactionTemplate#executeWithoutResult} retornar.
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ApuracaoEventoDominioIntegrationTest {

  private static final ZoneOffset FUSO_BRASIL = ZoneOffset.ofHours(-3);

  @Autowired private ApuracaoService apuracaoService;
  @Autowired private SefazService sefazService;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    transactionTemplate.executeWithoutResult(status -> {
      apuracaoRepository.deleteAll();
      notaFiscalRepository.deleteAll();
      produtorRepository.deleteAll();
      produtor = produtorRepository.save(
          Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
    });
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName(
      "Cancelar NF-e autorizada de periodo ABERTO consolidado dispara evento e recalcula (issue #64)")
  void cancelarNotaEmPeriodoAbertoRecalculaApuracaoAutomaticamente() {
    OffsetDateTime emissao = OffsetDateTime.of(2026, 7, 15, 10, 0, 0, 0, FUSO_BRASIL);
    NotaFiscal nota = salvarSaidaAutorizada(emissao, "150.00", "1350.00");
    transactionTemplate.executeWithoutResult(
        status -> apuracaoService.consolidar("2026-07"));

    ApuracaoMensal antes =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07").orElseThrow();
    assertThat(antes.getDebitoIbs()).isEqualByComparingTo("150.00");

    // Cancela via o fluxo real SefazService -> evento -> listener (AFTER_COMMIT, sincrono).
    transactionTemplate.executeWithoutResult(status ->
        sefazService.cancelar(nota.getId(), "Erro na quantidade dos produtos vendidos"));

    ApuracaoMensal depois =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07").orElseThrow();
    // A nota cancelada some do debito (StatusNotaFiscal.CANCELADA nao conta mais).
    assertThat(depois.getDebitoIbs()).isEqualByComparingTo("0.00");
    assertThat(depois.getStatus()).isEqualTo(StatusApuracao.ABERTO);
  }

  @Test
  @DisplayName("Cancelar NF-e de periodo FECHADO nao recalcula: saldo consolidado preservado (issue #64)")
  void cancelarNotaEmPeriodoFechadoNaoRecalcula() {
    OffsetDateTime emissao = OffsetDateTime.of(2026, 7, 15, 10, 0, 0, 0, FUSO_BRASIL);
    NotaFiscal nota = salvarSaidaAutorizada(emissao, "150.00", "1350.00");
    transactionTemplate.executeWithoutResult(
        status -> apuracaoService.fecharPeriodo("2026-07"));

    transactionTemplate.executeWithoutResult(status ->
        sefazService.cancelar(nota.getId(), "Erro na quantidade dos produtos vendidos"));

    ApuracaoMensal apuracao =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), "2026-07").orElseThrow();
    assertThat(apuracao.getStatus()).isEqualTo(StatusApuracao.FECHADO);
    // Saldo do fechamento preservado — a correcao so ocorre via reabertura auditada (RN005).
    assertThat(apuracao.getDebitoIbs()).isEqualByComparingTo("150.00");
  }

  /**
   * Contador determinístico para {@code numero}/{@code chaveAcesso} das notas de teste — evita a
   * fragilidade de derivar esses valores de {@code System.nanoTime()}, que pode ser negativo (a
   * JVM só garante monotonicidade, não um sinal), quebrando o contrato de {@code numero} positivo
   * e de {@code chaveAcesso} com 44 dígitos puramente numéricos.
   */
  private static final AtomicLong SEQUENCIA_NOTA = new AtomicLong(1);

  private NotaFiscal salvarSaidaAutorizada(OffsetDateTime dataEmissao, String ibs, String cbs) {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua A").numero("1").municipio("Cuiaba")
        .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build();
    long sequencia = SEQUENCIA_NOTA.getAndIncrement();
    return transactionTemplate.execute(status -> {
      NotaFiscal nota = NotaFiscal.builder()
          .numero(sequencia)
          .serie(1).modelo("55")
          .naturezaOperacao("Venda de producao rural")
          .dataEmissao(dataEmissao)
          .produtor(produtor)
          .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
          .destinatarioEndereco(endereco)
          .valorTotalProdutos(new BigDecimal("1000.00"))
          .valorTotalNota(new BigDecimal("1000.00"))
          .status(StatusNotaFiscal.AUTORIZADA)
          .chaveAcesso(String.format("%044d", sequencia))
          .protocoloAutorizacao("141260000000017")
          .dataAutorizacao(dataEmissao)
          .sefazCstat("100")
          .valorTotalIbs(new BigDecimal(ibs))
          .valorTotalCbs(new BigDecimal(cbs))
          .build();
      return notaFiscalRepository.saveAndFlush(nota);
    });
  }
}

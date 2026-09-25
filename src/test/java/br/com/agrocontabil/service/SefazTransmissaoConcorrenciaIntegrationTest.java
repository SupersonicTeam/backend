package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.exception.NotaFiscalNaoCancelavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoConsultavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.sefaz.SefazTransport;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testes de concorrência da transmissão à SEFAZ (Onda 3, issue #96).
 *
 * <p><strong>O defeito que estes testes travam:</strong> antes do lock pessimista e do estado
 * transitório, {@code enviar} lia a nota, checava {@code status == XML_GERADO}, chamava a SEFAZ e
 * salvava. Duas requisições simultâneas passavam <em>as duas</em> pela checagem e:
 * <ul>
 *   <li>transmitiam a mesma NF-e <strong>duas vezes</strong> ao fisco — efeito externo
 *       irreversível;</li>
 *   <li>publicavam <strong>dois</strong> {@code NotaEmitidaEvent}, e a apuração somava o débito de
 *       IBS/CBS em dobro (guia fiscal apurada a maior).</li>
 * </ul>
 * O mesmo valia para o cancelamento, retirando o débito duas vezes.
 *
 * <p>O {@code @Version} da issue #95 não resolve isso: ele só falha no <em>commit</em>, quando as
 * duas já teriam falado com a SEFAZ. Daí o lock.
 *
 * <p>A classe <strong>não</strong> usa {@code @Transactional}: cada thread precisa commitar de
 * verdade, tanto para o lock ser disputado quanto para o
 * {@code @TransactionalEventListener(AFTER_COMMIT)} disparar. O {@code SecurityContextHolder} é
 * thread-local, então cada thread autentica o produtor por conta própria.
 */
@SpringBootTest
@Import({TestDatabaseCleaner.class,
    SefazTransmissaoConcorrenciaIntegrationTest.ContadorDeEventosConfig.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class SefazTransmissaoConcorrenciaIntegrationTest {

  private static final int TIMEOUT_SEGUNDOS = 30;
  /** Prefixo de 38 digitos; os 6 finais vem do contador, dando uma chave unica por nota. */
  private static final String CHAVE_PREFIXO = "41262612345678000195550010000000011000";

  /**
   * O {@code MockSefazTransport} e singleton e lembra as chaves que ja transmitiu (issue #96),
   * para nao autorizar na consulta uma NF-e que nunca saiu. Uma chave fixa compartilhada entre os
   * testes desta classe vazaria esse estado de um teste para o outro — o de "nota nunca
   * transmitida" passaria a ver a chave como conhecida. Daí uma chave nova por nota, o que tambem
   * respeita a unique de {@code chave_acesso}.
   */
  private static final AtomicInteger SEQUENCIA_CHAVE = new AtomicInteger();

  private static String chaveUnica() {
    return CHAVE_PREFIXO + String.format("%06d", SEQUENCIA_CHAVE.incrementAndGet());
  }
  private static final String MOTIVO =
      "Cancelamento por erro de digitacao nos dados do destinatario";

  @Autowired private SefazService sefazService;
  @Autowired private NotaFiscalService notaFiscalService;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ContadorDeEventos contador;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Espia o transporte para contar quantas vezes o fisco foi realmente acionado. */
  @SpyBean private SefazTransport sefazTransport;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    transactionTemplate.executeWithoutResult(status -> {
      cleaner.limparTudo();
      produtor = produtorRepository.save(Produtor.builder()
          .nomeCompleto("Maria Agricultora")
          .cpfCnpj("98765432100")
          .build());
    });
    contador.zerar();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(status -> cleaner.limparTudo());
  }

  @Test
  @DisplayName("enviar concorrente: o XML chega a SEFAZ uma unica vez e o outro request recebe 409")
  void enviarConcorrenteTransmiteUmaUnicaVez() throws Exception {
    UUID notaId = persistir(buildNota(StatusNotaFiscal.XML_GERADO, OffsetDateTime.now()));

    Resultado resultado = emParalelo(() -> sefazService.enviar(notaId));

    assertThat(resultado.sucessos()).hasSize(1);
    assertThat(resultado.falhas()).hasSize(1);
    // Com lock pessimista a recusa e DETERMINISTICA e semanticamente correta: a segunda thread
    // espera o lock, le o estado ja commitado (ENVIANDO) e recebe "nota nao enviavel". Sem o
    // lock ela leria XML_GERADO junto com a primeira e falharia so no commit da reserva, com o
    // 409 genérico de concorrencia (@Version) — seguro, porem opaco para quem consome a API.
    // Fixar o tipo aqui e o que faz este teste distinguir as duas implementacoes.
    assertThat(resultado.falhas().get(0))
        .isInstanceOf(NotaFiscalNaoEnviavelException.class);

    // A asserção central: o efeito externo irreversível aconteceu UMA vez.
    verify(sefazTransport, times(1)).enviar(any(), any(), any());

    // E o evento de domínio também — dois eventos apurariam o débito de IBS/CBS em dobro.
    assertThat(contador.emitidas()).isEqualTo(1);

    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("cancelar concorrente: o evento vai a SEFAZ uma unica vez e o outro recebe 409")
  void cancelarConcorrenteCancelaUmaUnicaVez() throws Exception {
    UUID notaId = persistir(
        buildNota(StatusNotaFiscal.AUTORIZADA, OffsetDateTime.now().minusDays(3)));

    Resultado resultado = emParalelo(() -> sefazService.cancelar(notaId, MOTIVO));

    assertThat(resultado.sucessos()).hasSize(1);
    assertThat(resultado.falhas()).hasSize(1);
    // Deterministico pelo mesmo motivo do envio: o lock garante que a segunda thread leia o
    // estado ja transicionado, entao a recusa e sempre a excecao de dominio, nao o 409 genérico.
    assertThat(resultado.falhas().get(0))
        .isInstanceOf(NotaFiscalNaoCancelavelException.class);

    verify(sefazTransport, times(1)).cancelar(any(), any(), any(), any());

    // Um único NotaCanceladaEvent: dois retirariam o débito do período duas vezes.
    assertThat(contador.canceladas()).isEqualTo(1);

    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.CANCELADA);
  }

  @Test
  @DisplayName("nota em ENVIANDO: toda operacao responde 409 enquanto a transmissao esta em curso")
  void notaEmEnviandoBloqueiaTodasAsOperacoes() {
    UUID notaId = persistir(buildNota(StatusNotaFiscal.ENVIANDO, OffsetDateTime.now()));
    autenticar();

    // O estado transitório é curto, mas enquanto dura precisa ser hermético: qualquer operação
    // que escape daqui atropelaria uma transmissão em andamento.
    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(NotaFiscalNaoEnviavelException.class);
    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(NotaFiscalNaoCancelavelException.class);
    assertThatThrownBy(() -> sefazService.sincronizarStatus(notaId))
        .isInstanceOf(NotaFiscalNaoConsultavelException.class);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalNaoEditavelException.class);
    assertThatThrownBy(() -> notaFiscalService.delete(notaId))
        .isInstanceOf(NotaFiscalNaoEditavelException.class);

    // Nada foi transmitido ao fisco em nenhuma dessas tentativas.
    verify(sefazTransport, times(0)).enviar(any(), any(), any());
    verify(sefazTransport, times(0)).cancelar(any(), any(), any(), any());
  }

  @Test
  @DisplayName("nota em CANCELANDO: toda operacao responde 409")
  void notaEmCancelandoBloqueiaTodasAsOperacoes() {
    UUID notaId = persistir(
        buildNota(StatusNotaFiscal.CANCELANDO, OffsetDateTime.now().minusDays(1)));
    autenticar();

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(NotaFiscalNaoCancelavelException.class);
    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(NotaFiscalNaoEnviavelException.class);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalNaoEditavelException.class);

    verify(sefazTransport, times(0)).cancelar(any(), any(), any(), any());
  }

  @Test
  @DisplayName("reconciliar resgata nota presa em ENVIANDO alem do timeout")
  void reconciliarResgataNotaPresa() {
    NotaFiscal presa = buildNota(StatusNotaFiscal.ENVIANDO, OffsetDateTime.now());
    UUID notaId = persistir(presa);

    // Simula o processo derrubado no meio da transmissão: a nota ficou parada muito antes do
    // corte. O envelhecimento vai por SQL direto porque o @PreUpdate da entidade sobrescreveria
    // o updatedAt com "agora" em qualquer gravacao via ORM — o teste passaria a nao provar nada.
    jdbcTemplate.update(
        "UPDATE nota_fiscal SET updated_at = ? WHERE id = ?",
        Timestamp.from(OffsetDateTime.now().minusHours(2).toInstant()), notaId);

    assertThat(sefazService.reconciliarTransmissoesPresas()).isEqualTo(1);

    // PENDENTE_SEFAZ e o estado que faz o job consultar a chave e descobrir o desfecho real.
    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
  }

  @Test
  @DisplayName("nota nunca transmitida nao e autorizada pela sincronizacao nem gera evento")
  void notaNuncaTransmitidaNaoEhAutorizadaPelaSincronizacao() {
    // Cenario do code review deste PR: o envio falha ANTES de chegar ao fisco, a compensacao
    // deixa a nota em PENDENTE_SEFAZ e o job a sincroniza. Como o mock respondia AUTORIZADA para
    // qualquer chave, a nota virava AUTORIZADA com protocolo ficticio e publicava
    // NotaEmitidaEvent — o debito de IBS/CBS de uma NF-e que nunca saiu entrava na apuracao.
    UUID notaId = persistir(buildNota(StatusNotaFiscal.PENDENTE_SEFAZ, OffsetDateTime.now()));

    int atualizadas = sefazService.sincronizarPendentes();

    assertThat(atualizadas).isEqualTo(1);
    // cStat 217 (nao consta na base da SEFAZ) -> REJEITADA, nunca AUTORIZADA.
    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.REJEITADA);
    // E, sobretudo, nenhum evento de debito para uma nota inexistente no fisco.
    assertThat(contador.emitidas()).isZero();
  }

  @Test
  @DisplayName("nota efetivamente transmitida e autorizada pela sincronizacao e publica o evento")
  void notaTransmitidaEhAutorizadaPelaSincronizacao() {
    UUID notaId = persistir(buildNota(StatusNotaFiscal.XML_GERADO, OffsetDateTime.now()));
    autenticar();

    // Transmite de verdade (o mock passa a conhecer a chave) e devolve a nota a PENDENTE_SEFAZ,
    // como se o desfecho tivesse ficado indefinido.
    sefazService.enviar(notaId);
    jdbcTemplate.update(
        "UPDATE nota_fiscal SET status = 'PENDENTE_SEFAZ' WHERE id = ?", notaId);
    contador.zerar();

    assertThat(sefazService.sincronizarPendentes()).isEqualTo(1);

    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.AUTORIZADA);
    // O contraponto do teste acima: aqui a nota existe no fisco, entao o evento DEVE sair — do
    // contrario a apuracao ficaria a menor (a guarda da issue #79).
    assertThat(contador.emitidas()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Dispara a mesma operação em duas threads alinhadas por latch e separa sucessos de falhas. */
  private Resultado emParalelo(Callable<?> operacao) throws Exception {
    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Object> tarefa = () -> {
        autenticar();
        largada.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
        try {
          return operacao.call();
        } finally {
          SecurityContextHolder.clearContext();
        }
      };

      List<Future<Object>> futures =
          List.of(executor.submit(tarefa), executor.submit(tarefa));
      largada.countDown();

      List<Object> sucessos = new ArrayList<>();
      List<Throwable> falhas = new ArrayList<>();
      for (Future<Object> future : futures) {
        try {
          sucessos.add(future.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          falhas.add(e.getCause());
        }
      }
      return new Resultado(sucessos, falhas);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void autenticar() {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
  }

  private UUID persistir(NotaFiscal nota) {
    return transactionTemplate.execute(status -> notaFiscalRepository.save(nota)).getId();
  }

  private NotaFiscal buildNota(StatusNotaFiscal status, OffsetDateTime dataEmissao) {
    String chave = chaveUnica();
    return NotaFiscal.builder()
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(dataEmissao)
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(status)
        .chaveAcesso(chave)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + chave + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .protocoloAutorizacao("141260000000017")
        .build();
  }

  /** Resultado agregado das duas threads. */
  private record Resultado(List<Object> sucessos, List<Throwable> falhas) {
  }

  /**
   * Conta os eventos de domínio efetivamente publicados.
   *
   * <p>É a prova que mais importa para a apuração: o {@code ApuracaoService} escuta estes eventos
   * para recalcular débito e crédito do período, então um evento duplicado vira imposto apurado em
   * duplicidade.
   */
  @Component
  static class ContadorDeEventos {

    private final AtomicInteger emitidas = new AtomicInteger();
    private final AtomicInteger canceladas = new AtomicInteger();

    @EventListener
    void aoEmitir(NotaEmitidaEvent evento) {
      emitidas.incrementAndGet();
    }

    @EventListener
    void aoCancelar(NotaCanceladaEvent evento) {
      canceladas.incrementAndGet();
    }

    void zerar() {
      emitidas.set(0);
      canceladas.set(0);
    }

    int emitidas() {
      return emitidas.get();
    }

    int canceladas() {
      return canceladas.get();
    }
  }

  /** Registra o contador de eventos no contexto de teste. */
  @TestConfiguration
  static class ContadorDeEventosConfig {

    @org.springframework.context.annotation.Bean
    ContadorDeEventos contadorDeEventos() {
      return new ContadorDeEventos();
    }
  }
}

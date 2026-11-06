package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SefazTransport;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import br.com.agrocontabil.service.SefazService;
import br.com.agrocontabil.support.ContadorDeEventos;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transmissão à SEFAZ com desfecho incerto ou recusado, ponta a ponta pelo HTTP (issues #83/#84).
 *
 * <p>O {@code MockSefazTransport} do contexto é espiado ({@link SpyBean}) para reproduzir o que o
 * transporte real faz e o mock nunca fazia: perder a resposta depois de a SEFAZ processar, falhar
 * antes de conectar, recusar o evento de cancelamento. O teste observa o que importa ao produtor e
 * à apuração:
 * <ul>
 *   <li>quantas vezes o XML ou o evento chegou ao fisco (efeito externo irreversível);</li>
 *   <li>o status final da nota e a resposta HTTP;</li>
 *   <li>quantos eventos de domínio saíram (um a mais ou a menos vira imposto apurado errado);</li>
 *   <li>que nenhuma chamada à SEFAZ acontece com transação aberta ou conexão do pool presa.</li>
 * </ul>
 *
 * <p>Todos os cenários compartilham um contexto (um só {@code @SpyBean}); cada teste usa uma chave
 * nova porque o mock lembra as chaves transmitidas e canceladas.
 */
@SpringBootTest
@Import({TestDatabaseCleaner.class, ContadorDeEventos.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    // Retry real (3 tentativas), sem esperar 1s entre elas.
    "app.nfe.sefaz.retry.backoff-ms=1",
    // Lote pequeno para o T6 atravessar varios lotes do keyset com poucas notas.
    "app.nfe.sefaz.sync.lote-tamanho=2"})
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class SefazEntregaIncertaIntegrationTest {

  /** Prefixo de 38 dígitos; os 6 finais vêm da sequência, uma chave nova por nota. */
  private static final String CHAVE_PREFIXO = "41262612345678000195550010000000011000";
  private static final AtomicInteger SEQUENCIA_CHAVE = new AtomicInteger();
  private static final String XMOTIVO_RECUSA =
      "Rejeicao: Pedido de Cancelamento intempestivo (NF-e autorizada a mais de 7 dias)";
  private static final String CORPO_CANCELAMENTO =
      "{\"motivo\": \"Cancelamento por erro de digitacao nos dados do destinatario\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ContadorDeEventos contador;
  @Autowired private DataSource dataSource;
  @Autowired private SefazService sefazService;

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
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
    contador.zerar();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(status -> cleaner.limparTudo());
  }

  // -------------------------------------------------------------------------
  // Envio (issue #83)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("T1: timeout depois de a SEFAZ autorizar nao reenvia, reconcilia e autoriza uma vez")
  void timeoutAposAutorizacaoNaoDuplica() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO);
    // O fisco processa (o mock registra a chave como autorizada) e a resposta se perde.
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new SefazRespostaIndeterminadaException("read timeout simulado");
    }).when(sefazTransport).enviar(any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"));

    // A asserção central: o XML chegou ao fisco UMA vez. Com o retry cego de antes, o client
    // reenviaria, a SEFAZ responderia 204 e a nota terminaria REJEITADA — autorizada no fisco.
    verify(sefazTransport, times(1)).enviar(any(), any(), any());
    verify(sefazTransport, times(1)).consultarStatus(any(), any());
    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(contador.emitidas()).isEqualTo(1);
  }

  @Test
  @DisplayName("T2: falha de conexao e re-tentada e devolve a nota a XML_GERADO com 502")
  void falhaDeConexaoEhRetentadaEVoltaParaXmlGerado() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO);
    doThrow(new SefazConexaoException("connection refused simulado"))
        .when(sefazTransport).enviar(any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.errors[0].code").value("SEFAZ_COMUNICACAO_FALHOU"));

    // Retry seguro: nada saiu. As 3 tentativas padrao de app.nfe.sefaz.retry.max-attempts.
    verify(sefazTransport, times(3)).enviar(any(), any(), any());
    // Sem consulta: nao ha desfecho a descobrir.
    verify(sefazTransport, never()).consultarStatus(any(), any());
    // E a nota volta a ser enviavel, em vez de ficar pendente esperando o job.
    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.XML_GERADO);
    assertThat(contador.emitidas()).isZero();
  }

  @Test
  @DisplayName("T4: reenvio de chave ja autorizada (cStat 204) termina AUTORIZADA, nao REJEITADA")
  void duplicidadeTerminaAutorizada() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO);
    // O fisco ja tem a chave (envio anterior processado, resposta perdida fora deste fluxo).
    sefazTransport.enviar("<NFe/>", chaveDe(notaId), null);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"));

    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(contador.emitidas()).isEqualTo(1);
  }

  @Test
  @DisplayName("T5: nenhuma chamada a SEFAZ acontece com transacao ativa ou conexao do pool presa")
  void chamadasASefazNaoSeguramTransacaoNemConexao() throws Exception {
    List<String> violacoes = new CopyOnWriteArrayList<>();
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
    Answer<Object> observando = inv -> {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        violacoes.add(inv.getMethod().getName() + ": transacao ativa");
      }
      int ativas = hikari.getHikariPoolMXBean().getActiveConnections();
      if (ativas > 0) {
        violacoes.add(inv.getMethod().getName() + ": " + ativas + " conexao(oes) ativa(s)");
      }
      return inv.callRealMethod();
    };

    // Envio que perde a resposta: exercita envio + consulta de reconciliacao.
    UUID enviada = persistir(StatusNotaFiscal.XML_GERADO);
    doAnswer(inv -> {
      observando.answer(inv);
      throw new SefazRespostaIndeterminadaException("read timeout simulado");
    }).when(sefazTransport).enviar(any(), any(), any());
    doAnswer(observando).when(sefazTransport).consultarStatus(any(), any());
    doAnswer(observando).when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", enviada))
        .andExpect(status().isOk());
    // Cancelamento da mesma nota, agora autorizada.
    mockMvc.perform(post("/api/notas/{id}/cancelar", enviada)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isOk());

    verify(sefazTransport, times(1)).enviar(any(), any(), any());
    verify(sefazTransport, times(1)).consultarStatus(any(), any());
    verify(sefazTransport, times(1)).cancelar(any(), any(), any(), any());
    // A chamada de rede com transacao aberta seguraria uma conexao do pool pelo tempo do timeout
    // da SEFAZ: com a SEFAZ fora, o pool esgota e o backend inteiro para (issue #83). A checagem
    // do pool pega tambem o open-in-view: foi este teste que achou o OSIV religado pela #125.
    assertThat(violacoes).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Sincronizacao em lote (issue #83)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("T6: sync atravessa varios lotes e um erro numa nota nao impede as outras")
  void syncProgrideApesarDeErroEmUmaNota() {
    // Cinco notas pendentes que a SEFAZ autorizou (o mock conhece as chaves). Com lote de 2, o
    // keyset percorre tres lotes.
    List<UUID> notas = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID notaId = persistir(StatusNotaFiscal.PENDENTE_SEFAZ);
      sefazTransport.enviar("<NFe/>", chaveDe(notaId), null);
      notas.add(notaId);
    }
    // Uma delas quebra com erro que nao e de comunicacao (dado inconsistente, por exemplo).
    UUID quebrada = notas.get(1);
    doThrow(new IllegalStateException("dado inconsistente simulado"))
        .when(sefazTransport).consultarStatus(eq(chaveDe(quebrada)), any());

    int atualizadas = sefazService.sincronizarPendentes();

    // Antes, uma excecao fora de SefazComunicacaoException revertia o lote inteiro e o job nunca
    // progredia. Agora cada nota tem a sua transacao.
    assertThat(atualizadas).isEqualTo(4);
    assertThat(statusDe(quebrada)).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    notas.stream().filter(id -> !id.equals(quebrada)).forEach(id ->
        assertThat(statusDe(id)).isEqualTo(StatusNotaFiscal.AUTORIZADA));
    assertThat(contador.emitidas()).isEqualTo(4);
  }

  // -------------------------------------------------------------------------
  // Cancelamento (issues #83 e #84)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Cancelamento com resposta perdida e evento registrado no fisco termina CANCELADA")
  void cancelamentoComRespostaPerdidaTerminaCancelada() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA);
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new SefazRespostaIndeterminadaException("read timeout simulado");
    }).when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"));

    verify(sefazTransport, times(1)).cancelar(any(), any(), any(), any());
    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(contador.canceladas()).isEqualTo(1);
  }

  @Test
  @DisplayName("Cancelamento repetido (cStat 573) com cancelamento ja registrado termina CANCELADA")
  void cancelamentoRepetidoTerminaCancelada() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA);
    // O evento ja foi registrado antes (resposta perdida fora deste fluxo).
    sefazTransport.cancelar(chaveDe(notaId), "motivo qualquer com 15+", "1", null);

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"));

    assertThat(contador.canceladas()).isEqualTo(1);
  }

  @Test
  @DisplayName("SEFAZ recusa o cancelamento: 422 CANCELAMENTO_REJEITADO com cStat e xMotivo")
  void recusaDoFiscoRespondeUnprocessableEntityComCstatEXmotivo() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA);
    doReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "501", XMOTIVO_RECUSA, null, null))
        .when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("CANCELAMENTO_REJEITADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("501"))
        // O motivo do fisco vai ao cliente como veio: e ele que diz ao produtor o que resolver.
        .andExpect(jsonPath("$.errors[0].message").value(XMOTIVO_RECUSA));

    // A recusa nao cancelou nada: a nota segue AUTORIZADA (e cancelavel) e o debito do periodo
    // nao muda — nenhum NotaCanceladaEvent.
    NotaFiscal nota = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloCancelamento()).isNull();
    assertThat(contador.canceladas()).isZero();
  }

  @Test
  @DisplayName("SEFAZ recusa sem xMotivo: a mensagem cai no texto generico com o cStat")
  void recusaSemMotivoUsaMensagemGenerica() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA);
    doReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "580", null, null, null))
        .when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("CANCELAMENTO_REJEITADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("580"))
        .andExpect(jsonPath("$.errors[0].message")
            .value("A SEFAZ recusou o cancelamento (cStat 580)."));
  }

  // -------------------------------------------------------------------------
  // Compra importada (issue #418)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Compra importada autorizada nao e cancelada pelo destinatario: 409, nada transmitido")
  void compraImportadaNaoPodeSerCanceladaPeloProdutor() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA, OrigemNotaFiscal.IMPORTADA);

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("CANCELAMENTO_COMPRA_IMPORTADA"))
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.containsString("fornecedor que a emitiu")));

    verify(sefazTransport, never()).cancelar(any(), any(), any(), any());
    NotaFiscal nota = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloCancelamento()).isNull();
    assertThat(contador.canceladas()).isZero();
  }

  @Test
  @DisplayName("Nota propria autorizada segue cancelando (controle da #418)")
  void notaPropriaSegueCancelando() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA, OrigemNotaFiscal.PROPRIA);

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO_CANCELAMENTO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"));

    assertThat(contador.canceladas()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private StatusNotaFiscal statusDe(UUID notaId) {
    return notaFiscalRepository.findById(notaId).orElseThrow().getStatus();
  }

  private String chaveDe(UUID notaId) {
    return notaFiscalRepository.findById(notaId).orElseThrow().getChaveAcesso();
  }

  private UUID persistir(StatusNotaFiscal status) {
    return persistir(status, OrigemNotaFiscal.PROPRIA);
  }

  private UUID persistir(StatusNotaFiscal status, OrigemNotaFiscal origem) {
    String chave = CHAVE_PREFIXO + String.format("%06d", SEQUENCIA_CHAVE.incrementAndGet());
    return transactionTemplate.execute(tx -> notaFiscalRepository.save(NotaFiscal.builder()
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now().minusDays(2))
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(status)
        .origem(origem)
        .chaveAcesso(chave)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + chave + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .versaoRegraFiscal(br.com.agrocontabil.nfe.VersaoRegraFiscal.ATUAL)
        .protocoloAutorizacao(status == StatusNotaFiscal.AUTORIZADA ? "141260000000017" : null)
        .build())).getId();
  }
}

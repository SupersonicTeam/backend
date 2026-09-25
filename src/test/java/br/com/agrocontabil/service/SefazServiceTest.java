package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.exception.CancelamentoForaPrazoException;
import br.com.agrocontabil.exception.CancelamentoRejeitadoException;
import br.com.agrocontabil.exception.NotaFiscalNaoCancelavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoConsultavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testes unitarios do {@link SefazService} (issue #15 — envio SEFAZ).
 *
 * <p>Mockito puro: {@link NotaFiscalRepository}, {@link AuthenticatedProdutorProvider} e
 * {@link SefazClient} sao mockados. {@link SefazProperties} usa instancia real (default
 * HOMOLOGACAO) para evitar stubs desnecessarios (strict stubs).
 *
 * <p>Cenarios: envio autorizado, rejeicao persistida, status invalido (409), ownership (404)
 * e falha de comunicacao propagada (502).
 */
@ExtendWith(MockitoExtension.class)
class SefazServiceTest {

  @Mock
  private NotaFiscalRepository notaFiscalRepository;

  @Mock
  private AuthenticatedProdutorProvider produtorProvider;

  @Mock
  private SefazClient sefazClient;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  /** Instancia real — default HOMOLOGACAO; evita UnnecessaryStubbing nos early returns. */
  private final SefazProperties sefazProperties = new SefazProperties();

  private SefazService sefazService;

  private static final String CHAVE = "41262612345678000195550010000000011000000012";
  private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

  private Produtor produtor;
  private UUID produtorId;

  @BeforeEach
  void setUp() {
    produtorId = UUID.randomUUID();
    produtor = Produtor.builder()
        .id(produtorId)
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();
    // TransactionTemplate real sobre um gerenciador dublado: o callback roda de verdade (o que
    // interessa ao teste), sem exigir banco nem stub de transacao em cada caso.
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(mock(PlatformTransactionManager.class));
    sefazService = new SefazService(
        notaFiscalRepository, produtorProvider, sefazClient, sefazProperties, eventPublisher,
        transactionTemplate);
  }

  @Test
  @DisplayName("enviar deve autorizar a nota e persistir protocolo/data quando cStat=100")
  void enviarShouldAuthorizeAndPersistProtocol() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    OffsetDateTime autorizacao = OffsetDateTime.now();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    // issue #96: no instante da chamada a SEFAZ a nota tem de estar em ENVIANDO e ja persistida.
    // E esse estado, gravado numa transacao curta que commita ANTES da rede, que faz a segunda
    // requisicao concorrente receber 409 em vez de transmitir de novo.
    when(sefazClient.enviar(eq(nota.getXmlGerado()), eq(CHAVE), eq(SefazAmbiente.HOMOLOGACAO)))
        .thenAnswer(inv -> {
          assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.ENVIANDO);
          verify(notaFiscalRepository).save(nota);
          return new SefazRetorno(
              SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
              autorizacao);
        });
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazEnvioResponse response = sefazService.enviar(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(response.cStat()).isEqualTo("100");
    assertThat(response.protocolo()).isEqualTo("141260000000017");
    assertThat(response.dataAutorizacao()).isEqualTo(autorizacao);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloAutorizacao()).isEqualTo("141260000000017");
    assertThat(nota.getSefazAmbiente()).isEqualTo(SefazAmbiente.HOMOLOGACAO);

    // Duas gravacoes, uma por transacao curta: reserva (ENVIANDO) e desfecho (AUTORIZADA).
    verify(notaFiscalRepository, times(2)).save(nota);
    // E a ordem que importa: a nota e persistida ANTES da rede, nao depois.
    InOrder ordem = inOrder(notaFiscalRepository, sefazClient);
    ordem.verify(notaFiscalRepository).save(nota);
    ordem.verify(sefazClient).enviar(any(), any(), any());
    ordem.verify(notaFiscalRepository).save(nota);

    // issue Agrocontabil#64: autorizacao publica evento para recalculo da apuracao (debito).
    // Exatamente uma vez — dois eventos apurariam o debito em dobro.
    verify(eventPublisher, times(1)).publishEvent(new NotaEmitidaEvent(
        produtorId, YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL))));
  }

  @Test
  @DisplayName("enviar deve marcar REJEITADA e nao preencher protocolo quando o fisco rejeita")
  void enviarShouldPersistRejectionWithoutProtocol() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.enviar(any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.REJEITADA, "204", "Duplicidade de NF-e", null, null));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazEnvioResponse response = sefazService.enviar(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.REJEITADA);
    assertThat(response.cStat()).isEqualTo("204");
    assertThat(response.protocolo()).isNull();
    assertThat(nota.getProtocoloAutorizacao()).isNull();
    assertThat(nota.getDataAutorizacao()).isNull();
    // Rejeicao nao afeta debito/credito da apuracao: nenhum evento de dominio e publicado.
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("enviar deve lancar NotaFiscalNaoEnviavelException quando status nao e XML_GERADO")
  void enviarShouldThrowWhenStatusIsNotXmlGerado() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.RASCUNHO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(NotaFiscalNaoEnviavelException.class)
        .hasMessageContaining("RASCUNHO");

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("enviar deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void enviarShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("enviar deve propagar 502 e compensar a nota para PENDENTE_SEFAZ")
  void enviarShouldPropagateCommunicationFailure() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("timeout simulado"));

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(SefazComunicacaoException.class);

    // issue #96: a falha nao diz se o XML chegou ao fisco, entao a nota NAO pode voltar a
    // XML_GERADO — isso liberaria um reenvio que duplicaria a NF-e. PENDENTE_SEFAZ e o estado
    // "transmiti, desfecho desconhecido": o job consulta pela chave e resolve.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    // Duas gravacoes: a reserva (ENVIANDO) e a compensacao (PENDENTE_SEFAZ).
    verify(notaFiscalRepository, times(2)).save(nota);
    // Nenhum evento de dominio: sem autorizacao confirmada, a apuracao nao muda.
    verify(eventPublisher, never()).publishEvent(any());
  }

  // -------------------------------------------------------------------------
  // sincronizarStatus / sincronizarPendentes (issue #16)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("sincronizarStatus deve atualizar nota PENDENTE_SEFAZ para AUTORIZADA")
  void sincronizarStatusShouldUpdatePendingToAuthorized() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.PENDENTE_SEFAZ);
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);
    OffsetDateTime autorizacao = OffsetDateTime.now();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    // issue #96: a consulta a SEFAZ saiu da transacao — a tx de escrita recarrega com lock.
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.consultarStatus(eq(CHAVE), eq(SefazAmbiente.HOMOLOGACAO)))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            autorizacao));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazStatusResponse response = sefazService.sincronizarStatus(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(response.protocolo()).isEqualTo("141260000000017");
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("sincronizarStatus deve lancar NotaFiscalNaoConsultavelException quando RASCUNHO")
  void sincronizarStatusShouldThrowWhenRascunho() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.RASCUNHO);
    nota.setChaveAcesso(null);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.sincronizarStatus(notaId))
        .isInstanceOf(NotaFiscalNaoConsultavelException.class);

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("sincronizarStatus NAO deve ressuscitar nota CANCELADA (estado terminal) para AUTORIZADA")
  void sincronizarStatusShouldNotResurrectTerminalNote() {
    // Regressao: a guarda antiga so barrava RASCUNHO/chave nula, entao uma nota CANCELADA
    // (chave != null, status != RASCUNHO) passava e o transporte de consulta — que sempre
    // responde AUTORIZADA — sobrescrevia o status, ressuscitando juridicamente a nota e
    // recolocando-a no debito da apuracao. Uma nota em estado terminal nao e sincronizavel.
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(3));
    nota.setStatus(StatusNotaFiscal.CANCELADA);
    nota.setDataCancelamento(OffsetDateTime.now().minusDays(1));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.sincronizarStatus(notaId))
        .isInstanceOf(NotaFiscalNaoConsultavelException.class);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.CANCELADA);
    // A guarda barra antes de qualquer I/O: a SEFAZ nao chega a ser consultada nem a nota salva.
    verify(sefazClient, never()).consultarStatus(any(), any());
    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("sincronizarStatus deve publicar NotaEmitidaEvent ao confirmar AUTORIZADA (recalculo da apuracao)")
  void sincronizarStatusShouldPublishEventWhenBecomesAuthorized() {
    // Regressao: enviar() publica NotaEmitidaEvent na transicao para AUTORIZADA, mas a
    // sincronizacao (endpoint manual e job) fazia a MESMA transicao PENDENTE_SEFAZ -> AUTORIZADA
    // sem publicar — deixando a apuracao ja consolidada com o debito de IBS/CBS subestimado.
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.PENDENTE_SEFAZ);
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    // issue #96: a consulta a SEFAZ saiu da transacao — a tx de escrita recarrega com lock.
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.consultarStatus(eq(CHAVE), eq(SefazAmbiente.HOMOLOGACAO)))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            OffsetDateTime.now()));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    sefazService.sincronizarStatus(notaId);

    verify(eventPublisher).publishEvent(new NotaEmitidaEvent(
        produtorId, YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL))));
  }

  @Test
  @DisplayName("sincronizarStatus deve lancar ResourceNotFoundException quando nao encontrada")
  void sincronizarStatusShouldThrowWhenNotFound() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.sincronizarStatus(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());
  }

  @Test
  @DisplayName("sincronizarPendentes deve consultar a SEFAZ e contar as notas atualizadas")
  void sincronizarPendentesShouldConsultAndCountUpdated() {
    NotaFiscal nota = buildNotaXmlGerado(UUID.randomUUID());
    nota.setStatus(StatusNotaFiscal.PENDENTE_SEFAZ);

    // issue #96: a consulta saiu da transacao e cada nota e aplicada na sua propria tx curta,
    // que recarrega pelo id antes de gravar.
    when(notaFiscalRepository.findById(nota.getId())).thenReturn(Optional.of(nota));
    when(notaFiscalRepository.findByStatus(StatusNotaFiscal.PENDENTE_SEFAZ))
        .thenReturn(List.of(nota));
    when(sefazClient.consultarStatus(eq(CHAVE), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            OffsetDateTime.now()));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    int atualizadas = sefazService.sincronizarPendentes();

    assertThat(atualizadas).isEqualTo(1);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    // O job tambem deve publicar o evento na transicao PENDENTE_SEFAZ -> AUTORIZADA, para que a
    // apuracao ja consolidada seja recalculada (issue Agrocontabil#64) — caminho do job agendado.
    verify(eventPublisher).publishEvent(new NotaEmitidaEvent(
        produtorId, YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL))));
  }

  // -------------------------------------------------------------------------
  // cancelar (issue #21 — RN006, limite de 30 dias)
  // -------------------------------------------------------------------------

  private static final String MOTIVO_VALIDO = "Erro na quantidade dos produtos vendidos";

  @Test
  @DisplayName("cancelar deve marcar CANCELADA e persistir data/motivo/protocolo quando dentro do prazo")
  void cancelarShouldCancelWhenAuthorizedAndWithinDeadline() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(5));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    // issue #96: durante a chamada a SEFAZ a nota tem de estar em CANCELANDO e ja persistida —
    // e o que faz um segundo cancelamento concorrente receber 409 em vez de transmitir o evento
    // de novo (e publicar um segundo NotaCanceladaEvent, retirando o debito duas vezes).
    when(sefazClient.cancelar(eq(CHAVE), eq(MOTIVO_VALIDO), eq("141260000000017"),
        eq(SefazAmbiente.HOMOLOGACAO)))
        .thenAnswer(inv -> {
          assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.CANCELANDO);
          verify(notaFiscalRepository).save(nota);
          return new SefazRetorno(
              SituacaoSefaz.AUTORIZADA, "135", "Evento registrado e vinculado a NF-e",
              "141260000000099", OffsetDateTime.now());
        });
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    CancelamentoResponse response = sefazService.cancelar(notaId, MOTIVO_VALIDO);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(response.sefazCstat()).isEqualTo("135");
    assertThat(response.protocoloCancelamento()).isEqualTo("141260000000099");
    assertThat(response.motivoCancelamento()).isEqualTo(MOTIVO_VALIDO);
    assertThat(response.dataCancelamento()).isNotNull();
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(nota.getDataCancelamento()).isNotNull();
    assertThat(nota.getMotivoCancelamento()).isEqualTo(MOTIVO_VALIDO);
    assertThat(nota.getProtocoloCancelamento()).isEqualTo("141260000000099");

    // Duas gravacoes, uma por transacao curta: reserva (CANCELANDO) e desfecho (CANCELADA).
    verify(notaFiscalRepository, times(2)).save(nota);
    // A nota e persistida ANTES de o evento ir a SEFAZ.
    InOrder ordem = inOrder(notaFiscalRepository, sefazClient);
    ordem.verify(notaFiscalRepository).save(nota);
    ordem.verify(sefazClient).cancelar(any(), any(), any(), any());
    ordem.verify(notaFiscalRepository).save(nota);

    // issue Agrocontabil#64: cancelamento publica evento para recalculo da apuracao (retira o
    // debito do periodo original de emissao, nao o da data do cancelamento). Uma unica vez.
    verify(eventPublisher, times(1)).publishEvent(new NotaCanceladaEvent(
        produtorId, YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL))));
  }

  @Test
  @DisplayName("cancelar deve lancar NotaFiscalNaoCancelavelException quando status nao e AUTORIZADA")
  void cancelarShouldThrowWhenStatusIsNotAutorizada() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(1));
    nota.setStatus(StatusNotaFiscal.XML_GERADO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(NotaFiscalNaoCancelavelException.class)
        .hasMessageContaining("XML_GERADO");

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("cancelar deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void cancelarShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("cancelar deve permitir cancelamento exatamente no 30o dia (limite inclusivo)")
  void cancelarShouldAllowOnExactly30thDay() {
    UUID notaId = UUID.randomUUID();
    // Emissao ha quase 30 dias (30 dias menos 1 hora) — ainda dentro do limite inclusivo.
    NotaFiscal nota =
        buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(30).plusHours(1));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "135", "Evento registrado e vinculado a NF-e",
            "141260000000099", OffsetDateTime.now()));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    CancelamentoResponse response = sefazService.cancelar(notaId, MOTIVO_VALIDO);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.CANCELADA);
  }

  @Test
  @DisplayName("cancelar deve lancar CancelamentoForaPrazoException no 31o dia (fora do prazo)")
  void cancelarShouldThrowOn31stDay() {
    UUID notaId = UUID.randomUUID();
    // Emissao ha 31 dias — passou do limite de 30 dias corridos.
    NotaFiscal nota =
        buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(31));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(CancelamentoForaPrazoException.class);

    verify(sefazClient, never()).cancelar(any(), any(), any(), any());
    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("cancelar deve propagar 502 e devolver a nota para AUTORIZADA")
  void cancelarShouldPropagateCommunicationFailure() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(2));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("timeout simulado"));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(SefazComunicacaoException.class);

    // issue #96: volta para AUTORIZADA, e NAO para PENDENTE_SEFAZ. A consulta de status nao
    // reconhece cancelamento (mapStatus nao mapeia CANCELADA), entao o job reautorizaria a nota —
    // a regressao fechada pela issue #79. Repetir o cancelamento e seguro: o fisco responde
    // cStat de evento ja vinculado.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    // Duas gravacoes: a reserva (CANCELANDO) e a compensacao (AUTORIZADA).
    verify(notaFiscalRepository, times(2)).save(nota);
    // Nenhum evento: o cancelamento nao se concretizou, o debito do periodo nao muda.
    verify(eventPublisher, never()).publishEvent(any());
  }

  private NotaFiscal buildNotaAutorizada(UUID notaId, OffsetDateTime dataEmissao) {
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setDataEmissao(dataEmissao);
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);
    nota.setProtocoloAutorizacao("141260000000017");
    nota.setDataAutorizacao(dataEmissao);
    nota.setSefazCstat("100");
    nota.setSefazMotivo("Autorizado o uso da NF-e");
    return nota;
  }

  // -------------------------------------------------------------------------
  // reconciliarTransmissoesPresas (issue #96)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("reconciliar move nota presa em ENVIANDO para PENDENTE_SEFAZ")
  void reconciliarShouldMovePresaEmEnviandoParaPendente() {
    NotaFiscal presa = buildNotaXmlGerado(UUID.randomUUID());
    presa.setStatus(StatusNotaFiscal.ENVIANDO);

    when(notaFiscalRepository.findByStatusInAndUpdatedAtBefore(
        eq(List.of(StatusNotaFiscal.ENVIANDO, StatusNotaFiscal.CANCELANDO)), any()))
        .thenReturn(List.of(presa));
    // Cada nota e reconciliada na sua propria transacao, que recarrega a nota pelo id.
    when(notaFiscalRepository.findById(presa.getId())).thenReturn(Optional.of(presa));

    int reconciliadas = sefazService.reconciliarTransmissoesPresas();

    assertThat(reconciliadas).isEqualTo(1);
    // O XML pode ter chegado ao fisco: quem estabelece a verdade e a consulta pela chave, e e
    // PENDENTE_SEFAZ que a dispara. Voltar a XML_GERADO liberaria um reenvio duplicado.
    assertThat(presa.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    verify(notaFiscalRepository).save(presa);
  }

  @Test
  @DisplayName("reconciliar devolve nota presa em CANCELANDO para AUTORIZADA")
  void reconciliarShouldMovePresaEmCancelandoParaAutorizada() {
    NotaFiscal presa = buildNotaAutorizada(UUID.randomUUID(), OffsetDateTime.now().minusDays(1));
    presa.setStatus(StatusNotaFiscal.CANCELANDO);

    when(notaFiscalRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(presa));
    when(notaFiscalRepository.findById(presa.getId())).thenReturn(Optional.of(presa));

    int reconciliadas = sefazService.reconciliarTransmissoesPresas();

    assertThat(reconciliadas).isEqualTo(1);
    // NAO vai para PENDENTE_SEFAZ: a consulta de status nao reconhece cancelamento e o job
    // reautorizaria a nota — a regressao fechada pela issue #79.
    assertThat(presa.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    verify(notaFiscalRepository).save(presa);
  }

  @Test
  @DisplayName("reconciliar respeita o timeout configurado ao escolher o corte")
  void reconciliarShouldUseConfiguredTimeoutAsCutoff() {
    sefazProperties.getTransmissao().setTimeoutMs(600_000L);
    when(notaFiscalRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of());

    OffsetDateTime antes = OffsetDateTime.now();
    assertThat(sefazService.reconciliarTransmissoesPresas()).isZero();

    // O corte tem de ficar no passado pelo tempo configurado: uma transmissao em andamento
    // (com retry) nao pode ser reconciliada no meio do caminho.
    ArgumentCaptor<OffsetDateTime> corte = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(notaFiscalRepository)
        .findByStatusInAndUpdatedAtBefore(any(), corte.capture());
    assertThat(corte.getValue()).isBefore(antes.minusMinutes(9));
  }

  @Test
  @DisplayName("reconciliar nao grava nada quando nao ha nota presa")
  void reconciliarShouldDoNothingWhenNothingStuck() {
    when(notaFiscalRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of());

    assertThat(sefazService.reconciliarTransmissoesPresas()).isZero();

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
    verifyNoInteractions(sefazClient);
  }

  @Test
  @DisplayName("cancelar recusado pelo fisco lanca CancelamentoRejeitadoException e volta a AUTORIZADA")
  void cancelarRecusadoPeloFiscoDevolveNotaParaAutorizada() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(2));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
    // cStat fora de {135, 155}: o evento nao foi vinculado a NF-e.
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.REJEITADA, "573", "Duplicidade de evento", null, null));

    // Antes esta recusa reaproveitava NotaFiscalNaoCancelavelException, que diz "o status atual
    // nao permite cancelar" apontando AUTORIZADA — justamente o status que permite. A excecao
    // propria diz a verdade e repassa o motivo do fisco.
    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(CancelamentoRejeitadoException.class)
        .hasMessageContaining("573");

    // A recusa nao cancelou a nota: ela volta a AUTORIZADA e segue cancelavel.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    // Nenhum evento de dominio: o debito do periodo nao muda.
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("reconciliar ignora nota que saiu do estado transitorio entre a leitura e a gravacao")
  void reconciliarIgnoraNotaQueJaSaiuDoEstadoTransitorio() {
    NotaFiscal presa = buildNotaXmlGerado(UUID.randomUUID());
    presa.setStatus(StatusNotaFiscal.ENVIANDO);

    when(notaFiscalRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(presa));

    // Simula a corrida com a tx2 da propria transmissao: ao recarregar, a nota ja foi autorizada.
    NotaFiscal jaAutorizada = buildNotaXmlGerado(presa.getId());
    jaAutorizada.setStatus(StatusNotaFiscal.AUTORIZADA);
    when(notaFiscalRepository.findById(presa.getId())).thenReturn(Optional.of(jaAutorizada));

    assertThat(sefazService.reconciliarTransmissoesPresas()).isZero();

    // Nada e sobrescrito: o desfecho real da transmissao prevalece sobre a reconciliacao.
    assertThat(jaAutorizada.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  private NotaFiscal buildNotaXmlGerado(UUID notaId) {
    return NotaFiscal.builder()
        .id(notaId)
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(StatusNotaFiscal.XML_GERADO)
        .chaveAcesso(CHAVE)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + CHAVE + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .build();
  }
}

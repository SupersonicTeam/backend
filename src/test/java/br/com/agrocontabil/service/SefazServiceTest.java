package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.CancelamentoForaPrazoException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

  /** Instancia real — default HOMOLOGACAO; evita UnnecessaryStubbing nos early returns. */
  private final SefazProperties sefazProperties = new SefazProperties();

  private SefazService sefazService;

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

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
    sefazService = new SefazService(
        notaFiscalRepository, produtorProvider, sefazClient, sefazProperties);
  }

  @Test
  @DisplayName("enviar deve autorizar a nota e persistir protocolo/data quando cStat=100")
  void enviarShouldAuthorizeAndPersistProtocol() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    OffsetDateTime autorizacao = OffsetDateTime.now();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.enviar(eq(nota.getXmlGerado()), eq(CHAVE), eq(SefazAmbiente.HOMOLOGACAO)))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            autorizacao));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazEnvioResponse response = sefazService.enviar(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(response.cStat()).isEqualTo("100");
    assertThat(response.protocolo()).isEqualTo("141260000000017");
    assertThat(response.dataAutorizacao()).isEqualTo(autorizacao);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloAutorizacao()).isEqualTo("141260000000017");
    assertThat(nota.getSefazAmbiente()).isEqualTo(SefazAmbiente.HOMOLOGACAO);
    verify(notaFiscalRepository).save(nota);
  }

  @Test
  @DisplayName("enviar deve marcar REJEITADA e nao preencher protocolo quando o fisco rejeita")
  void enviarShouldPersistRejectionWithoutProtocol() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
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
  }

  @Test
  @DisplayName("enviar deve lancar NotaFiscalNaoEnviavelException quando status nao e XML_GERADO")
  void enviarShouldThrowWhenStatusIsNotXmlGerado() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);
    nota.setStatus(StatusNotaFiscal.RASCUNHO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
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
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("enviar deve propagar SefazComunicacaoException sem persistir quando o transporte falha")
  void enviarShouldPropagateCommunicationFailure() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaXmlGerado(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("timeout simulado"));

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(SefazComunicacaoException.class);

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
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
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.cancelar(eq(CHAVE), eq(MOTIVO_VALIDO), eq("141260000000017"),
        eq(SefazAmbiente.HOMOLOGACAO)))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.AUTORIZADA, "135", "Evento registrado e vinculado a NF-e",
            "141260000000099", OffsetDateTime.now()));
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
    verify(notaFiscalRepository).save(nota);
  }

  @Test
  @DisplayName("cancelar deve lancar NotaFiscalNaoCancelavelException quando status nao e AUTORIZADA")
  void cancelarShouldThrowWhenStatusIsNotAutorizada() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(1));
    nota.setStatus(StatusNotaFiscal.XML_GERADO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
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
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
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
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
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
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(CancelamentoForaPrazoException.class);

    verify(sefazClient, never()).cancelar(any(), any(), any(), any());
    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("cancelar deve propagar SefazComunicacaoException sem persistir quando o transporte falha")
  void cancelarShouldPropagateCommunicationFailure() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaAutorizada(notaId, OffsetDateTime.now().minusDays(2));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("timeout simulado"));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO_VALIDO))
        .isInstanceOf(SefazComunicacaoException.class);

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
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

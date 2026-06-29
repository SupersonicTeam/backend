package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.SefazSubmissionResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.InvoiceNotQueryableException;
import br.com.agrocontabil.exception.InvoiceNotSubmittableException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SefazCommunicationException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import br.com.agrocontabil.sefaz.SefazEnvironment;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazReturn;
import br.com.agrocontabil.sefaz.SefazSituation;
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
 * <p>Mockito puro: {@link InvoiceRepository}, {@link AuthenticatedProducerProvider} e
 * {@link SefazClient} sao mockados. {@link SefazProperties} usa instancia real (default
 * HOMOLOGACAO) para evitar stubs desnecessarios (strict stubs).
 *
 * <p>Cenarios: envio autorizado, rejeicao persistida, status invalido (409), ownership (404)
 * e falha de comunicacao propagada (502).
 */
@ExtendWith(MockitoExtension.class)
class SefazServiceTest {

  @Mock
  private InvoiceRepository invoiceRepository;

  @Mock
  private AuthenticatedProducerProvider producerProvider;

  @Mock
  private SefazClient sefazClient;

  /** Instancia real — default HOMOLOGACAO; evita UnnecessaryStubbing nos early returns. */
  private final SefazProperties sefazProperties = new SefazProperties();

  private SefazService sefazService;

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

  private Producer producer;
  private UUID producerId;

  @BeforeEach
  void setUp() {
    producerId = UUID.randomUUID();
    producer = Producer.builder()
        .id(producerId)
        .fullName("Murilo Produtor")
        .taxId("12345678901")
        .build();
    sefazService = new SefazService(
        invoiceRepository, producerProvider, sefazClient, sefazProperties);
  }

  @Test
  @DisplayName("enviar deve autorizar a nota e persistir protocolo/data quando cStat=100")
  void enviarShouldAuthorizeAndPersistProtocol() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);
    OffsetDateTime autorizacao = OffsetDateTime.now();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(sefazClient.send(eq(invoice.getGeneratedXml()), eq(CHAVE), eq(SefazEnvironment.HOMOLOGACAO)))
        .thenReturn(new SefazReturn(
            SefazSituation.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            autorizacao));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazSubmissionResponse response = sefazService.send(invoiceId);

    assertThat(response.status()).isEqualTo(InvoiceStatus.AUTORIZADA);
    assertThat(response.cStat()).isEqualTo("100");
    assertThat(response.protocol()).isEqualTo("141260000000017");
    assertThat(response.authorizationDate()).isEqualTo(autorizacao);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.AUTORIZADA);
    assertThat(invoice.getAuthorizationProtocol()).isEqualTo("141260000000017");
    assertThat(invoice.getSefazEnvironment()).isEqualTo(SefazEnvironment.HOMOLOGACAO);
    verify(invoiceRepository).save(invoice);
  }

  @Test
  @DisplayName("enviar deve marcar REJEITADA e nao preencher protocolo quando o fisco rejeita")
  void enviarShouldPersistRejectionWithoutProtocol() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(sefazClient.send(any(), any(), any()))
        .thenReturn(new SefazReturn(
            SefazSituation.REJEITADA, "204", "Duplicidade de NF-e", null, null));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazSubmissionResponse response = sefazService.send(invoiceId);

    assertThat(response.status()).isEqualTo(InvoiceStatus.REJEITADA);
    assertThat(response.cStat()).isEqualTo("204");
    assertThat(response.protocol()).isNull();
    assertThat(invoice.getAuthorizationProtocol()).isNull();
    assertThat(invoice.getAuthorizationDate()).isNull();
  }

  @Test
  @DisplayName("enviar deve lancar NotaFiscalNaoEnviavelException quando status nao e XML_GERADO")
  void enviarShouldThrowWhenStatusIsNotXmlGerado() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);
    invoice.setStatus(InvoiceStatus.RASCUNHO);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> sefazService.send(invoiceId))
        .isInstanceOf(InvoiceNotSubmittableException.class)
        .hasMessageContaining("RASCUNHO");

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("enviar deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void enviarShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.send(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("enviar deve propagar SefazComunicacaoException sem persistir quando o transporte falha")
  void enviarShouldPropagateCommunicationFailure() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(sefazClient.send(any(), any(), any()))
        .thenThrow(new SefazCommunicationException("timeout simulado"));

    assertThatThrownBy(() -> sefazService.send(invoiceId))
        .isInstanceOf(SefazCommunicationException.class);

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  // -------------------------------------------------------------------------
  // syncStatus / sincronizarPendentes (issue #16)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("sincronizarStatus deve atualizar nota PENDENTE_SEFAZ para AUTORIZADA")
  void sincronizarStatusShouldUpdatePendingToAuthorized() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);
    invoice.setStatus(InvoiceStatus.PENDENTE_SEFAZ);
    invoice.setSefazEnvironment(SefazEnvironment.HOMOLOGACAO);
    OffsetDateTime autorizacao = OffsetDateTime.now();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));
    when(sefazClient.queryStatus(eq(CHAVE), eq(SefazEnvironment.HOMOLOGACAO)))
        .thenReturn(new SefazReturn(
            SefazSituation.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            autorizacao));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    SefazStatusResponse response = sefazService.syncStatus(invoiceId);

    assertThat(response.status()).isEqualTo(InvoiceStatus.AUTORIZADA);
    assertThat(response.protocol()).isEqualTo("141260000000017");
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.AUTORIZADA);
  }

  @Test
  @DisplayName("sincronizarStatus deve lancar NotaFiscalNaoConsultavelException quando RASCUNHO")
  void sincronizarStatusShouldThrowWhenRascunho() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = buildNotaXmlGerado(invoiceId);
    invoice.setStatus(InvoiceStatus.RASCUNHO);
    invoice.setAccessKey(null);

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.of(invoice));

    assertThatThrownBy(() -> sefazService.syncStatus(invoiceId))
        .isInstanceOf(InvoiceNotQueryableException.class);

    verify(invoiceRepository, never()).save(any(Invoice.class));
  }

  @Test
  @DisplayName("sincronizarStatus deve lancar ResourceNotFoundException quando nao encontrada")
  void sincronizarStatusShouldThrowWhenNotFound() {
    UUID invoiceId = UUID.randomUUID();

    when(producerProvider.get()).thenReturn(producer);
    when(invoiceRepository.findByIdAndProducerId(invoiceId, producerId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sefazService.syncStatus(invoiceId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(invoiceId.toString());
  }

  @Test
  @DisplayName("sincronizarPendentes deve consultar a SEFAZ e contar as notas atualizadas")
  void sincronizarPendentesShouldConsultAndCountUpdated() {
    Invoice invoice = buildNotaXmlGerado(UUID.randomUUID());
    invoice.setStatus(InvoiceStatus.PENDENTE_SEFAZ);

    when(invoiceRepository.findByStatus(InvoiceStatus.PENDENTE_SEFAZ))
        .thenReturn(List.of(invoice));
    when(sefazClient.queryStatus(eq(CHAVE), any()))
        .thenReturn(new SefazReturn(
            SefazSituation.AUTORIZADA, "100", "Autorizado o uso da NF-e", "141260000000017",
            OffsetDateTime.now()));
    when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    int atualizadas = sefazService.sincronizarPendentes();

    assertThat(atualizadas).isEqualTo(1);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.AUTORIZADA);
  }

  private Invoice buildNotaXmlGerado(UUID invoiceId) {
    return Invoice.builder()
        .id(invoiceId)
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Jose Comprador")
        .recipientTaxId("12345678901")
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .status(InvoiceStatus.XML_GERADO)
        .accessKey(CHAVE)
        .generatedXml("<NFe><infNFe Id=\"NFe" + CHAVE + "\"/></NFe>")
        .nfeLayoutVersion("4.00")
        .build();
  }
}

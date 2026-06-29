package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.NfaInvalidIssuerException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.DocumentType;
import br.com.agrocontabil.model.ProducerType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link NfaXmlService}.
 *
 * <p>O pipeline de XML ({@link InvoiceXmlService}) e mockado — aqui validamos
 * apenas as pre-condicoes especificas da NFA (emitente PF com CPF valido) e o
 * roteamento da geracao.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeracaoNfaService — pre-validacao e delegacao da NFA")
class NfaXmlServiceTest {

  @Mock
  private InvoiceXmlService invoiceXmlService;

  @InjectMocks
  private NfaXmlService service;

  private Invoice invoice;

  @BeforeEach
  void setUp() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("JOAO DA SILVA AGRICULTOR")
        .taxId("52998224725") // CPF valido
        .producerType(ProducerType.PESSOA_FISICA)
        .state("PR")
        .build();

    invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .documentType(DocumentType.NFA)
        .producer(producer)
        .build();
  }

  @Test
  @DisplayName("delega ao pipeline NF-e quando emitente e PF com CPF valido")
  void delegaQuandoPfComCpfValido() {
    when(invoiceXmlService.generateXml(invoice)).thenReturn("<xml/>");

    String xml = service.generateXml(invoice);

    assertThat(xml).isEqualTo("<xml/>");
    verify(invoiceXmlService).generateXml(invoice);
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e pessoa juridica")
  void rejeitaProdutorPj() {
    invoice.getProducer().setProducerType(ProducerType.PESSOA_JURIDICA);

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(NfaInvalidIssuerException.class);

    verify(invoiceXmlService, never()).generateXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o CPF do produtor e invalido")
  void rejeitaCpfInvalido() {
    invoice.getProducer().setTaxId("12345678901"); // CPF invalido

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(NfaInvalidIssuerException.class);

    verify(invoiceXmlService, never()).generateXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e nulo")
  void rejeitaProdutorNulo() {
    invoice.setProducer(null);

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(NfaInvalidIssuerException.class);

    verify(invoiceXmlService, never()).generateXml(any());
  }
}

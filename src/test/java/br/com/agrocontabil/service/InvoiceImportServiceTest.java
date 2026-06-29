package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ImportBatchResponse;
import br.com.agrocontabil.exception.XmlImportException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.InvoiceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImportacaoNotaFiscalService — lote com relatorio por arquivo")
class InvoiceImportServiceTest {

  @Mock private NfeXmlParser parser;
  @Mock private NfeImportMapper mapper;
  @Mock private InvoiceRepository repository;
  @Mock private AuthenticatedProducerProvider producerProvider;

  @InjectMocks private InvoiceImportService service;

  private Producer producer;

  @BeforeEach
  void setUp() {
    producer = Producer.builder().id(UUID.randomUUID()).fullName("Dono").build();
  }

  private MultipartFile fileName(String name) {
    return new MockMultipartFile(name, name, "application/xml", "<NFe/>".getBytes());
  }

  @Test
  @DisplayName("lote com um sucesso e um erro produz relatorio correto")
  void loteSucessoEErro() {
    when(producerProvider.get()).thenReturn(producer);

    NFe nfeOk = new NFe();
    when(parser.parse(any())).thenReturn(nfeOk).thenThrow(
        new XmlImportException("XML invalido"));

    Invoice notaMapeada = Invoice.builder()
        .id(UUID.randomUUID()).accessKey("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByAccessKey(any())).thenReturn(false);
    when(repository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportBatchResponse resp = service.importInvoices(List.of(fileName("ok.xml"), fileName("ruim.xml")));

    assertThat(resp.total()).isEqualTo(2);
    assertThat(resp.imported()).isEqualTo(1);
    assertThat(resp.failures()).isEqualTo(1);
    assertThat(resp.results()).anyMatch(r -> r.success() && "ok.xml".equals(r.fileName()));
    assertThat(resp.results()).anyMatch(r -> !r.success() && "ruim.xml".equals(r.fileName()));
  }

  @Test
  @DisplayName("chave ja importada vira erro no relatorio, sem salvar")
  void chaveDuplicada() {
    when(producerProvider.get()).thenReturn(producer);
    when(parser.parse(any())).thenReturn(new NFe());
    Invoice notaMapeada = Invoice.builder()
        .id(UUID.randomUUID()).accessKey("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByAccessKey(any())).thenReturn(true);

    ImportBatchResponse resp = service.importInvoices(List.of(fileName("dup.xml")));

    assertThat(resp.imported()).isZero();
    assertThat(resp.failures()).isEqualTo(1);
    assertThat(resp.results().get(0).error()).containsIgnoringCase("importada");
  }
}

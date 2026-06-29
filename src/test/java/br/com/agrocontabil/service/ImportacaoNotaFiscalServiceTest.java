package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
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
class ImportacaoNotaFiscalServiceTest {

  @Mock private NfeXmlParser parser;
  @Mock private NfeImportMapper mapper;
  @Mock private NotaFiscalRepository repository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks private ImportacaoNotaFiscalService service;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Dono").build();
  }

  private MultipartFile arquivo(String nome) {
    return new MockMultipartFile(nome, nome, "application/xml", "<NFe/>".getBytes());
  }

  @Test
  @DisplayName("lote com um sucesso e um erro produz relatorio correto")
  void loteSucessoEErro() {
    when(produtorProvider.get()).thenReturn(produtor);

    NFe nfeOk = new NFe();
    when(parser.parse(any())).thenReturn(nfeOk).thenThrow(
        new XmlImportacaoException("XML invalido"));

    NotaFiscal notaMapeada = NotaFiscal.builder()
        .id(UUID.randomUUID()).chaveAcesso("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByChaveAcesso(any())).thenReturn(false);
    when(repository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportacaoLoteResponse resp = service.importar(List.of(arquivo("ok.xml"), arquivo("ruim.xml")));

    assertThat(resp.total()).isEqualTo(2);
    assertThat(resp.importados()).isEqualTo(1);
    assertThat(resp.falhas()).isEqualTo(1);
    assertThat(resp.resultados()).anyMatch(r -> r.sucesso() && "ok.xml".equals(r.arquivo()));
    assertThat(resp.resultados()).anyMatch(r -> !r.sucesso() && "ruim.xml".equals(r.arquivo()));
  }

  @Test
  @DisplayName("chave ja importada vira erro no relatorio, sem salvar")
  void chaveDuplicada() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(parser.parse(any())).thenReturn(new NFe());
    NotaFiscal notaMapeada = NotaFiscal.builder()
        .id(UUID.randomUUID()).chaveAcesso("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByChaveAcesso(any())).thenReturn(true);

    ImportacaoLoteResponse resp = service.importar(List.of(arquivo("dup.xml")));

    assertThat(resp.importados()).isZero();
    assertThat(resp.falhas()).isEqualTo(1);
    assertThat(resp.resultados().get(0).erro()).containsIgnoringCase("importada");
  }
}

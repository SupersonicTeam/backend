package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.model.TipoProdutor;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link GeracaoNfaService}.
 *
 * <p>O pipeline de XML ({@link GeracaoNotaFiscalService}) e mockado — aqui validamos
 * apenas as pre-condicoes especificas da NFA (emitente PF com CPF valido) e o
 * roteamento da geracao.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeracaoNfaService — pre-validacao e delegacao da NFA")
class GeracaoNfaServiceTest {

  @Mock
  private GeracaoNotaFiscalService geracaoNotaFiscalService;

  @InjectMocks
  private GeracaoNfaService service;

  private NotaFiscal nota;

  @BeforeEach
  void setUp() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725") // CPF valido
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .estado("PR")
        .build();

    nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .tipoDocumento(TipoDocumento.NFA)
        .produtor(produtor)
        .build();
  }

  @Test
  @DisplayName("delega ao pipeline NF-e quando emitente e PF com CPF valido")
  void delegaQuandoPfComCpfValido() {
    when(geracaoNotaFiscalService.gerarXml(nota)).thenReturn("<xml/>");

    String xml = service.gerarXml(nota);

    assertThat(xml).isEqualTo("<xml/>");
    verify(geracaoNotaFiscalService).gerarXml(nota);
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e pessoa juridica")
  void rejeitaProdutorPj() {
    nota.getProdutor().setTipoProdutor(TipoProdutor.PESSOA_JURIDICA);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o CPF do produtor e invalido")
  void rejeitaCpfInvalido() {
    nota.getProdutor().setCpfCnpj("12345678901"); // CPF invalido

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e nulo")
  void rejeitaProdutorNulo() {
    nota.setProdutor(null);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }
}

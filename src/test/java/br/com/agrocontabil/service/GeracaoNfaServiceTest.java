package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link GeracaoNfaService} (issue #18).
 *
 * <p>Valida as pre-condicoes especificas da NFA (produtor pessoa fisica + CPF de emitente com
 * digito verificador valido) e a delegacao ao pipeline de emissao ja existente.
 */
@ExtendWith(MockitoExtension.class)
class GeracaoNfaServiceTest {

  @Mock
  private GeracaoNotaFiscalService geracaoNotaFiscalService;

  @InjectMocks
  private GeracaoNfaService geracaoNfaService;

  private NotaFiscal notaComProdutor(TipoProdutor tipo, String cpfCnpj) {
    Produtor produtor = Produtor.builder()
        .nomeCompleto("Joao Produtor")
        .tipoProdutor(tipo)
        .cpfCnpj(cpfCnpj)
        .estado("MT")
        .build();
    return NotaFiscal.builder()
        .numero(1L)
        .tipoDocumento(TipoDocumentoFiscal.NFA)
        .produtor(produtor)
        .build();
  }

  @Test
  @DisplayName("Produtor PF com CPF valido delega ao pipeline e retorna o XML gerado")
  void produtorPfComCpfValidoDelegaAoPipeline() {
    NotaFiscal nota = notaComProdutor(TipoProdutor.PESSOA_FISICA, "52998224725");
    when(geracaoNotaFiscalService.gerarXml(nota)).thenReturn("<xml/>");

    String xml = geracaoNfaService.gerarXml(nota);

    assertThat(xml).isEqualTo("<xml/>");
    verify(geracaoNotaFiscalService).gerarXml(nota);
  }

  @Test
  @DisplayName("Produtor PJ na NFA deve lancar 422 e nao chamar o pipeline")
  void produtorPjNaNfaDeveFalhar() {
    NotaFiscal nota = notaComProdutor(TipoProdutor.PESSOA_JURIDICA, "11222333000181");

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class)
        .satisfies(ex -> assertThat(((NfaInvalidaParaEmissaoException) ex).getErrors())
            .anyMatch(e -> e.code().equals(ErrorCode.NFA_PRODUTOR_NAO_PF.code())));

    verify(geracaoNotaFiscalService, never()).gerarXml(nota);
  }

  @Test
  @DisplayName("Produtor PF com CPF de digito verificador invalido deve lancar 422")
  void produtorPfComCpfInvalidoDeveFalhar() {
    NotaFiscal nota = notaComProdutor(TipoProdutor.PESSOA_FISICA, "12345678901");

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class)
        .satisfies(ex -> assertThat(((NfaInvalidaParaEmissaoException) ex).getErrors())
            .anyMatch(e -> e.code().equals(ErrorCode.DOCUMENTO_INVALIDO.code())));

    verify(geracaoNotaFiscalService, never()).gerarXml(nota);
  }

  @Test
  @DisplayName("Produtor PF com CNPJ (14 digitos) na NFA deve falhar: NFA exige CPF")
  void produtorPfComCnpjNaNfaDeveFalhar() {
    // Documento com DV valido, mas de 14 digitos (CNPJ) — nao e CPF de PF.
    NotaFiscal nota = notaComProdutor(TipoProdutor.PESSOA_FISICA, "11222333000181");

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class)
        .satisfies(ex -> assertThat(((NfaInvalidaParaEmissaoException) ex).getErrors())
            .anyMatch(e -> e.code().equals(ErrorCode.DOCUMENTO_INVALIDO.code())));

    verify(geracaoNotaFiscalService, never()).gerarXml(nota);
  }

  @Test
  @DisplayName("Produtor PF com CPF nulo deve lancar 422")
  void produtorPfComCpfNuloDeveFalhar() {
    NotaFiscal nota = notaComProdutor(TipoProdutor.PESSOA_FISICA, null);

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(nota);
  }
}

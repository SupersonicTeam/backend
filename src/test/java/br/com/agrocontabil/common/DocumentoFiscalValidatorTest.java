package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link DocumentoFiscalValidator} (issue #18).
 *
 * <p>Valida o digito verificador (modulo 11) de CPF (11 digitos) e CNPJ (14 digitos),
 * escolhendo o algoritmo pelo comprimento do documento. Os valores de teste sao numeros
 * sinteticos com DV valido/invalido (nao correspondem a pessoas reais).
 */
class DocumentoFiscalValidatorTest {

  @Nested
  @DisplayName("CPF (11 digitos)")
  class Cpf {

    @Test
    @DisplayName("CPF com digito verificador valido deve ser aceito")
    void cpfValidoDeveSerAceito() {
      assertThat(DocumentoFiscalValidator.isCpfValido("52998224725")).isTrue();
      assertThat(DocumentoFiscalValidator.isCpfValido("11144477735")).isTrue();
    }

    @Test
    @DisplayName("CPF com digito verificador invalido deve ser rejeitado")
    void cpfComDvInvalidoDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCpfValido("12345678901")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfValido("52998224724")).isFalse();
    }

    @Test
    @DisplayName("CPF com todos os digitos iguais deve ser rejeitado")
    void cpfComDigitosRepetidosDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCpfValido("00000000000")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfValido("11111111111")).isFalse();
    }

    @Test
    @DisplayName("CPF com comprimento ou formato invalido deve ser rejeitado")
    void cpfComFormatoInvalidoDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCpfValido("5299822472")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfValido("529982247250")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfValido("5299822472a")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfValido(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("CNPJ (14 digitos)")
  class Cnpj {

    @Test
    @DisplayName("CNPJ com digito verificador valido deve ser aceito")
    void cnpjValidoDeveSerAceito() {
      assertThat(DocumentoFiscalValidator.isCnpjValido("11222333000181")).isTrue();
      assertThat(DocumentoFiscalValidator.isCnpjValido("11444777000161")).isTrue();
    }

    @Test
    @DisplayName("CNPJ com digito verificador invalido deve ser rejeitado")
    void cnpjComDvInvalidoDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCnpjValido("11222333000182")).isFalse();
      assertThat(DocumentoFiscalValidator.isCnpjValido("12345678000199")).isFalse();
    }

    @Test
    @DisplayName("CNPJ com todos os digitos iguais deve ser rejeitado")
    void cnpjComDigitosRepetidosDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCnpjValido("00000000000000")).isFalse();
    }

    @Test
    @DisplayName("CNPJ com comprimento ou formato invalido deve ser rejeitado")
    void cnpjComFormatoInvalidoDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCnpjValido("1122233300018")).isFalse();
      assertThat(DocumentoFiscalValidator.isCnpjValido("112223330001811")).isFalse();
      assertThat(DocumentoFiscalValidator.isCnpjValido(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("isCpfCnpjValido (dispatch por comprimento)")
  class CpfCnpjDispatch {

    @Test
    @DisplayName("Escolhe CPF para 11 digitos e CNPJ para 14 digitos")
    void escolheAlgoritmoPeloComprimento() {
      assertThat(DocumentoFiscalValidator.isCpfCnpjValido("52998224725")).isTrue();
      assertThat(DocumentoFiscalValidator.isCpfCnpjValido("11222333000181")).isTrue();
    }

    @Test
    @DisplayName("Comprimento diferente de 11 ou 14 deve ser rejeitado")
    void comprimentoInesperadoDeveSerRejeitado() {
      assertThat(DocumentoFiscalValidator.isCpfCnpjValido("123")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfCnpjValido("123456789012")).isFalse();
      assertThat(DocumentoFiscalValidator.isCpfCnpjValido(null)).isFalse();
    }
  }
}

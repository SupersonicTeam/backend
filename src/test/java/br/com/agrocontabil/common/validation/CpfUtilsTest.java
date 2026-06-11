package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CpfUtils — validacao de CPF por modulo 11")
class CpfUtilsTest {

  @Test
  @DisplayName("aceita CPF valido com 11 digitos")
  void aceitaCpfValido() {
    assertThat(CpfUtils.isValid("52998224725")).isTrue();
    assertThat(CpfUtils.isValid("11144477735")).isTrue();
  }

  @Test
  @DisplayName("aceita CPF valido mesmo com formatacao (pontos e traco)")
  void aceitaCpfFormatado() {
    assertThat(CpfUtils.isValid("529.982.247-25")).isTrue();
  }

  @Test
  @DisplayName("rejeita CPF com digito verificador errado")
  void rejeitaDigitoVerificadorErrado() {
    assertThat(CpfUtils.isValid("12345678901")).isFalse();
    assertThat(CpfUtils.isValid("52998224724")).isFalse();
  }

  @Test
  @DisplayName("rejeita CPF com todos os digitos iguais")
  void rejeitaDigitosRepetidos() {
    assertThat(CpfUtils.isValid("11111111111")).isFalse();
    assertThat(CpfUtils.isValid("00000000000")).isFalse();
  }

  @Test
  @DisplayName("rejeita CPF com numero de digitos diferente de 11")
  void rejeitaTamanhoInvalido() {
    assertThat(CpfUtils.isValid("123")).isFalse();
    assertThat(CpfUtils.isValid("123456789012")).isFalse();
  }

  @Test
  @DisplayName("rejeita null e string em branco")
  void rejeitaNullEBranco() {
    assertThat(CpfUtils.isValid(null)).isFalse();
    assertThat(CpfUtils.isValid("   ")).isFalse();
  }
}

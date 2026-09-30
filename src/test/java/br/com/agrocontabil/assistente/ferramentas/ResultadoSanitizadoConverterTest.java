package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O resultado de uma função sai serializado e mascarado (issue #144). */
class ResultadoSanitizadoConverterTest {

  /** DTO hipotético de uma função futura que esqueceu a minimização. */
  record ResultadoComVazamento(String insumo, String valorTotal) {}

  @Test
  @DisplayName("Documento que escapar da minimizacao sai mascarado; valores ficam intactos")
  void mascaraOQueEscaparDaMinimizacao() {
    String json = new ResultadoSanitizadoConverter().convert(
        new ResultadoComVazamento("adubo do fornecedor 12.345.678/0001-95", "R$ 1.234,56"),
        ResultadoComVazamento.class);

    assertThat(json).contains("[CNPJ]", "R$ 1.234,56").doesNotContain("12.345.678/0001-95");
  }
}

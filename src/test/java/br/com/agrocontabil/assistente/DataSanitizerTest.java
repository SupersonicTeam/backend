package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes do {@link DataSanitizer} (issue #144). */
class DataSanitizerTest {

  @Test
  @DisplayName("CPF e CNPJ, formatados ou nao, viram marcadores")
  void mascaraDocumentos() {
    assertThat(DataSanitizer.mascarar("CPF 123.456.789-09 e 12345678909"))
        .isEqualTo("CPF [CPF] e [CPF]");
    assertThat(DataSanitizer.mascarar("CNPJ 12.345.678/0001-95 ou 12345678000195"))
        .isEqualTo("CNPJ [CNPJ] ou [CNPJ]");
  }

  @Test
  @DisplayName("Chave de acesso, e-mail, telefone e CEP viram marcadores")
  void mascaraContatosEChave() {
    String chave = "3".repeat(44);
    assertThat(DataSanitizer.mascarar("chave " + chave)).isEqualTo("chave [CHAVE]");
    assertThat(DataSanitizer.mascarar("fale com joao.silva@fazenda.com.br"))
        .isEqualTo("fale com [EMAIL]");
    assertThat(DataSanitizer.mascarar("tel (65) 99876-5432 ou +55 65 998765432"))
        .isEqualTo("tel [TELEFONE] ou [TELEFONE]");
    assertThat(DataSanitizer.mascarar("CEP 78000-000")).isEqualTo("CEP [CEP]");
  }

  @Test
  @DisplayName("Valores, datas, quantidades e NCM ficam intactos (o modelo precisa deles)")
  void naoToreaValoresFiscais() {
    String texto = "R$ 123.456.789,01 em 31/07/2026 (2026-07-31), 1.500 KG, NCM 31052000, "
        + "saldo R$ 1.080,00, mes 2026-07";

    assertThat(DataSanitizer.mascarar(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("Nulo e vazio passam")
  void nuloEVazio() {
    assertThat(DataSanitizer.mascarar(null)).isNull();
    assertThat(DataSanitizer.mascarar("")).isEmpty();
  }
}

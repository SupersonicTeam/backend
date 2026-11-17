package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.DocumentoFiscalValidator;
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
  @DisplayName("CNPJ alfanumerico valido, com e sem pontuacao, vira [CNPJ] (#313)")
  void mascaraCnpjAlfanumerico() {
    assertThat(DataSanitizer.mascarar(
        "fornecedor 12.ABC.345/01DE-35 raw=12ABC34501DE35 (12ABC34501DE35)."))
        .isEqualTo("fornecedor [CNPJ] raw=[CNPJ] ([CNPJ]).");
  }

  @Test
  @DisplayName("CNPJ alfanumerico em minusculas, com o DV valido, tambem e mascarado (#313)")
  void mascaraCnpjAlfanumericoEmMinusculas() {
    // Diferente do log (so maiusculas, por causa do hash em hex): a pergunta e texto do usuario.
    assertThat(DataSanitizer.mascarar("cnpj 12abc34501de35 e 12.aBc.345/01dE-35"))
        .isEqualTo("cnpj [CNPJ] e [CNPJ]");
  }

  @Test
  @DisplayName("Token de 14 caracteres com DV invalido ou colado em letra fica intacto (#313)")
  void cnpjAlfanumericoSemDvValidoFicaIntacto() {
    // DV trocado, hash em hex terminado em 2 digitos, minusculas com DV errado e o documento colado
    // em letra.
    String texto = "a=12ABC34501DE36 b=ABCDEF01234567 c=12abc34501de36 d=x12ABC34501DE35"
        + " e=fffeee00112233";
    assertThat(DataSanitizer.mascarar(texto)).isEqualTo(texto);
  }

  @Test
  @DisplayName("Propriedade: token de 14 posicoes vira [CNPJ] se, e so se, o DV confere, em qualquer"
      + " caixa (#313)")
  void soComDvValido() throws Exception {
    java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
    String alfabeto = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    int validos = 0;
    // Sequencia fixa (derivada do SHA-256 do indice): uma falha se repete localmente.
    for (int i = 0; i < 5_000; i++) {
      byte[] h = sha.digest(("cnpj-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder token = new StringBuilder();
      for (int p = 0; p < 12; p++) {
        token.append(alfabeto.charAt(Math.floorMod(h[p], alfabeto.length())));
      }
      token.append(Math.floorMod(h[12], 10)).append(Math.floorMod(h[13], 10));
      if (token.chars().allMatch(Character::isDigit)) {
        continue; // so de digitos e a regra numerica, que mascara sem conferir o DV
      }
      boolean valido = DocumentoFiscalValidator.isCnpjAlfanumericoValido(token.toString());
      validos += valido ? 1 : 0;
      for (String forma : new String[] {token.toString(), token.toString().toLowerCase()}) {
        String linha = "doc " + forma + " fim";
        assertThat(DataSanitizer.mascarar(linha)).as(forma)
            .isEqualTo(valido ? "doc [CNPJ] fim" : linha);
      }
    }
    // Cerca de 1 em 100 tem DV valido: o teste passa pelos dois lados.
    assertThat(validos).isBetween(10, 200);
  }

  @Test
  @DisplayName("Limite aceito: o CNPJ alfanumerico colado em letra nao e mascarado (#313)")
  void cnpjAlfanumericoColadoEmLetraPassa() {
    assertThat(DataSanitizer.mascarar("cnpj12ABC34501DE35")).isEqualTo("cnpj12ABC34501DE35");
    // com qualquer separador, mascara
    assertThat(DataSanitizer.mascarar("cnpj:12ABC34501DE35 cnpj_12ABC34501DE35"))
        .isEqualTo("cnpj:[CNPJ] cnpj_[CNPJ]");
  }

  @Test
  @DisplayName("CNPJ so de digitos segue mascarado, com ou sem DV valido (#313)")
  void cnpjNumericoSegueMascarado() {
    assertThat(DataSanitizer.mascarar("11222333000181 e 12345678000199"))
        .isEqualTo("[CNPJ] e [CNPJ]");
  }

  @Test
  @DisplayName("O CNPJ alfanumerico no meio de uma pergunta sai da frase, sem mexer no resto (#313)")
  void cnpjAlfanumericoNoMeioDaPergunta() {
    assertThat(DataSanitizer.mascarar("qual o saldo do 12.ABC.345/01DE-35?"))
        .isEqualTo("qual o saldo do [CNPJ]?");
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

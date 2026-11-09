package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.exception.SenhaFracaException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PoliticaSenha — regras da senha de cadastro (#205)")
class PoliticaSenhaTest {

  private static final String EMAIL = "joaquim.silva@fazenda.com.br";
  private static final String CPF = "52998224725";

  private final PoliticaSenha politica = new PoliticaSenha();

  private List<ErrorDetail> violacoes(String senha) {
    try {
      politica.validar(PoliticaSenha.normalizar(senha), EMAIL, CPF, "senha");
      return List.of();
    } catch (SenhaFracaException e) {
      return e.getErrors();
    }
  }

  @Test
  @DisplayName("frase longa sem dado do titular passa")
  void fraseLongaPassa() {
    assertThatCode(() -> politica.validar("cavalo bateria grampo correto", EMAIL, CPF, "senha"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("10 caracteres passam e 9 nao (contagem por code point, com acento)")
  void tamanhoMinimoPorCodePoint() {
    assertThat(violacoes("ção-lavoura")).isEmpty();
    assertThat(violacoes("çãolavour")).singleElement()
        .satisfies(e -> assertThat(e.message()).contains("pelo menos 10"));
  }

  @Test
  @DisplayName("acima de 72 bytes UTF-8 e recusada, mesmo com menos de 72 caracteres")
  void tetoEmBytesDoBcrypt() {
    // 40 letras acentuadas = 80 bytes: o BCrypt ignoraria os ultimos 8 em silencio.
    String acentuada = "é".repeat(40);
    assertThat(acentuada.length()).isLessThan(72);
    assertThat(violacoes(acentuada)).anySatisfy(e -> assertThat(e.message()).contains("72 bytes"));
    assertThat(violacoes("a1b2c3d4e5".repeat(7) + "xy")).isEmpty(); // 72 bytes exatos
  }

  @Test
  @DisplayName("caractere unico repetido e recusado")
  void caractereRepetido() {
    assertThat(violacoes("zzzzzzzzzzzz"))
        .anySatisfy(e -> assertThat(e.message()).contains("repetido"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"joaquim.silva2026!", "minha52998224725", "x" + EMAIL})
  @DisplayName("senha com o e-mail, a parte local ou o CPF e recusada")
  void dadoDoTitular(String senha) {
    assertThat(violacoes(senha))
        .anySatisfy(e -> assertThat(e.message()).contains("e-mail nem seu CPF/CNPJ"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1234567890", "Senha123456", "QWERTYUIOP", "AgroContabil123"})
  @DisplayName("senha da lista de comuns e recusada sem diferenciar maiusculas")
  void senhaComum(String senha) {
    assertThat(violacoes(senha))
        .anySatisfy(e -> assertThat(e.message()).contains("mais usadas"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "12345678901", "01234567890", "98765432109", "abcdefghijk", "ZYXWVUTSRQP", "qwertyuiopas",
      "poiuytrewq12", "asdfghjklç", "zxcvbnm,.-x", "1q2w3e4r5t6y", "asdfasdfasdf", "Fazenda123456",
      "654321Fazenda", "meusucesso-abcdef"})
  @DisplayName("sequencia, fileira de teclado ou trecho repetido e recusado (#252)")
  void sequenciasEPadroes(String senha) {
    assertThat(violacoes(senha))
        .anySatisfy(e -> {
          assertThat(e.message()).contains("sequencias");
          assertThat(e.rejectedValue()).isNull();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "cavalo bateria grampo correto", "Fazenda-Sao-Jose-2026", "Banca-AgroConIA-demonstracao-2026",
      "abcde-fghij-klmno", "12345 e 54321 e 1357924680", "colheita2026safra2027", "xaxbxcxdxexf9"})
  @DisplayName("sequencias curtas (ate 5) e senhas sem padrao passam (#252)")
  void casosLimiteQuePassam(String senha) {
    assertThat(violacoes(senha)).isEmpty();
  }

  @Test
  @DisplayName("lista ampliada com SecLists: senhas vazadas de 10+ caracteres sao recusadas (#252)")
  void listaAmpliada() {
    assertThat(violacoes("trustno1234")).isNotEmpty();
    assertThat(violacoes("passw0rd123")).isNotEmpty();
  }

  @Test
  @DisplayName("todas as violacoes vem juntas, com codigo SENHA_FRACA e sem ecoar a senha")
  void violacoesSemEcoarSenha() {
    // 9 caracteres e um unico caractere repetido: duas regras de uma vez.
    assertThatThrownBy(() -> politica.validar("zzzzzzzzz", EMAIL, CPF, "novaSenha"))
        .isInstanceOfSatisfying(SenhaFracaException.class, e -> {
          assertThat(e.getErrors()).hasSize(2);
          assertThat(e.getErrors()).allSatisfy(erro -> {
            assertThat(erro.code()).isEqualTo("SENHA_FRACA");
            assertThat(erro.field()).isEqualTo("novaSenha");
            assertThat(erro.rejectedValue()).isNull();
            assertThat(erro.message()).doesNotContain("zzzzzzzzz");
          });
        });
  }

  @Test
  @DisplayName("senha nula ou vazia e recusada")
  void senhaVazia() {
    assertThat(violacoes("")).singleElement()
        .satisfies(e -> assertThat(e.message()).isEqualTo("Informe a senha."));
    assertThatThrownBy(() -> politica.validar(null, EMAIL, CPF, "senha"))
        .isInstanceOf(SenhaFracaException.class);
  }

  @Test
  @DisplayName("NFKC: e composto e e + acento combinante viram a mesma senha")
  void normalizacaoNfkc() {
    String composto = "café com leite quente";
    String decomposto = "café com leite quente";
    assertThat(decomposto).isNotEqualTo(composto);
    assertThat(PoliticaSenha.normalizar(decomposto)).isEqualTo(PoliticaSenha.normalizar(composto));
    assertThat(PoliticaSenha.normalizar(null)).isNull();
  }
}

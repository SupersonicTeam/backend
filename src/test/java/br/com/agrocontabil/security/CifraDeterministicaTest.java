package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CriptografiaProperties;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CifraDeterministica — AES-256-GCM com IV sintetico (#147)")
class CifraDeterministicaTest {

  private static final String CHAVE = Base64.getEncoder()
      .encodeToString("chave-de-teste-com-32-bytes-ok!!".getBytes());

  private final CifraDeterministica cifra =
      CifraDeterministica.de(new CriptografiaProperties(CHAVE, false, true));

  @Test
  @DisplayName("ida e volta; o valor cifrado nao contem o claro")
  void idaEVolta() {
    String cifrado = cifra.cifrar("52998224725");

    assertThat(cifrado).startsWith("v1:").doesNotContain("52998224725");
    assertThat(cifra.decifrar(cifrado)).isEqualTo("52998224725");
    assertThat(cifra.decifrar(cifra.cifrar("joão@fazenda.com.br"))).isEqualTo("joão@fazenda.com.br");
  }

  @Test
  @DisplayName("deterministica: mesmo valor, mesma cifra; valores diferentes, IVs diferentes")
  void deterministica() {
    assertThat(cifra.cifrar("a@b.com")).isEqualTo(cifra.cifrar("a@b.com"));
    String um = cifra.cifrar("52998224725");
    String outro = cifra.cifrar("11144477735");
    assertThat(um.substring(0, 19)).isNotEqualTo(outro.substring(0, 19));
  }

  @Test
  @DisplayName("nulo passa; valor sem prefixo e legado em claro e volta como esta")
  void nuloELegado() {
    assertThat(cifra.cifrar(null)).isNull();
    assertThat(cifra.decifrar(null)).isNull();
    assertThat(cifra.decifrar("52998224725")).isEqualTo("52998224725");
  }

  @Test
  @DisplayName("adulterado ou com outra chave: lanca, sem o valor na mensagem")
  void adulteradoOuOutraChave() {
    String cifrado = cifra.cifrar("52998224725");
    char ultimo = cifrado.charAt(cifrado.length() - 1);
    String adulterado = cifrado.substring(0, cifrado.length() - 1) + (ultimo == 'A' ? 'B' : 'A');
    CifraDeterministica outra = CifraDeterministica.de(new CriptografiaProperties(
        Base64.getEncoder().encodeToString("outra-chave-de-teste-32-bytes!!!".getBytes()),
        false, true));

    assertThatThrownBy(() -> cifra.decifrar(adulterado))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("52998224725");
    assertThatThrownBy(() -> outra.decifrar(cifrado)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cifra.decifrar("v1:!!!")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("chave ausente, curta, invalida ou de desenvolvimento fora de dev: nao sobe")
  void chaveRecusada() {
    assertThatThrownBy(() -> CifraDeterministica.de(new CriptografiaProperties(null, false, true)))
        .hasMessageContaining("CRIPTO_CHAVE ausente");
    assertThatThrownBy(() -> CifraDeterministica.de(new CriptografiaProperties(
        CifraDeterministica.CHAVE_DE_DESENVOLVIMENTO, false, true)))
        .hasMessageContaining("desenvolvimento");
    // A de desenvolvimento sem o "=" final decodifica nos mesmos bytes: tambem recusada.
    assertThatThrownBy(() -> CifraDeterministica.de(new CriptografiaProperties(
        CifraDeterministica.CHAVE_DE_DESENVOLVIMENTO.replace("=", ""), false, true)))
        .hasMessageContaining("desenvolvimento");
    assertThatThrownBy(() -> CifraDeterministica.de(new CriptografiaProperties(
        Base64.getEncoder().encodeToString(new byte[16]), false, true)))
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> CifraDeterministica.de(new CriptografiaProperties(
        "nao e base64 %%%", false, true)))
        .hasMessageContaining("Base64");
  }

  @Test
  @DisplayName("em dev/test, sem chave, usa a de desenvolvimento (a mesma entre reinicios)")
  void chaveDeDesenvolvimento() {
    CifraDeterministica dev1 = CifraDeterministica.de(new CriptografiaProperties(null, true, true));
    CifraDeterministica dev2 = CifraDeterministica.de(new CriptografiaProperties(null, true, true));

    assertThat(dev2.decifrar(dev1.cifrar("52998224725"))).isEqualTo("52998224725");
  }

  @Test
  @DisplayName("o toString das properties nao mostra a chave")
  void toStringSemChave() {
    assertThat(new CriptografiaProperties(CHAVE, false, true).toString()).doesNotContain(CHAVE);
  }
}

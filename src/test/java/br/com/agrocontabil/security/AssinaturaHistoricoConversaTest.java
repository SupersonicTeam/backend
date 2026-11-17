package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.CriptografiaProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Assinatura das trocas da continuidade (#499): formato, MAC e recusas silenciosas. */
class AssinaturaHistoricoConversaTest {

  /** Chave de teste (32 bytes em Base64), só deste teste. */
  private static final String CHAVE = Base64.getEncoder().encodeToString(
      "chave-de-teste-assinatura-ceres!".getBytes(StandardCharsets.US_ASCII));
  private static final String OUTRA_CHAVE = Base64.getEncoder().encodeToString(
      "outra-chave-de-teste-da-ceres-12".getBytes(StandardCharsets.US_ASCII));

  private final AssinaturaHistoricoConversa assinatura =
      new AssinaturaHistoricoConversa(new CriptografiaProperties(CHAVE, false, true));
  private final UUID produtor = UUID.randomUUID();
  private final byte[] carga = "{\"exp\":1,\"rota\":\"DADOS\",\"reescrita\":false}"
      .getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("Ida e volta: a carga volta igual para o mesmo produtor, pergunta e resposta")
  void idaEVolta() {
    String s = assinatura.assinar(produtor, carga, "quanto vendi?", "Vendeu R$ 10,00.").orElseThrow();

    assertThat(s).startsWith("v1.").matches("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}");
    assertThat(assinatura.conferir(produtor, s, "quanto vendi?", "Vendeu R$ 10,00."))
        .hasValueSatisfying(c -> assertThat(c).isEqualTo(carga));
  }

  @Test
  @DisplayName("Outro produtor, pergunta ou resposta mudada: nada confere")
  void outroProdutorOuTexto() {
    String s = assinatura.assinar(produtor, carga, "p", "r").orElseThrow();

    assertThat(assinatura.conferir(UUID.randomUUID(), s, "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, s, "p ", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, s, "p", "r.")).isEmpty();
  }

  @Test
  @DisplayName("Um caractere mudado em cada parte da assinatura: nada confere")
  void byteMudado() {
    String s = assinatura.assinar(produtor, carga, "p", "r").orElseThrow();
    String[] partes = s.split("\\.");
    String cargaMudada = partes[0] + "." + trocar(partes[1]) + "." + partes[2];
    String macMudado = partes[0] + "." + partes[1] + "." + trocar(partes[2]);
    String versaoMudada = "v2." + partes[1] + "." + partes[2];

    assertThat(assinatura.conferir(produtor, cargaMudada, "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, macMudado, "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, versaoMudada, "p", "r")).isEmpty();
  }

  @Test
  @DisplayName("Base64 com preenchimento, ponto a mais, não ASCII ou longa demais: recusada")
  void formato() {
    String s = assinatura.assinar(produtor, carga, "p", "r").orElseThrow();

    assertThat(assinatura.conferir(produtor, s + "=", "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, s + ".x", "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, s.replace('v', 'ν'), "p", "r")).isEmpty();
    assertThat(assinatura.conferir(produtor, "v1." + "a".repeat(2100) + ".b", "p", "r"))
        .isEmpty();
    assertThat(assinatura.conferir(produtor, null, "p", "r")).isEmpty();
  }

  @Test
  @DisplayName("Surrogate solto no texto: não assina nem confere")
  void surrogateSolto() {
    String solto = "nota \uD83D fim";

    assertThat(assinatura.assinar(produtor, carga, solto, "r")).isEmpty();
    String s = assinatura.assinar(produtor, carga, "p", "r").orElseThrow();
    assertThat(assinatura.conferir(produtor, s, "p", solto)).isEmpty();
  }

  @Test
  @DisplayName("Outra CRIPTO_CHAVE invalida os pares vivos")
  void outraChave() {
    String s = assinatura.assinar(produtor, carga, "p", "r").orElseThrow();
    AssinaturaHistoricoConversa outra =
        new AssinaturaHistoricoConversa(new CriptografiaProperties(OUTRA_CHAVE, false, true));

    assertThat(outra.conferir(produtor, s, "p", "r")).isEmpty();
  }

  @Test
  @DisplayName("Carga vazia ou acima de 1500 bytes não é assinada")
  void cargaForaDoTeto() {
    assertThat(assinatura.assinar(produtor, new byte[0], "p", "r")).isEmpty();
    assertThat(assinatura.assinar(produtor, new byte[1501], "p", "r")).isEmpty();
  }

  private static String trocar(String segmento) {
    char c = segmento.charAt(0);
    return (c == 'A' ? 'B' : 'A') + segmento.substring(1);
  }
}

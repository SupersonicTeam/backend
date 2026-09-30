package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Testes do {@link FatiadorDispositivos} (issue #156) sobre HTML na estrutura do Planalto
 * ({@code legislacao/norma-ilustrativa.html} — conteúdo ilustrativo, não é lei).
 */
class FatiadorDispositivosTest {

  private static Map<String, String> porDispositivo;
  private static List<DispositivoLegal> dispositivos;

  @BeforeAll
  static void fatiar() throws IOException {
    String html = new ClassPathResource("legislacao/norma-ilustrativa.html")
        .getContentAsString(StandardCharsets.UTF_8);
    dispositivos = new FatiadorDispositivos().fatiar(html);
    porDispositivo = dispositivos.stream().collect(Collectors.toMap(
        DispositivoLegal::dispositivo, DispositivoLegal::texto, (a, b) -> a,
        java.util.LinkedHashMap::new));
  }

  @Test
  @DisplayName("Um trecho por dispositivo, na ordem: artigo, incisos, alineas, paragrafos")
  void umTrechoPorDispositivo() {
    assertThat(porDispositivo.keySet()).containsExactly(
        "art. 1º",
        "art. 2º",
        "art. 2º, inciso I",
        "art. 2º, inciso II",
        "art. 2º, inciso II, alínea a",
        "art. 2º, inciso II, alínea b",
        "art. 2º, inciso III",
        "art. 2º, § 1º",
        "art. 2º, § 2º",
        "art. 2º, § 2º, inciso I",
        "art. 2º, § 2º, inciso II",
        "art. 10",
        "art. 10, parágrafo único",
        "art. 11",
        "art. 11 (redação do art. 156-A)",
        "art. 11 (redação do art. 156-A, § 1º)",
        "art. 11 (redação do art. 156-A, § 1º, inciso I)",
        "art. 11 (redação do art. 156-A, § 1º, inciso II)",
        "art. 12");
  }

  @Test
  @DisplayName("Inciso e alinea levam o caput: sozinhos nao dizem do que tratam")
  void contextoDoCaput() {
    assertThat(porDispositivo.get("art. 2º, inciso II, alínea a"))
        .startsWith("Art. 2º O tributo de exemplo incide sobre:")
        .contains("II - a importação de bens", "a) a base é o valor aduaneiro;");
    assertThat(porDispositivo.get("art. 2º, § 2º, inciso I"))
        .contains("§ 2º O disposto no § 1º", "I - aos fertilizantes");
  }

  @Test
  @DisplayName("Texto tachado (revogado) e anotacoes editoriais ficam de fora")
  void semRevogadoNemAnotacao() {
    String inciso3 = porDispositivo.get("art. 2º, inciso III");

    assertThat(inciso3).contains("III - o fornecimento de serviços.")
        .doesNotContain("exportação", "Redação dada");
    assertThat(dispositivos).extracting(DispositivoLegal::dispositivo)
        .filteredOn("art. 2º, inciso III"::equals).hasSize(1);
  }

  @Test
  @DisplayName("Titulos, capitulos, ementa e preambulo nao viram trecho")
  void semTitulosNemEmenta() {
    assertThat(dispositivos).extracting(DispositivoLegal::texto)
        .noneMatch(t -> t.startsWith("TÍTULO") || t.startsWith("CAPÍTULO")
            || t.startsWith("NORMA ILUSTRATIVA") || t.startsWith("Dispõe"));
  }

  @Test
  @DisplayName("Nova redacao entre aspas: referencia pelo artigo da emenda e dispositivo alterado")
  void textoCitadoDeEmenda() {
    assertThat(porDispositivo.get("art. 11 (redação do art. 156-A, § 1º, inciso II)"))
        .startsWith("Art. 11. A norma de referência passa a vigorar")
        .contains("Art. 156-A.", "§ 1º O imposto de exemplo:", "II - não incidirá sobre exportações.")
        .doesNotContain("(NR)", "”");
    // Depois das aspas, volta a numeracao da propria norma.
    assertThat(porDispositivo).containsKey("art. 12");
  }

  @Test
  @DisplayName("Artigo 10 em diante sem ordinal (convencao legislativa)")
  void ordinalSoAteNove() {
    Function<String, Boolean> tem = porDispositivo::containsKey;
    assertThat(tem.apply("art. 1º")).isTrue();
    assertThat(tem.apply("art. 10")).isTrue();
    assertThat(tem.apply("art. 10º")).isFalse();
  }
}

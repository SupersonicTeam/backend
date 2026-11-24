package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A versão dos termos tem um valor só (#498): o padrão do {@code application.yml}, o do compose da
 * VPS e o do {@code .env.example}. O compose repassa {@code TERMOS_VERSAO} ao backend e vale mais
 * que o padrão do yml: se ele ficar com a versão antiga, o endpoint serve o texto novo com a versão
 * provisória (e o aceite grava a versão errada), sem erro nenhum.
 */
@DisplayName("Versão dos termos de uso: um valor só no yml, no compose e no .env.example")
class TermosVersaoCoerenteTest {

  private static final String DATA = "(\\d{4}-\\d{2}-\\d{2})";

  private static String versaoEm(String arquivo, String padrao) throws IOException {
    String texto = Files.readString(Path.of(arquivo), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile(padrao, Pattern.MULTILINE).matcher(texto);
    assertThat(m.find()).as("TERMOS_VERSAO em " + arquivo).isTrue();
    return m.group(1);
  }

  @Test
  @DisplayName("yml, compose e .env.example têm a mesma versão, e não é a provisória")
  void mesmaVersao() throws IOException {
    String yml = versaoEm("src/main/resources/application.yml",
        "^\\s+versao: \\$\\{TERMOS_VERSAO:" + DATA + "}");
    String compose = versaoEm("deploy/compose.yaml",
        "^\\s+TERMOS_VERSAO: \\$\\{TERMOS_VERSAO:-" + DATA + "}");
    String env = versaoEm(".env.example", "^TERMOS_VERSAO=" + DATA);

    assertThat(compose).as("deploy/compose.yaml").isEqualTo(yml);
    assertThat(env).as(".env.example").isEqualTo(yml);
    assertThat(yml).as("a provisória é anterior ao texto").isNotEqualTo("2026-11-11");
  }
}

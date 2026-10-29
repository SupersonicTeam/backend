package br.com.agrocontabil.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Todo NCM semeado em {@code ncm_agropecuario} existe na nomenclatura oficial (issue #282).
 *
 * <p>A RN008 ({@code @ValidNcm}) aceita o que a tabela de referência tiver. Com um código
 * inexistente semeado (era o caso da soja {@code 12010090}), a nota passava na nossa validação e
 * seria recusada pela SEFAZ real. A nomenclatura versionada fica em
 * {@code ncm/nomenclatura-ncm-oficial.txt} (download do Siscomex, com a versão da Gecex no
 * cabeçalho).
 *
 * <p>Confere as duas fontes do seed: o changelog do Liquibase, aplicado em ordem (inserts menos
 * deletes de {@code ncm_agropecuario}), e o espelho do H2 dos testes.
 */
class NcmSemeadoExisteNaNomenclaturaTest {

  private static final Pattern INCLUDE = Pattern.compile("file:\\s*(\\S+\\.xml)");
  private static final Pattern INSERT = Pattern.compile(
      "<insert tableName=\"ncm_agropecuario\">\\s*<column name=\"codigo\" value=\"(\\d{8})\"");
  private static final Pattern DELETE = Pattern.compile(
      "<delete tableName=\"ncm_agropecuario\">\\s*<where>codigo IN \\(([^)]*)\\)</where>");
  private static final Pattern ROLLBACK =
      Pattern.compile("<rollback>.*?</rollback>", Pattern.DOTALL);
  private static final Pattern CODIGO = Pattern.compile("'(\\d{8})'");
  private static final Pattern MERGE_NCM = Pattern.compile(
      "MERGE INTO ncm_agropecuario[^;]*;", Pattern.DOTALL);

  @Test
  @DisplayName("seed do Liquibase: todo NCM semeado existe na nomenclatura oficial")
  void seedDoLiquibase() throws IOException {
    Set<String> semeados = new LinkedHashSet<>();
    String master = ler("db/changelog/db.changelog-master.yaml");
    Matcher arquivos = INCLUDE.matcher(master);
    while (arquivos.find()) {
      // O rollback nao roda na subida: fora da conta.
      String changeSet = ROLLBACK.matcher(ler(arquivos.group(1))).replaceAll("");
      // Inserts e deletes do mesmo arquivo seguem a ordem do texto (a ordem dos changeSets).
      Matcher operacao = Pattern.compile(INSERT.pattern() + "|" + DELETE.pattern())
          .matcher(changeSet);
      while (operacao.find()) {
        if (operacao.group(1) != null) {
          semeados.add(operacao.group(1));
        } else {
          codigos(operacao.group(2)).forEach(semeados::remove);
        }
      }
    }

    assertThat(semeados).as("seed do changelog").hasSizeGreaterThan(10);
    assertThat(semeados).as("NCM semeado fora da nomenclatura oficial")
        .isSubsetOf(nomenclaturaOficial());
  }

  @Test
  @DisplayName("espelho do H2: todo NCM semeado existe na nomenclatura oficial")
  void espelhoDoH2() throws IOException {
    Matcher merge = MERGE_NCM.matcher(ler("schema-nota-fiscal-test.sql"));
    assertThat(merge.find()).isTrue();
    List<String> semeados = codigos(merge.group());

    assertThat(semeados).as("seed do H2").hasSizeGreaterThan(10);
    assertThat(semeados).as("NCM semeado fora da nomenclatura oficial")
        .isSubsetOf(nomenclaturaOficial());
  }

  private static Set<String> nomenclaturaOficial() throws IOException {
    Set<String> codigos = ler("ncm/nomenclatura-ncm-oficial.txt").lines()
        .filter(linha -> !linha.isBlank() && !linha.startsWith("#"))
        .collect(Collectors.toSet());
    assertThat(codigos).as("nomenclatura versionada").hasSizeGreaterThan(10_000);
    return codigos;
  }

  private static List<String> codigos(String texto) {
    return CODIGO.matcher(texto).results().map(m -> m.group(1)).toList();
  }

  private static String ler(String recurso) throws IOException {
    try (InputStream in = NcmSemeadoExisteNaNomenclaturaTest.class.getClassLoader()
        .getResourceAsStream(recurso)) {
      assertThat(in).as(recurso).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

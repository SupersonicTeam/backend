package br.com.agrocontabil.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Todo NCM semeado em {@code ncm_agropecuario} existe na nomenclatura oficial (issue #282).
 *
 * <p>A RN008 ({@code @ValidNcm} e a checagem do {@code FiscalRulesValidator}) aceita o que a
 * tabela de referência tiver. Com um código inexistente semeado (era o caso da soja
 * {@code 12010090}), a nota passava na nossa validação e seria recusada pela SEFAZ real. A
 * nomenclatura versionada fica em {@code ncm/nomenclatura-ncm-oficial.txt} (download do Siscomex,
 * com a versão da Gecex no cabeçalho).
 *
 * <p>Confere as duas fontes do seed: o changelog do Liquibase, lido como XML e aplicado em ordem
 * ({@code insert} menos {@code delete} de {@code ncm_agropecuario}, sem os blocos de rollback), e
 * o espelho do H2 dos testes, que tem de ser igual ao seed do changelog. Uma escrita em
 * {@code ncm_agropecuario} que este teste não sabe ler ({@code sql}, {@code loadData},
 * {@code update} do código) falha, em vez de passar sem conferir.
 */
class NcmSemeadoExisteNaNomenclaturaTest {

  private static final String TABELA = "ncm_agropecuario";
  private static final Pattern INCLUDE = Pattern.compile("file:\\s*(\\S+\\.xml)");
  private static final Pattern CODIGO = Pattern.compile("'(\\d{8})'");
  private static final Pattern ESCRITA_H2 = Pattern.compile(
      "(?:MERGE|INSERT)\\s+INTO\\s+" + TABELA + "[^;]*;", Pattern.CASE_INSENSITIVE);

  @Test
  @DisplayName("seed do Liquibase: todo NCM semeado existe na nomenclatura oficial")
  void seedDoLiquibase() throws Exception {
    Set<String> semeados = seedDoChangelog();

    assertThat(semeados).as("seed do changelog").hasSizeGreaterThan(10);
    assertThat(semeados).as("NCM semeado fora da nomenclatura oficial")
        .isSubsetOf(nomenclaturaOficial());
  }

  @Test
  @DisplayName("espelho do H2: igual ao seed do Liquibase")
  void espelhoDoH2() throws Exception {
    Matcher escrita = ESCRITA_H2.matcher(ler("schema-nota-fiscal-test.sql"));
    Set<String> espelho = new LinkedHashSet<>();
    while (escrita.find()) {
      espelho.addAll(codigos(escrita.group()));
    }

    assertThat(espelho).as("seed do H2").containsExactlyInAnyOrderElementsOf(seedDoChangelog());
  }

  /** Códigos que o changelog deixa em {@code ncm_agropecuario}, changeSet a changeSet. */
  private static Set<String> seedDoChangelog() throws Exception {
    Set<String> semeados = new LinkedHashSet<>();
    Matcher arquivos = INCLUDE.matcher(ler("db/changelog/db.changelog-master.yaml"));
    while (arquivos.find()) {
      for (Element operacao : operacoes(arquivos.group(1))) {
        aplicar(operacao, semeados, arquivos.group(1));
      }
    }
    return semeados;
  }

  private static void aplicar(Element operacao, Set<String> semeados, String arquivo) {
    String nome = operacao.getLocalName();
    boolean daTabela = TABELA.equalsIgnoreCase(operacao.getAttribute("tableName"));
    switch (nome) {
      case "insert" -> {
        if (daTabela) {
          semeados.add(coluna(operacao, "codigo", arquivo));
        }
      }
      case "delete" -> {
        if (daTabela) {
          String where = texto(operacao, "where");
          assertThat(where).as("delete de %s sem where em %s", TABELA, arquivo).isNotBlank();
          codigos(where).forEach(semeados::remove);
        }
      }
      // Update de outras colunas (a reducao padrao do 0012) nao muda o conjunto de codigos.
      case "update" -> assertThat(daTabela && temColuna(operacao, "codigo"))
          .as("update do codigo em %s nao e conferido por este teste (%s)", TABELA, arquivo)
          .isFalse();
      case "loadData", "loadUpdateData" -> assertThat(daTabela)
          .as("%s em %s nao e conferido por este teste (%s)", nome, TABELA, arquivo).isFalse();
      case "sql" -> assertThat(operacao.getTextContent().toLowerCase(Locale.ROOT))
          .as("SQL que escreve em %s nao e conferido por este teste (%s)", TABELA, arquivo)
          .doesNotContainPattern("(insert\\s+into|update|delete\\s+from)\\s+" + TABELA);
      default -> {
        // Demais operações não escrevem dados na tabela.
      }
    }
  }

  /** Operações de cada changeSet do arquivo, na ordem do texto, fora dos blocos de rollback. */
  private static List<Element> operacoes(String arquivo) throws Exception {
    DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
    fabrica.setNamespaceAware(true);
    fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document documento;
    try (InputStream in = recurso(arquivo)) {
      documento = fabrica.newDocumentBuilder().parse(in);
    }
    List<Element> operacoes = new ArrayList<>();
    NodeList changeSets = documento.getElementsByTagNameNS("*", "changeSet");
    for (int i = 0; i < changeSets.getLength(); i++) {
      for (Node filho = changeSets.item(i).getFirstChild(); filho != null;
          filho = filho.getNextSibling()) {
        if (filho instanceof Element elemento && !"rollback".equals(elemento.getLocalName())) {
          operacoes.add(elemento);
        }
      }
    }
    return operacoes;
  }

  private static boolean temColuna(Element operacao, String nome) {
    NodeList colunas = operacao.getElementsByTagNameNS("*", "column");
    for (int i = 0; i < colunas.getLength(); i++) {
      if (nome.equals(((Element) colunas.item(i)).getAttribute("name"))) {
        return true;
      }
    }
    return false;
  }

  private static String coluna(Element insert, String nome, String arquivo) {
    NodeList colunas = insert.getElementsByTagNameNS("*", "column");
    for (int i = 0; i < colunas.getLength(); i++) {
      Element coluna = (Element) colunas.item(i);
      if (nome.equals(coluna.getAttribute("name"))) {
        return coluna.getAttribute("value");
      }
    }
    throw new AssertionError("insert em " + TABELA + " sem a coluna " + nome + " em " + arquivo);
  }

  private static String texto(Element pai, String filho) {
    NodeList nos = pai.getElementsByTagNameNS("*", filho);
    return nos.getLength() == 0 ? "" : nos.item(0).getTextContent();
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

  private static String ler(String nome) throws IOException {
    try (InputStream in = recurso(nome)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static InputStream recurso(String nome) {
    InputStream in = NcmSemeadoExisteNaNomenclaturaTest.class.getClassLoader()
        .getResourceAsStream(nome);
    assertThat(in).as(nome).isNotNull();
    return in;
  }
}

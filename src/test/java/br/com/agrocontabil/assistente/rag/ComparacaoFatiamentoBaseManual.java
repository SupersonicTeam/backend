package br.com.agrocontabil.assistente.rag;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Comparação A SECO do fatiador contra a base de lei (#606). Ferramenta MANUAL: não roda no
 * {@code ./mvnw test} (o nome não casa com o surefire), não ingere nada, não gera embedding e não
 * usa o Ollama nem o banco.
 *
 * <p>Roda o {@link FatiadorDispositivos} sobre os cinco HTMLs do Planalto, como a ingestão faz
 * (mesmas normas, mesma vigência de cada uma, {@code art. 6º} só na Lei 9.528/1997) e compara por
 * (norma, dispositivo, vigência de início) com o conteúdo de um <strong>CSV com o que há hoje na
 * base</strong> ({@code norma, dispositivo, vigencia_inicio, vigencia_fim, content, hash}), tirado
 * por fora, somente leitura:
 *
 * <pre>
 * docker exec agrocontabil-postgres psql -U agrocontabil -d agrocontabil -c "COPY (SELECT norma,
 *   dispositivo, vigencia_inicio, vigencia_fim, content, hash FROM legislacao_trecho ORDER BY
 *   norma, dispositivo) TO STDOUT WITH (FORMAT csv, HEADER)" &gt; base.csv
 * </pre>
 *
 * <p>Parâmetros ({@code -Dtest=ComparacaoFatiamentoBaseManual}):
 *
 * <ul>
 *   <li>{@code comparacao.pasta}: pasta com {@code lcp214.htm}, {@code lcp227.htm}, {@code
 *       l8212cons.htm}, {@code emc132.htm} e {@code l9528.htm}, como o Planalto serve;</li>
 *   <li>{@code comparacao.base}: o CSV da base (acima) ou, para isolar o efeito do código do efeito
 *       de o Planalto ter mudado o texto, o CSV {@code comparacao.novo-snapshot} de uma rodada
 *       anterior;</li>
 *   <li>{@code comparacao.saida}: relatório em Markdown;</li>
 *   <li>{@code comparacao.novo-snapshot} (opcional): grava o que o fatiador devolveu, no mesmo
 *       formato do CSV da base;</li>
 *   <li>{@code comparacao.sql-saida} (opcional): grava o {@code DELETE} dos trechos que mudam, por
 *       hash (ver o procedimento no {@code deploy/README.md}).</li>
 * </ul>
 *
 * <p>Por norma, o relatório diz quantos trechos são iguais, quantos mudam, quantos somem e quantos
 * aparecem, e classifica cada mudança: {@code SUFIXO} (o texto novo é o começo do antigo: o que
 * sobrou no fim saiu), {@code MEIO} (o texto novo é o antigo sem um pedaço do meio) ou {@code
 * OUTRA} (qualquer outra coisa: o que o fatiador nunca deve fazer com texto de lei).
 */
class ComparacaoFatiamentoBaseManual {

  /** Norma, arquivo, vigência de início da primeira carga e dispositivos pedidos (vazio = todos). */
  private record Fonte(String norma, String arquivo, LocalDate vigenciaInicio, List<String> somente) {
  }

  private static final List<Fonte> FONTES = List.of(
      new Fonte("LC 214/2025", "lcp214.htm", LocalDate.parse("2025-01-16"), List.of()),
      new Fonte("LC 227/2026", "lcp227.htm", LocalDate.parse("2026-01-14"), List.of()),
      new Fonte("Lei 8.212/1991", "l8212cons.htm", LocalDate.parse("1991-07-25"), List.of()),
      new Fonte("EC 132/2023", "emc132.htm", LocalDate.parse("2023-12-21"), List.of()),
      new Fonte("Lei 9.528/1997", "l9528.htm", LocalDate.parse("1997-12-11"),
          List.of("art. 6º")));

  /** Texto que termina em "Do/Da/Dos/Das Título" sem pontuação final: o defeito da #606. */
  private static final Pattern TERMINA_EM_TITULO =
      Pattern.compile("(?s).*\\s(?:Do|Da|Dos|Das|DO|DA|DOS|DAS)\\s[^.;:!?]{3,200}[^.;:!?\\s]$");
  private static final int TRECHO_NO_RELATORIO = 140;
  /** O diff por palavras usa uma matriz de a × b inteiros; acima disso vira {@code OUTRA}. */
  private static final long LIMITE_CELULAS_DO_DIFF = 100_000_000L;

  /** Uma linha da base (ou do snapshot). */
  private record Linha(String norma, String dispositivo, String vigenciaInicio,
      String vigenciaFim, String content, String hash) {

    String chave() {
      return norma + "|" + dispositivo + "|" + vigenciaInicio;
    }
  }

  private enum Tipo { SUFIXO, MEIO, OUTRA }

  /** {@code removidos}: os pedaços que saíram, na ordem (vazio em {@code OUTRA}). */
  private record Mudanca(Linha antes, Linha depois, Tipo tipo, List<String> removidos) {
  }

  /** Anotação do Planalto (link sem parênteses): o que o fatiador corrigido tira de propósito. */
  private static final Pattern ANOTACAO = Pattern.compile(
      "(?i)^\\(?(?:produ[çc][ãa]o de efeitos?|vig[êe]ncia(?: encerrada)?|sem efic[áa]cia"
          + "|vide\\b.*)\\)?\\.?$");

  /** Sem os parâmetros a ferramenta não faz nada (nem falha): o {@code ./mvnw test} a pula. */
  @Test
  void comparar() throws IOException {
    String pasta = System.getProperty("comparacao.pasta");
    String base = System.getProperty("comparacao.base");
    String saida = System.getProperty("comparacao.saida");
    Assumptions.assumeTrue(pasta != null && base != null && saida != null,
        "ferramenta manual: informe -Dcomparacao.pasta, -Dcomparacao.base e -Dcomparacao.saida");

    Map<String, Linha> atuais = indexar(lerCsv(Path.of(base)));
    List<Linha> novas = new ArrayList<>();
    FatiadorDispositivos fatiador = new FatiadorDispositivos();
    for (Fonte f : FONTES) {
      List<DispositivoLegal> dispositivos = IngestaoLegislacaoRunner.selecionar(
          fatiador.fatiar(LeitorHtmlLegislacao.ler(Path.of(pasta, f.arquivo()))), f.somente());
      for (DispositivoLegal d : dispositivos) {
        novas.add(new Linha(f.norma(), d.dispositivo(), f.vigenciaInicio().toString(),
            "9999-12-31", d.texto(), IngestaoLegislacaoService.hash(f.norma(), d)));
      }
    }
    Map<String, Linha> novosPorChave = indexar(novas);
    if (novosPorChave.size() != novas.size()) {
      throw new IllegalStateException("chave (norma, dispositivo, vigência) repetida no fatiador");
    }
    if (System.getProperty("comparacao.novo-snapshot") != null) {
      gravarCsv(Path.of(System.getProperty("comparacao.novo-snapshot")), novas);
    }

    StringBuilder relatorio = new StringBuilder("# Comparação a seco do fatiador (#606)\n\n");
    List<Mudanca> todasMudancas = new ArrayList<>();
    int divergencias = 0;
    relatorio.append("| Norma | Base | Fatiador | Iguais | Mudam | Somem | Aparecem |\n")
        .append("|---|---:|---:|---:|---:|---:|---:|\n");
    StringBuilder detalhes = new StringBuilder();
    for (Fonte f : FONTES) {
      List<Linha> daBase = atuais.values().stream().filter(l -> l.norma().equals(f.norma()))
          .toList();
      List<Linha> doFatiador = novosPorChave.values().stream()
          .filter(l -> l.norma().equals(f.norma())).toList();
      int iguais = 0;
      List<Mudanca> mudancas = new ArrayList<>();
      List<Linha> somem = new ArrayList<>();
      for (Linha antes : daBase) {
        Linha depois = novosPorChave.get(antes.chave());
        if (depois == null) {
          somem.add(antes);
        } else if (depois.content().equals(antes.content())) {
          iguais++;
        } else {
          mudancas.add(classificar(antes, depois));
        }
      }
      List<Linha> aparecem = doFatiador.stream()
          .filter(l -> !atuais.containsKey(l.chave())).toList();
      relatorio.append("| %s | %d | %d | %d | %d | %d | %d |%n".formatted(f.norma(), daBase.size(),
          doFatiador.size(), iguais, mudancas.size(), somem.size(), aparecem.size()));
      todasMudancas.addAll(mudancas);
      divergencias += somem.size() + aparecem.size();
      detalhes.append("\n## ").append(f.norma()).append("\n\n");
      detalhes.append(resumoPorTipo(mudancas));
      detalhes.append("Terminam em título colado (heurística): base %d, fatiador %d.%n%n".formatted(
          contarTitulo(daBase), contarTitulo(doFatiador)));
      detalhes.append("Com anotação do Planalto no texto (\"Produção de efeito(s)\", \"Sem eficácia\"):"
          + " base %d, fatiador %d.%n%n".formatted(
              contarAnotacao(daBase), contarAnotacao(doFatiador)));
      listar(detalhes, "Somem (estão na base e o fatiador não produz)", somem);
      listar(detalhes, "Aparecem (o fatiador produz e não estão na base)", aparecem);
      Map<String, Integer> distintos = new LinkedHashMap<>();
      for (Mudanca m : mudancas) {
        for (String removido : m.removidos()) {
          distintos.merge(removido, 1, Integer::sum);
        }
      }
      detalhes.append("### O que sai (pedaço removido e em quantos trechos): %d distintos, %d de anotação%n%n"
          .formatted(distintos.size(),
              distintos.keySet().stream().filter(r -> ANOTACAO.matcher(r).matches()).count()));
      distintos.entrySet().stream()
          .sorted((x, y) -> y.getValue() - x.getValue())
          .forEach(e -> detalhes.append("- %d× %s `%s`%n".formatted(e.getValue(),
              ANOTACAO.matcher(e.getKey()).matches() ? "[anotação]" : "[outro]",
              cortar(e.getKey()))));
      detalhes.append("\n### Mudam\n\n");
      for (Mudanca m : mudancas) {
        detalhes.append("- %s — %s — %s%n".formatted(m.antes().dispositivo(), m.tipo(),
            m.tipo() == Tipo.OUTRA ? "**ACRESCENTA OU TROCA PALAVRAS: revisar**"
                : "removido: `" + cortar(String.join(" | ", m.removidos())) + "`"));
      }
    }
    relatorio.append(detalhes);
    Files.writeString(Path.of(saida), relatorio.toString(), StandardCharsets.UTF_8);

    if (System.getProperty("comparacao.sql-saida") != null) {
      // Só quando o fatiador apenas removeu texto e não perdeu nem criou dispositivo: qualquer outra
      // coisa é para o operador ler e decidir antes de apagar linha da base.
      if (divergencias == 0 && todasMudancas.stream().noneMatch(m -> m.tipo() == Tipo.OUTRA)) {
        gravarSql(Path.of(System.getProperty("comparacao.sql-saida")), todasMudancas);
      } else {
        System.out.println("SQL NÃO GERADO: há dispositivo que some ou aparece, ou mudança que"
            + " não é só remoção (OUTRA). Leia o relatório.");
      }
    }
    System.out.println("Relatório em " + saida);
  }

  /**
   * Diff por palavras (subsequência comum mais longa): se todas as palavras do texto novo estão no
   * antigo, na mesma ordem, a mudança é só remoção e os pedaços removidos saem agrupados; senão é
   * {@code OUTRA} (alguma palavra foi acrescentada ou trocada).
   */
  private static Mudanca classificar(Linha antes, Linha depois) {
    String[] a = antes.content().split(" ", -1);
    String[] b = depois.content().split(" ", -1);
    if ((long) a.length * b.length > LIMITE_CELULAS_DO_DIFF) {
      // Matriz grande demais: não arrisca estourar a memória, e quem lê o relatório decide.
      return new Mudanca(antes, depois, Tipo.OUTRA, List.of());
    }
    int[][] lcs = new int[a.length + 1][b.length + 1];
    for (int i = a.length - 1; i >= 0; i--) {
      for (int j = b.length - 1; j >= 0; j--) {
        lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1
            : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    if (lcs[0][0] != b.length) {
      return new Mudanca(antes, depois, Tipo.OUTRA, List.of());
    }
    List<String> removidos = new ArrayList<>();
    StringBuilder corrente = new StringBuilder();
    int i = 0;
    int j = 0;
    boolean terminaRemovendo = false;
    while (i < a.length) {
      if (j < b.length && a[i].equals(b[j]) && lcs[i][j] == lcs[i + 1][j + 1] + 1) {
        if (corrente.length() > 0) {
          removidos.add(corrente.toString());
          corrente.setLength(0);
        }
        i++;
        j++;
        terminaRemovendo = false;
      } else {
        corrente.append(corrente.length() > 0 ? " " : "").append(a[i]);
        i++;
        terminaRemovendo = true;
      }
    }
    if (corrente.length() > 0) {
      removidos.add(corrente.toString());
    }
    boolean soNoFim = removidos.size() == 1 && terminaRemovendo;
    return new Mudanca(antes, depois, soNoFim ? Tipo.SUFIXO : Tipo.MEIO, removidos);
  }

  private static String resumoPorTipo(List<Mudanca> mudancas) {
    int sufixo = 0;
    int meio = 0;
    int outra = 0;
    for (Mudanca m : mudancas) {
      switch (m.tipo()) {
        case SUFIXO -> sufixo++;
        case MEIO -> meio++;
        default -> outra++;
      }
    }
    return "Mudanças: %d SUFIXO, %d MEIO, %d OUTRA.%n%n".formatted(sufixo, meio, outra);
  }

  private static void listar(StringBuilder sb, String titulo, List<Linha> linhas) {
    sb.append("### ").append(titulo).append(": ").append(linhas.size()).append("\n\n");
    for (Linha l : linhas) {
      sb.append("- ").append(l.dispositivo()).append('\n');
    }
    sb.append('\n');
  }

  private static long contarTitulo(List<Linha> linhas) {
    return linhas.stream().filter(l -> TERMINA_EM_TITULO.matcher(l.content()).matches()).count();
  }

  private static long contarAnotacao(List<Linha> linhas) {
    return linhas.stream().filter(l -> {
      String t = l.content().toLowerCase(Locale.ROOT);
      return t.contains("produção de efeito") || t.contains("sem eficácia");
    }).count();
  }

  private static String cortar(String texto) {
    String s = texto.replace("\n", " ");
    return s.length() > TRECHO_NO_RELATORIO ? s.substring(0, TRECHO_NO_RELATORIO) + "…" : s;
  }

  private static Map<String, Linha> indexar(List<Linha> linhas) {
    Map<String, Linha> mapa = new LinkedHashMap<>();
    List<String> repetidas = new ArrayList<>();
    for (Linha l : linhas) {
      if (mapa.put(l.chave(), l) != null) {
        repetidas.add(l.chave());
      }
    }
    if (!repetidas.isEmpty()) {
      // Duas linhas com a mesma (norma, dispositivo, vigência): a comparação esconderia uma delas e o
      // DELETE não a apagaria; a reingestão seria recusada depois. Quem lê decide antes.
      throw new IllegalStateException("chave (norma|dispositivo|vigência) repetida em "
          + repetidas.size() + " linha(s), a primeira: " + repetidas.get(0));
    }
    return mapa;
  }

  private static void gravarSql(Path arquivo, List<Mudanca> mudancas) throws IOException {
    Set<String> hashes = new LinkedHashSet<>();
    for (Mudanca m : mudancas) {
      hashes.add(m.antes().hash());
    }
    StringBuilder sql = new StringBuilder(
        "-- #606: trechos da base que o fatiador corrigido refaz (por hash do texto antigo).\n"
            + "-- Depois de apagar, a reingestão (mesmos argumentos da carga) os refaz com o texto\n"
            + "-- novo e o embedding novo; os outros trechos ficam como estão.\n"
            + "-- Esperado: DELETE " + hashes.size() + "\nBEGIN;\n");
    sql.append("DELETE FROM legislacao_trecho WHERE hash IN (\n");
    sql.append(String.join(",\n", hashes.stream().map(h -> "  '" + h + "'").toList()));
    sql.append("\n);\nCOMMIT;\n");
    Files.writeString(arquivo, sql.toString(), StandardCharsets.UTF_8);
  }

  // ---- CSV (RFC 4180, com campos entre aspas e quebras de linha dentro do campo) ----

  private static List<Linha> lerCsv(Path arquivo) throws IOException {
    String texto = Files.readString(arquivo, StandardCharsets.UTF_8);
    List<List<String>> registros = new ArrayList<>();
    List<String> campos = new ArrayList<>();
    StringBuilder campo = new StringBuilder();
    boolean aspas = false;
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      if (aspas) {
        if (c == '"') {
          if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
            campo.append('"');
            i++;
          } else {
            aspas = false;
          }
        } else {
          campo.append(c);
        }
      } else if (c == '"') {
        aspas = true;
      } else if (c == ',') {
        campos.add(campo.toString());
        campo.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
          i++;
        }
        campos.add(campo.toString());
        campo.setLength(0);
        registros.add(campos);
        campos = new ArrayList<>();
      } else {
        campo.append(c);
      }
    }
    if (campo.length() > 0 || !campos.isEmpty()) {
      campos.add(campo.toString());
      registros.add(campos);
    }
    List<Linha> linhas = new ArrayList<>();
    for (List<String> r : registros.subList(1, registros.size())) {
      linhas.add(new Linha(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5)));
    }
    return linhas;
  }

  private static void gravarCsv(Path arquivo, List<Linha> linhas) throws IOException {
    try (Writer w = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
      w.write("norma,dispositivo,vigencia_inicio,vigencia_fim,content,hash\n");
      for (Linha l : linhas) {
        w.write(String.join(",", aspas(l.norma()), aspas(l.dispositivo()),
            aspas(l.vigenciaInicio()), aspas(l.vigenciaFim()), aspas(l.content()), aspas(l.hash())));
        w.write("\n");
      }
    }
  }

  private static String aspas(String valor) {
    return "\"" + valor.replace("\"", "\"\"") + "\"";
  }
}

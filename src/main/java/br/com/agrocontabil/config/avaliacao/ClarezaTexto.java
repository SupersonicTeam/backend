package br.com.agrocontabil.config.avaliacao;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Medidas automáticas de clareza de uma resposta da Ceres (issue #264): frases, palavras por frase
 * e siglas do glossário usadas sem explicação. Funções puras, sem Spring.
 *
 * <p>São um indicador barato, que vale para toda etapa da Ceres 2.0; não substituem a nota do
 * avaliador externo (CSV cego + rubrica, #275). Uma sigla conta como explicada se a resposta traz,
 * em qualquer lugar, uma das explicações do {@link #GLOSSARIO} (sem acento e sem caixa): "IBS
 * (imposto sobre bens e serviços)" ou "o imposto sobre bens e serviços, o IBS".
 */
public final class ClarezaTexto {

  /**
   * Siglas que o produtor rural não precisa conhecer, com as explicações aceitas (já sem acento e
   * em minúsculas). A lista inicial é a da issue #264 e vira o glossário da etapa 5.
   */
  static final Map<String, List<String>> GLOSSARIO = glossario();

  private static final Map<String, Pattern> PADRAO_SIGLA = padroes();

  /** Abreviações cujo ponto não termina a frase ("art. 164", "inc. IV", "n. 214"). */
  private static final Pattern ABREVIACAO = Pattern.compile(
      "\\b(arts?|incs?|al|n|nº|par|pag|p|sr|sra|dr|dra|etc|ex|obs|aprox)\\.",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Fim de frase: . ! ? seguidos de espaço ou do fim, ou quebra de linha. */
  private static final Pattern FIM_DE_FRASE = Pattern.compile("[.!?]+(?=\\s|$)|\\n+");

  private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{N}]");
  private static final Pattern ACENTOS = Pattern.compile("\\p{M}");

  private ClarezaTexto() {}

  /**
   * Medidas de um texto.
   *
   * @param frases              frases com ao menos uma palavra
   * @param palavras            palavras (tokens com letra ou dígito)
   * @param siglasSemExplicacao siglas do glossário presentes e não explicadas, na ordem do glossário
   */
  public record Medida(int frases, int palavras, List<String> siglasSemExplicacao) {

    /** Cópia defensiva. */
    public Medida {
      siglasSemExplicacao = List.copyOf(siglasSemExplicacao);
    }

    /** Média de palavras por frase; 0 sem frases. */
    public double palavrasPorFrase() {
      return frases == 0 ? 0 : (double) palavras / frases;
    }
  }

  /**
   * Mede um texto. Texto nulo ou vazio dá tudo zero.
   *
   * @param texto resposta entregue ao produtor
   * @return frases, palavras e siglas sem explicação
   */
  public static Medida medir(String texto) {
    if (texto == null || texto.isBlank()) {
      return new Medida(0, 0, List.of());
    }
    String semAbreviacao = ABREVIACAO.matcher(texto).replaceAll("$1");
    int frases = 0;
    int palavras = 0;
    for (String frase : FIM_DE_FRASE.split(semAbreviacao)) {
      int daFrase = (int) Arrays.stream(frase.trim().split("\\s+"))
          .filter(t -> PALAVRA.matcher(t).find()).count();
      if (daFrase > 0) {
        frases++;
        palavras += daFrase;
      }
    }
    return new Medida(frases, palavras, siglasSemExplicacao(texto));
  }

  /** Siglas do glossário que aparecem no texto sem nenhuma das explicações aceitas. */
  static List<String> siglasSemExplicacao(String texto) {
    String normalizado = normalizar(texto);
    List<String> semExplicacao = new ArrayList<>();
    GLOSSARIO.forEach((sigla, explicacoes) -> {
      if (PADRAO_SIGLA.get(sigla).matcher(texto).find()
          && explicacoes.stream().noneMatch(normalizado::contains)) {
        semExplicacao.add(sigla);
      }
    });
    return semExplicacao;
  }

  /** Minúsculas, sem acento e com espaços simples. */
  static String normalizar(String texto) {
    return ACENTOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("")
        .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static Map<String, List<String>> glossario() {
    Map<String, List<String>> g = new LinkedHashMap<>();
    g.put("IBS", List.of("imposto sobre bens e servicos"));
    g.put("CBS", List.of("contribuicao sobre bens e servicos"));
    g.put("NF-e", List.of("nota fiscal eletronica"));
    g.put("NCM", List.of("nomenclatura comum do mercosul", "codigo do produto",
        "codigo de classificacao do produto"));
    g.put("CFOP", List.of("codigo fiscal de operacoes", "codigo da operacao",
        "codigo fiscal da operacao"));
    g.put("Funrural", List.of("contribuicao previdenciaria", "previdencia rural",
        "previdencia do produtor", "inss"));
    g.put("DANFE", List.of("documento auxiliar"));
    g.put("SEFAZ", List.of("secretaria da fazenda", "secretaria estadual da fazenda"));
    return Collections.unmodifiableMap(g);
  }

  private static Map<String, Pattern> padroes() {
    Map<String, Pattern> p = new LinkedHashMap<>();
    p.put("IBS", Pattern.compile("\\bIBS\\b"));
    p.put("CBS", Pattern.compile("\\bCBS\\b"));
    p.put("NF-e", Pattern.compile("\\bNF-?e\\b", Pattern.CASE_INSENSITIVE));
    p.put("NCM", Pattern.compile("\\bNCM\\b"));
    p.put("CFOP", Pattern.compile("\\bCFOP\\b"));
    p.put("Funrural", Pattern.compile("\\bfunrural\\b", Pattern.CASE_INSENSITIVE));
    p.put("DANFE", Pattern.compile("\\bDANFE\\b", Pattern.CASE_INSENSITIVE));
    p.put("SEFAZ", Pattern.compile("\\bSEFAZ\\b", Pattern.CASE_INSENSITIVE));
    return p;
  }
}

package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Medidas automáticas de clareza de uma resposta da Ceres (issue #264): frases, palavras por frase
 * e siglas do glossário usadas sem explicação. Funções puras, sem Spring.
 *
 * <p>São um indicador barato, que vale para toda etapa da Ceres 2.0; não substituem a nota do
 * orientador (CSV cego + rubrica, #275; sem contador, #387). Uma sigla conta como explicada se a resposta traz,
 * em qualquer lugar, uma das explicações do {@link #GLOSSARIO} (sem acento e sem caixa): "IBS
 * (imposto sobre bens e serviços)" ou "o imposto sobre bens e serviços, o IBS".
 */
public final class ClarezaTexto {

  /**
   * Uma sigla que o produtor rural não precisa conhecer.
   *
   * @param nome        sigla, como aparece no relatório
   * @param padrao      como ela aparece no texto
   * @param explicacoes explicações aceitas, já sem acento e em minúsculas
   */
  record Sigla(String nome, Pattern padrao, List<String> explicacoes) {}

  /** Glossário inicial da issue #264; vira o glossário da etapa 5. */
  static final List<Sigla> GLOSSARIO = List.of(
      new Sigla("IBS", Pattern.compile("\\bIBS\\b"), List.of("imposto sobre bens e servicos")),
      new Sigla("CBS", Pattern.compile("\\bCBS\\b"), List.of("contribuicao sobre bens e servicos",
          "contribuicao social sobre bens e servicos")),
      new Sigla("NF-e", Pattern.compile("\\bNF-?e\\b", Pattern.CASE_INSENSITIVE),
          List.of("nota fiscal eletronica")),
      new Sigla("NCM", Pattern.compile("\\bNCM\\b"), List.of("nomenclatura comum do mercosul",
          "codigo do produto", "codigo de classificacao do produto")),
      new Sigla("CFOP", Pattern.compile("\\bCFOP\\b"), List.of("codigo fiscal de operacoes",
          "codigo da operacao", "codigo fiscal da operacao")),
      new Sigla("Funrural", Pattern.compile("\\bfunrural\\b", Pattern.CASE_INSENSITIVE),
          List.of("contribuicao previdenciaria", "previdencia rural", "previdencia do produtor",
              "inss")),
      new Sigla("DANFE", Pattern.compile("\\bDANFE\\b", Pattern.CASE_INSENSITIVE),
          List.of("documento auxiliar")),
      new Sigla("SEFAZ", Pattern.compile("\\bSEFAZ\\b", Pattern.CASE_INSENSITIVE),
          List.of("secretaria da fazenda", "secretaria de fazenda", "secretaria de estado de fazenda",
              "secretaria de estado da fazenda", "secretaria estadual da fazenda")));

  /** Abreviações cujo ponto não termina a frase ("art. 164", "inc. IV", "n. 214"). */
  private static final Pattern ABREVIACAO = Pattern.compile(
      "\\b(arts?|incs?|al|n|nº|par|pag|p|sr|sra|dr|dra|ex|obs|aprox)\\.",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Fim de frase: . ! ? seguidos de espaço ou do fim, ou quebra de linha. */
  private static final Pattern FIM_DE_FRASE = Pattern.compile("[.!?]+(?=\\s|$)|\\n+");

  /** Marcador de item numerado no começo da linha ("1. ", "2) "): não é uma frase. */
  private static final Pattern ITEM_NUMERADO = Pattern.compile("(?m)^\\s*\\d{1,3}[.)]\\s+");

  private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{N}]");

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
    String semMarcador = ITEM_NUMERADO.matcher(texto).replaceAll("");
    String semAbreviacao = ABREVIACAO.matcher(semMarcador).replaceAll("$1");
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
    for (Sigla sigla : GLOSSARIO) {
      if (sigla.padrao().matcher(texto).find()
          && sigla.explicacoes().stream().noneMatch(normalizado::contains)) {
        semExplicacao.add(sigla.nome());
      }
    }
    return semExplicacao;
  }

  /** Minúsculas, sem acento e com espaços simples (a mesma normalização da busca, #174). */
  static String normalizar(String texto) {
    return NormalizacaoBusca.normalizar(texto);
  }
}

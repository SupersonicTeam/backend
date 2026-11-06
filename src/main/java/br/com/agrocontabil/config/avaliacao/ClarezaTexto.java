package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Medidas automáticas de clareza de uma resposta da Ceres (issue #264): frases, palavras por frase
 * e siglas do glossário usadas sem explicação. Funções puras, sem Spring.
 *
 * <p>São um indicador barato, que vale para toda etapa da Ceres 2.0; não substituem a nota do
 * orientador (CSV cego + rubrica, #275; sem contador, #387). Uma sigla conta como explicada se a resposta traz,
 * em qualquer lugar, uma das explicações do {@link GlossarioProdutor} (sem acento e sem caixa):
 * "IBS (imposto sobre bens e serviços)" ou "o imposto sobre bens e serviços, o IBS".
 *
 * <p><strong>A régua tem 8 siglas, de propósito</strong> ({@link #SIGLAS_DA_REGUA}): a medição
 * alimenta o {@code comSiglaSemExplicacao} do benchmark, que põe lado a lado a Ceres 1 (8 siglas) e
 * a Ceres 2.0. Sigla nova no glossário não entra aqui.
 */
public final class ClarezaTexto {

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

  /**
   * As 8 siglas da régua da #264, por nome no {@link GlossarioProdutor}. A régua é fixa de
   * propósito: o benchmark da banca compara a Ceres 1 (que mede estas 8) com a Ceres 2.0, e só
   * vale se as duas usam a mesma. O glossário tem mais siglas, cobradas pelo
   * {@code TextosFixosSiglasTest} e pela composição, não por esta medição.
   */
  static final List<String> SIGLAS_DA_REGUA = List.of(
      "IBS", "CBS", "NF-e", "NCM", "CFOP", "Funrural", "DANFE", "SEFAZ");

  /**
   * Siglas da régua que aparecem no texto sem a forma por extenso nem expressão aceita (a
   * definição do glossário não conta, como na Ceres 1), na ordem do glossário.
   */
  static List<String> siglasSemExplicacao(String texto) {
    return GlossarioProdutor.padrao().siglasSemNome(texto).stream()
        .map(GlossarioProdutor.Termo::termo)
        .filter(SIGLAS_DA_REGUA::contains)
        .toList();
  }
}

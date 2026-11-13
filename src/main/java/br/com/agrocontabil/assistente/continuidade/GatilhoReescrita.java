package br.com.agrocontabil.assistente.continuidade;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quando a mensagem nova depende do histórico e vai à reescrita (#499). Só com histórico válido; com
 * o gatilho falso, nenhuma chamada usa o histórico ({@code CONTINUIDADE_DISPENSADA}).
 *
 * <ol type="a">
 *   <li>começa por "e", "mas", "então" ou "também" e tem até 6 palavras;</li>
 *   <li>tem anáfora ("isso", "dele", "nessa"...) ou demonstrativo ("esse", "aquela"...), nenhum
 *       seguido de período ("esse mês", "este ano");</li>
 *   <li>tem ordinal ("a segunda", "o último") sem palavra de conteúdo depois, ou ordinal e até 4
 *       palavras;</li>
 *   <li>é inteira um SEGUIMENTO que não é confirmação ("por quê?", "como assim?", "explica
 *       melhor");</li>
 *   <li>é contestação com conteúdo novo.</li>
 * </ol>
 *
 * <p>Ficam de fora o critério solto "até 4 palavras" e "por que" dentro de frase maior ("Por que a
 * nota 12 está rejeitada?" se entende sozinha).
 */
public final class GatilhoReescrita {

  private static final Pattern REFERENCIA = Pattern.compile(
      "\\b(isso|disso|nisso|desse|dessa|desses|dessas|nesse|nessa|dele|dela|deles|delas|nele"
          + "|nela|o mesmo|a mesma|esse|essa|esses|essas|este|esta|estes|estas|aquele|aquela)\\b"
          + "(?! (mes|ano|semana|semestre|trimestre|safra|periodo|dia|data|quinzena|exercicio)\\b)");

  private static final Pattern ORDINAL = Pattern.compile(
      "\\b(a|o|as|os) (primeir|segund|terceir|quart|quint|ultim|penultim)(a|o|as|os)\\b");

  private static final Set<String> SEGUIMENTOS = Set.of("por que", "porque", "como assim",
      "explica melhor", "explique melhor", "explica de novo", "nao entendi", "e entao",
      "e agora");

  private GatilhoReescrita() {
  }

  /** Qual critério disparou (a âncora da reescrita depende dele). */
  public enum Criterio {
    /** (a) conectivo no começo. */
    CONECTIVO,
    /** (b) anáfora ou demonstrativo. */
    REFERENCIA,
    /** (c) ordinal. */
    ORDINAL,
    /** (d) SEGUIMENTO que não é confirmação. */
    SEGUIMENTO,
    /** (e) contestação com conteúdo novo. */
    CONTESTACAO
  }

  /**
   * {@code true} se a mensagem vai à reescrita.
   *
   * @param mensagem mensagem nova
   * @param tipo     o que ela faz com a resposta anterior
   * @return se o gatilho dispara
   */
  public static boolean dispara(String mensagem, MensagemContinuidade.Tipo tipo) {
    return criterio(mensagem, tipo).isPresent();
  }

  /**
   * O critério que dispara, se algum.
   *
   * @param mensagem mensagem nova
   * @param tipo     o que ela faz com a resposta anterior
   * @return o critério, na ordem (e), (d), (c), (b), (a)
   */
  public static java.util.Optional<Criterio> criterio(String mensagem,
      MensagemContinuidade.Tipo tipo) {
    if (tipo == MensagemContinuidade.Tipo.CONTESTACAO_CONTEUDO) {
      return java.util.Optional.of(Criterio.CONTESTACAO);
    }
    if (tipo.caminhoDaConfirmacao()) {
      return java.util.Optional.empty();
    }
    String normal = TextoContinuidade.normalizar(mensagem);
    if (normal.isEmpty()) {
      return java.util.Optional.empty();
    }
    // Ordem pela ancora da reescrita: o SEGUIMENTO ("e entao?") e o ordinal ("e a segunda?")
    // tambem comecam por conectivo, e a ancora deles e mais estrita.
    if (SEGUIMENTOS.contains(normal)) {
      return java.util.Optional.of(Criterio.SEGUIMENTO);
    }
    List<String> palavras = TextoContinuidade.palavras(normal);
    var ordinal = ORDINAL.matcher(normal);
    if (ordinal.find()) {
      String depois = normal.substring(ordinal.end());
      boolean semConteudoDepois = TextoContinuidade.palavras(depois.strip()).stream()
          .noneMatch(TextoContinuidade::conteudo);
      if (semConteudoDepois || palavras.size() <= 4) {
        return java.util.Optional.of(Criterio.ORDINAL);
      }
    }
    if (REFERENCIA.matcher(normal).find()) {
      return java.util.Optional.of(Criterio.REFERENCIA);
    }
    if (TextoContinuidade.CONECTIVOS.contains(palavras.get(0)) && palavras.size() <= 6) {
      return java.util.Optional.of(Criterio.CONECTIVO);
    }
    return java.util.Optional.empty();
  }
}

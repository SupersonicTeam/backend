package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.orquestracao.ConversaLeve;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que a mensagem nova faz com a resposta anterior (#499): confirmação ("tem certeza?"),
 * contestação pura ("está errado"), reafirmação ("não, eu perguntei da soja", quando a soja já
 * estava na pergunta anterior), contestação com conteúdo novo ("não, eu perguntei do feijão") ou
 * nada disso. Sobre o {@code normalizar} e sem o vocativo "ceres".
 */
public final class MensagemContinuidade {

  /** O que a mensagem é. */
  public enum Tipo {
    /** Pede para conferir a resposta anterior. */
    CONFIRMACAO,
    /** Diz que a resposta anterior está errada, sem dizer o quê. */
    CONTESTACAO_PURA,
    /** Contesta repetindo o que a pergunta anterior já tinha. */
    REAFIRMACAO,
    /** Contesta trazendo algo novo: vai à reescrita. */
    CONTESTACAO_CONTEUDO,
    /** Nada disso. */
    NENHUMA;

    /** Segue o caminho da confirmação (sem reescrita). */
    public boolean caminhoDaConfirmacao() {
      return this == CONFIRMACAO || this == CONTESTACAO_PURA || this == REAFIRMACAO;
    }

    /** É contestação (pura, reafirmação ou com conteúdo). */
    public boolean contestacao() {
      return this == CONTESTACAO_PURA || this == REAFIRMACAO || this == CONTESTACAO_CONTEUDO;
    }
  }

  private static final Pattern TEM_CERTEZA = Pattern.compile(
      "^(voce |vc )?(tem|tens) (certeza|ctz)( disso| mesmo| absoluta)?$");

  private static final Set<String> CONFIRMACOES = Set.of("certeza", "certeza mesmo", "serio",
      "e serio", "repete", "repita", "repete isso", "de novo", "confere", "confere isso",
      "confere de novo", "confira de novo", "verifica de novo", "verifique de novo", "confirma",
      "confirme", "confirma isso", "ta certo isso");

  private static final Set<String> CONFIRMACOES_COM_INTERROGACAO =
      Set.of("certo", "ta certo", "isso mesmo");

  /** Marcadores de contestação, do mais longo para o mais curto. */
  private static final List<String> MARCADORES = List.of("isso esta errado", "isso ta errado",
      "voce errou", "vc errou", "nao esta certo", "nao ta certo", "esta errado", "ta errado",
      "nao e isso", "nao foi isso", "nao era isso", "nao bate", "nao confere", "incorreto",
      "errado", "errou");

  /** "Não" seguido de pontuação no começo (no texto cru, antes de normalizar). */
  private static final Pattern NAO_COM_PONTUACAO = Pattern.compile(
      "^\\s*(?:ceres\\s*[,.:;!-]*\\s*)?n[aã]o\\s*[,.;:!?]", Pattern.CASE_INSENSITIVE
          | Pattern.UNICODE_CASE);

  /** Palavras de polaridade e de juízo: depois de um par de lei, não contam como conteúdo. */
  private static final Set<String> POLARIDADE = Set.of("sim", "nao", "isento", "isenta",
      "isencao", "zero", "paga", "pago", "cobra", "incide", "tem", "certo", "correto", "verdade");

  /** Exclusões da guarda de conteúdo que também tiram palavras de W. */
  private static final Set<String> EXCLUSOES = Set.of("mais", "menos", "perguntei", "queria",
      "eu", "voce", "vc", "isso", "nao", "sim", "e", "mas", "entao");

  private MensagemContinuidade() {
  }

  /**
   * Lê a mensagem contra o último par válido.
   *
   * @param mensagem mensagem nova
   * @param par      último par válido
   * @return o tipo
   */
  public static Tipo ler(String mensagem, ParConferido par) {
    String normal = TextoContinuidade.normalizar(mensagem);
    if (normal.isEmpty()) {
      return Tipo.NENHUMA;
    }
    if (confirmacao(normal, mensagem)) {
      return Tipo.CONFIRMACAO;
    }
    Matcher naoInicial = NAO_COM_PONTUACAO.matcher(mensagem);
    String resto = null;
    for (String marcador : MARCADORES) {
      if (normal.equals(marcador) || normal.startsWith(marcador + " ")) {
        resto = normal.substring(marcador.length()).strip();
        break;
      }
    }
    if (resto == null && naoInicial.find()) {
      resto = normal.startsWith("nao") ? normal.substring(3).strip() : normal;
    }
    if (resto == null) {
      return Tipo.NENHUMA;
    }
    boolean depoisDeLei = par.carga().rota() == Intencao.LEGISLACAO
        || (par.carga().rota() == Intencao.MISTO && par.carga().lei() != null);
    List<String> w = restante(resto, depoisDeLei);
    if (w.isEmpty()) {
      return Tipo.CONTESTACAO_PURA;
    }
    List<String> pergunta = TextoContinuidade.palavras(
        TextoContinuidade.normalizar(par.pergunta()));
    boolean reafirma = w.stream().allMatch(p -> TextoContinuidade.contemPorPrefixo(pergunta, p));
    return reafirma ? Tipo.REAFIRMACAO : Tipo.CONTESTACAO_CONTEUDO;
  }

  /** A mensagem inteira é uma confirmação. */
  static boolean confirmacao(String normal, String mensagem) {
    if (TEM_CERTEZA.matcher(normal).matches() || CONFIRMACOES.contains(normal)) {
      return true;
    }
    return CONFIRMACOES_COM_INTERROGACAO.contains(normal)
        && ConversaLeve.terminaEmInterrogacao(mensagem);
  }

  /**
   * W: o que sobra da contestação sem o marcador, as palavras vazias e as exclusões; depois de um
   * par de lei, também sem números, percentuais e palavras de polaridade e de juízo.
   */
  static List<String> restante(String resto, boolean depoisDeLei) {
    List<String> w = new ArrayList<>();
    for (String palavra : TextoContinuidade.palavras(resto)) {
      if (TextoContinuidade.VAZIAS.contains(palavra) || EXCLUSOES.contains(palavra)
          || TextoContinuidade.MARCADORES.contains(palavra)
          || TextoContinuidade.verboDePedido(palavra)
          || TextoContinuidade.ANAFORAS.contains(palavra)
          || TextoContinuidade.DEMONSTRATIVOS.contains(palavra)) {
        continue;
      }
      if (depoisDeLei && (POLARIDADE.contains(palavra) || TextoContinuidade.numero(palavra)
          || palavra.equals("por") || palavra.equals("cento"))) {
        continue;
      }
      if (!TextoContinuidade.numero(palavra) && palavra.length() < 3) {
        continue;
      }
      w.add(palavra);
    }
    return w;
  }
}

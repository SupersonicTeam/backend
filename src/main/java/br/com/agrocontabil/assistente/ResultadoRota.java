package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.fallback.TelaAtalho;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;

/**
 * Resposta de uma rota da Ceres (issues #142, #144, #156 e #146).
 *
 * @param texto    texto a devolver ao usuário
 * @param desfecho como a pergunta terminou; fora de {@link DesfechoInteracao#RESPONDIDA} a resposta
 *                 do modelo foi descartada (ou nem houve modelo) e o texto é do backend — nesse
 *                 caso as fontes de dados não são exibidas: nada da resposta veio delas
 * @param citacoes trechos de lei citados (rota de legislação ou fallback); vazia na rota de dados
 * @param atalhos  telas sugeridas no lugar da resposta (só no modo fallback da parte de dados)
 */
public record ResultadoRota(
    String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes,
    List<TelaAtalho> atalhos, DetalheLei detalheLei) {

  /** Cópias defensivas. */
  public ResultadoRota {
    citacoes = citacoes == null ? List.of() : List.copyOf(citacoes);
    atalhos = atalhos == null ? List.of() : List.copyOf(atalhos);
  }

  /** Resultado sem atalhos. */
  public ResultadoRota(String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes) {
    this(texto, desfecho, citacoes, List.of());
  }

  /**
   * Resultado sem o detalhe de lei (#499): todas as rotas, menos a lei aceita.
   *
   * @param texto    texto
   * @param desfecho desfecho
   * @param citacoes citações
   * @param atalhos  telas sugeridas
   */
  public ResultadoRota(String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes,
      List<TelaAtalho> atalhos) {
    this(texto, desfecho, citacoes, atalhos, null);
  }

  /** Resposta do modelo aprovada, sem citação (rota de dados). */
  public static ResultadoRota aceita(String texto) {
    return new ResultadoRota(texto, DesfechoInteracao.RESPONDIDA, List.of());
  }

  /** Resposta do modelo aprovada, com as citações validadas (rota de legislação). */
  public static ResultadoRota aceita(String texto, List<CitacaoLegal> citacoes) {
    return new ResultadoRota(texto, DesfechoInteracao.RESPONDIDA, citacoes);
  }

  /**
   * Resposta do modelo aprovada com a negativa sem recorte trocada pela frase do backend (#274).
   * Conta como aceita: as fontes são as da resposta.
   */
  public static ResultadoRota comNegativaSubstituida(String texto) {
    return new ResultadoRota(texto, DesfechoInteracao.NEGATIVA_SUBSTITUIDA, List.of());
  }

  /** Resposta do modelo descartada; {@code mensagem} é texto fixo do backend. */
  public static ResultadoRota descartada(String mensagem, DesfechoInteracao motivo) {
    return new ResultadoRota(mensagem, motivo, List.of());
  }

  /**
   * Resposta montada sem modelo (issue #146): texto fixo do backend, trechos de lei verbatim e/ou
   * atalhos para as telas.
   */
  public static ResultadoRota fallback(
      String texto, List<CitacaoLegal> citacoes, List<TelaAtalho> atalhos) {
    return new ResultadoRota(texto, DesfechoInteracao.MODO_FALLBACK, citacoes, atalhos);
  }

  /** {@code true} se a resposta do modelo foi aprovada (inclusive com a negativa trocada). */
  public boolean aceita() {
    return desfecho == DesfechoInteracao.RESPONDIDA
        || desfecho == DesfechoInteracao.NEGATIVA_SUBSTITUIDA;
  }

  /**
   * {@code true} se o texto do modelo foi descartado mas há trechos de lei citados a mostrar (issues
   * #170 e #258): o texto é a frase fixa da rota de lei e as citações seguem na resposta.
   */
  public boolean soTrechos() {
    return !aceita() && !emFallback() && !citacoes.isEmpty();
  }

  /** {@code true} se a parte foi montada sem modelo — o texto é útil e deve ser entregue. */
  public boolean emFallback() {
    return desfecho == DesfechoInteracao.MODO_FALLBACK;
  }
}

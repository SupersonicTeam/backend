package br.com.agrocontabil.assistente;

/**
 * Resposta de uma rota da Ceres (issue #142).
 *
 * @param texto  texto a devolver ao usuário
 * @param aceita {@code false} quando a resposta do modelo foi descartada (número sem fonte, teto de
 *               rodadas, função fora da lista) e o texto é uma mensagem fixa — nesse caso as
 *               fontes de dados não são exibidas: nada da resposta veio delas
 */
public record ResultadoRota(String texto, boolean aceita) {

  /** Resposta do modelo aprovada. */
  public static ResultadoRota aceita(String texto) {
    return new ResultadoRota(texto, true);
  }

  /** Resposta do modelo descartada; {@code mensagem} é texto fixo do backend. */
  public static ResultadoRota descartada(String mensagem) {
    return new ResultadoRota(mensagem, false);
  }
}

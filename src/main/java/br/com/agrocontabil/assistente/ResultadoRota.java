package br.com.agrocontabil.assistente;

import br.com.agrocontabil.model.DesfechoInteracao;

/**
 * Resposta de uma rota da Ceres (issues #142 e #144).
 *
 * @param texto    texto a devolver ao usuário
 * @param desfecho como a pergunta terminou; fora de {@link DesfechoInteracao#RESPONDIDA} a resposta
 *                 do modelo foi descartada e o texto é uma mensagem fixa do backend — nesse caso as
 *                 fontes de dados não são exibidas: nada da resposta veio delas
 */
public record ResultadoRota(String texto, DesfechoInteracao desfecho) {

  /** Resposta do modelo aprovada. */
  public static ResultadoRota aceita(String texto) {
    return new ResultadoRota(texto, DesfechoInteracao.RESPONDIDA);
  }

  /** Resposta do modelo descartada; {@code mensagem} é texto fixo do backend. */
  public static ResultadoRota descartada(String mensagem, DesfechoInteracao motivo) {
    return new ResultadoRota(mensagem, motivo);
  }

  /** {@code true} se a resposta do modelo foi aprovada. */
  public boolean aceita() {
    return desfecho == DesfechoInteracao.RESPONDIDA;
  }
}

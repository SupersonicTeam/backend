package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;

/**
 * Resposta de uma rota da Ceres (issues #142, #144 e #156).
 *
 * @param texto    texto a devolver ao usuário
 * @param desfecho como a pergunta terminou; fora de {@link DesfechoInteracao#RESPONDIDA} a resposta
 *                 do modelo foi descartada e o texto é uma mensagem fixa do backend — nesse caso as
 *                 fontes de dados não são exibidas: nada da resposta veio delas
 * @param citacoes trechos de lei citados (rota de legislação); vazia na rota de dados
 */
public record ResultadoRota(String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes) {

  /** Cópia defensiva das citações. */
  public ResultadoRota {
    citacoes = citacoes == null ? List.of() : List.copyOf(citacoes);
  }

  /** Resposta do modelo aprovada, sem citação (rota de dados). */
  public static ResultadoRota aceita(String texto) {
    return new ResultadoRota(texto, DesfechoInteracao.RESPONDIDA, List.of());
  }

  /** Resposta do modelo aprovada, com as citações validadas (rota de legislação). */
  public static ResultadoRota aceita(String texto, List<CitacaoLegal> citacoes) {
    return new ResultadoRota(texto, DesfechoInteracao.RESPONDIDA, citacoes);
  }

  /** Resposta do modelo descartada; {@code mensagem} é texto fixo do backend. */
  public static ResultadoRota descartada(String mensagem, DesfechoInteracao motivo) {
    return new ResultadoRota(mensagem, motivo, List.of());
  }

  /** {@code true} se a resposta do modelo foi aprovada. */
  public boolean aceita() {
    return desfecho == DesfechoInteracao.RESPONDIDA;
  }
}

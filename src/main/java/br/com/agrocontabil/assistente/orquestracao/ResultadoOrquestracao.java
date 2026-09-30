package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;

/**
 * Resposta final do orquestrador da Ceres (issue #157).
 *
 * @param rota          camada que respondeu
 * @param texto         texto já validado (números e citações conferidos pelas rotas)
 * @param desfecho      como a pergunta terminou (auditoria)
 * @param citacoes      trechos de lei citados
 * @param exibirFontes  {@code true} se as fontes de dados podem aparecer — só quando a parte de
 *                      dados foi aceita
 */
public record ResultadoOrquestracao(
    Intencao rota, String texto, DesfechoInteracao desfecho, List<CitacaoLegal> citacoes,
    boolean exibirFontes) {

  /** Cópia defensiva das citações. */
  public ResultadoOrquestracao {
    citacoes = citacoes == null ? List.of() : List.copyOf(citacoes);
  }
}

package br.com.agrocontabil.assistente.continuidade;

import java.util.Objects;

/**
 * Um par do histórico que passou na conferência (#499).
 *
 * @param pergunta         pergunta considerada do par
 * @param resposta         resposta entregue (inteira, como o cliente a devolveu)
 * @param assinatura       assinatura recebida (devolvida igual nos textos fixos de lei)
 * @param carga            carga autenticada
 * @param posicao          posição no {@code historico} recebido (desempate do {@code exp})
 * @param foraDaReescrita  a resposta tem cara de injeção: o par não vai ao prompt da reescrita
 */
public record ParConferido(String pergunta, String resposta, String assinatura, CargaTurno carga,
    int posicao, boolean foraDaReescrita) {

  /** Valida. */
  public ParConferido {
    Objects.requireNonNull(pergunta, "pergunta");
    Objects.requireNonNull(resposta, "resposta");
    Objects.requireNonNull(assinatura, "assinatura");
    Objects.requireNonNull(carga, "carga");
  }

  /** Sem o texto: o par pode acabar em log por engano. */
  @Override
  public String toString() {
    return "ParConferido[rota=" + carga.rota() + ", posicao=" + posicao + "]";
  }
}

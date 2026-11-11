package br.com.agrocontabil.assistente.continuidade;

import java.util.List;
import java.util.Optional;

/**
 * O histórico depois da conferência (#499): os pares válidos, do mais antigo para o mais recente
 * (pelo {@code exp} e, no empate, pela posição), e o que a conferência fez.
 *
 * @param pares     pares válidos, em ordem
 * @param desfechos desfechos da conferência, para o log
 */
public record HistoricoConferido(List<ParConferido> pares, List<DesfechoContinuidade> desfechos) {

  /** Histórico vazio (continuidade desligada, nada recebido). */
  public static final HistoricoConferido VAZIO = new HistoricoConferido(List.of(), List.of());

  /** Normaliza. */
  public HistoricoConferido {
    pares = List.copyOf(pares);
    desfechos = List.copyOf(desfechos);
  }

  /** Histórico inteiro ignorado. */
  static HistoricoConferido ignorado() {
    return new HistoricoConferido(List.of(), List.of(DesfechoContinuidade.HISTORICO_IGNORADO));
  }

  /** {@code true} sem nenhum par válido. */
  public boolean vazio() {
    return pares.isEmpty();
  }

  /** O par mais recente. */
  public Optional<ParConferido> ultimo() {
    return pares.isEmpty() ? Optional.empty() : Optional.of(pares.get(pares.size() - 1));
  }

  /** Sem o texto. */
  @Override
  public String toString() {
    return "HistoricoConferido[pares=" + pares.size() + ", desfechos=" + desfechos + "]";
  }
}

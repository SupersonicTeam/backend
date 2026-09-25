package br.com.agrocontabil.service;

import java.util.List;
import java.util.UUID;

/**
 * Resultado de uma operação de lote da apuração executada pelo job de fechamento (issue #100).
 *
 * <p>Cada item do lote (um produtor no fechamento, uma apuração no refechamento) roda em transação
 * própria. Uma falha é registrada aqui e não interrompe os demais itens.
 *
 * <p>Uso interno do job: não é DTO de API.
 *
 * @param concluidas quantidade de itens processados com sucesso (transação commitada)
 * @param falhas     IDs dos itens que falharam: IDs de produtor no fechamento, IDs de apuração no
 *                   refechamento
 */
public record ResumoLoteApuracao(int concluidas, List<UUID> falhas) {

  /** Cópia defensiva: o resumo é imutável depois de montado. */
  public ResumoLoteApuracao {
    falhas = List.copyOf(falhas);
  }

  /** Total de itens processados, com e sem sucesso. */
  public int total() {
    return concluidas + falhas.size();
  }

  /** Indica se ao menos um item do lote falhou. */
  public boolean houveFalha() {
    return !falhas.isEmpty();
  }
}

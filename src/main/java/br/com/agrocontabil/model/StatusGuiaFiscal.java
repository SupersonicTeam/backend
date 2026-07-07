package br.com.agrocontabil.model;

/**
 * Status do ciclo de vida de uma {@link GuiaFiscal} (issue #74).
 *
 * <p>Inicia em {@link #PENDENTE}; transita para {@link #PAGA} quando o produtor marca a guia
 * como paga, ou para {@link #VENCIDA} quando o {@code GuiaFiscalStatusSyncJob} detecta que a
 * {@code dataVencimento} passou sem pagamento. {@link #PAGA} é terminal — não volta a
 * {@link #VENCIDA} mesmo que o pagamento tenha ocorrido após o vencimento.
 */
public enum StatusGuiaFiscal {
  PENDENTE,
  PAGA,
  VENCIDA
}

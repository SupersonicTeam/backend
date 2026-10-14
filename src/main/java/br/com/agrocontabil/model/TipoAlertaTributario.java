package br.com.agrocontabil.model;

/**
 * O que um {@link AlertaTributario} avisa (issue #140, RN010). Cada tipo aponta para um recurso do
 * próprio produtor ({@code referenciaId}).
 */
public enum TipoAlertaTributario {
  /** Guia fiscal pendente vencendo (referência: a guia). */
  VENCIMENTO_GUIA,
  /** Fechamento automático da apuração do mês (referência: a apuração em aberto). */
  FECHAMENTO_APURACAO,
  /** Fim do prazo de 30 dias para cancelar uma NF-e autorizada, RN006 (referência: a nota). */
  PRAZO_CANCELAMENTO_NFE
}

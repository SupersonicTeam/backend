package br.com.agrocontabil.assistente.fallback;

/**
 * Telas do AgroContabil sugeridas quando a Ceres está sem modelo e a pergunta é sobre dados
 * (issue #146). O frontend mapeia cada valor para a rota dele — o backend não conhece URLs do
 * Angular.
 */
public enum TelaAtalho {
  /** Notas fiscais (vendas e compras). */
  NOTAS,
  /** Apuração mensal de IBS e CBS. */
  APURACAO,
  /** Créditos de IBS e CBS das compras. */
  CREDITOS,
  /** Calendário fiscal: guias e vencimentos. */
  CALENDARIO
}

package br.com.agrocontabil.model;

/** Ação registrada no histórico de revisão da nota (issue #178). */
public enum AcaoRevisao {
  /** O produtor enviou para revisão. */
  ENVIADA,
  /** O produtor retirou da revisão (volta a rascunho). */
  RETIRADA,
  /** O contador aprovou. */
  APROVADA,
  /** O contador devolveu, com motivo. */
  DEVOLVIDA,
  /** A geração do XML de uma nota aprovada falhou: a aprovação caiu. */
  APROVACAO_CADUCADA
}

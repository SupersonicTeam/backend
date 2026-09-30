package br.com.agrocontabil.model;

/**
 * Como terminou uma pergunta à Ceres (issue #144). Alimenta a auditoria e a avaliação do TCC.
 */
public enum DesfechoInteracao {
  /** Resposta do modelo aprovada e entregue. */
  RESPONDIDA,
  /** Resposta descartada: tinha número ou data que não veio de nenhuma função. */
  NUMERO_SEM_FONTE,
  /** O modelo pediu funções além do teto de rodadas. */
  SEM_CONCLUSAO,
  /**
   * O modelo pediu uma função fora da lista de funções da rota — alucinação ou prompt injection.
   * Nada foi executado. Desfecho próprio para ser detectável na auditoria.
   */
  FUNCAO_NAO_PERMITIDA,
  /** O modelo não redigiu texto. */
  SEM_RESPOSTA,
  /** O modelo não respondeu (fora do ar, timeout, recusa do provedor). */
  MODELO_INDISPONIVEL,
  /** Falha inesperada do backend. */
  ERRO
}

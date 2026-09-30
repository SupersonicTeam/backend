package br.com.agrocontabil.model;

/**
 * Como terminou uma pergunta à Ceres (issue #144). Alimenta a auditoria e a avaliação do TCC.
 */
public enum DesfechoInteracao {
  /** Resposta do modelo aprovada e entregue. */
  RESPONDIDA,
  /** Resposta descartada: tinha número ou data que não veio de nenhuma função. */
  NUMERO_SEM_FONTE,
  /** O modelo pediu funções além do teto ou uma função fora da lista. */
  SEM_CONCLUSAO,
  /** O modelo não redigiu texto. */
  SEM_RESPOSTA,
  /** O modelo não respondeu (fora do ar, timeout, recusa do provedor). */
  MODELO_INDISPONIVEL,
  /** Falha inesperada do backend. */
  ERRO
}

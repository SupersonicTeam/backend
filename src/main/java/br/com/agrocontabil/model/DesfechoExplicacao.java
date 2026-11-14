package br.com.agrocontabil.model;

/**
 * Como terminou a explicação curta do modelo depois da resposta de dados (#268, com o que era da
 * #415). Vai à auditoria ({@code assistente_interacao.desfecho_explicacao}, changeSet 0064), sem o
 * texto (#144). Coluna nula = a explicação não se aplicou: flag desligada, outra rota, resposta de
 * dados descartada, com citação de lei, em modo fallback ou pergunta sem modelo (atalho, conversa
 * leve).
 *
 * <p>Toda explicação descartada some sem aviso: o produtor recebe só a resposta do template, que
 * já é completa e correta sozinha.
 */
public enum DesfechoExplicacao {

  /** Passou por todas as conferências do {@code VerificadorFidelidade} e foi entregue. */
  EXPLICACAO_ACEITA,

  /** Número que não está no texto do template, ou número do template alterado. */
  EXPLICACAO_DESCARTADA_NUMERO,

  /** Fora do tamanho ({@code app.assistente.explicacao.*}): frases, caracteres ou parágrafos. */
  EXPLICACAO_DESCARTADA_TAMANHO,

  /** Sigla do glossário sem explicação no texto dela nem no da resposta. */
  EXPLICACAO_DESCARTADA_SIGLA,

  /** Conclusão legal sem fonte ("você tem direito", "é isento", "não precisa pagar", lei). */
  EXPLICACAO_DESCARTADA_CONCLUSAO_LEGAL,

  /** Pedaço da instrução de sistema, rótulo do prompt ou nome interno de função no texto. */
  EXPLICACAO_DESCARTADA_VAZAMENTO,

  /** A geração parou no teto de tokens ({@code app.assistente.tetos.explicacao}, #270). */
  EXPLICACAO_DESCARTADA_CORTADA,

  /** Passou do prazo da explicação ({@code app.assistente.explicacao.timeout}). */
  EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO,

  /** Sem saldo na cota da semana para a explicação: o template sai sozinho, sem 429. */
  EXPLICACAO_DESCARTADA_SEM_SALDO,

  /** O modelo falhou ou está em pausa pelo disjuntor (#146). */
  EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL,

  /** O cliente saiu durante a explicação. */
  EXPLICACAO_DESCARTADA_CANCELADA,

  /** Falha inesperada do backend na explicação (a resposta já entregue não muda). */
  EXPLICACAO_DESCARTADA_ERRO
}

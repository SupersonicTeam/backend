package br.com.agrocontabil.assistente.continuidade;

/**
 * O que a continuidade fez numa pergunta (#499), um valor por caminho, guarda e conferência. Vai ao
 * log em {@code INFO} só como enum: nenhum texto do histórico, da reescrita nem da reconferência.
 * O motivo da reconferência de lei, mais fino que este enum, vai à auditoria como
 * {@code MotivoReconferenciaLei} (changeSet 0066, #584); as outras colunas ficam para depois da
 * banca.
 */
public enum DesfechoContinuidade {
  /** MAC, formato, codificação, rota, faixa ou função fora da lista: o histórico inteiro saiu. */
  HISTORICO_IGNORADO,
  /** Um par vencido saiu; os outros ficaram. */
  PAR_VENCIDO,
  /** Um par com pergunta de cara de injeção saiu. */
  PAR_COM_INJECAO,
  /** A resposta de um par tem cara de injeção: ele sai só do prompt da reescrita. */
  PAR_FORA_DA_REESCRITA,
  /** Par repetido contado uma vez. */
  PAR_REPETIDO,
  /** Confirmação respondida sem o modelo (reexecução ou texto fixo). */
  CONFIRMACAO_SEM_MODELO,
  /** Confirmação respondida com o modelo (consulta que não bate). */
  CONFIRMACAO_COM_MODELO,
  /** Gatilho da reescrita falso: vale a mensagem. */
  CONTINUIDADE_DISPENSADA,
  /** Disjuntor aberto, reescrita cortada ou modelo fora: vale a mensagem. */
  CONTINUIDADE_SEM_MODELO,
  /** Reescrita igual à mensagem. */
  CONTINUIDADE_MANTIDA,
  /** Reescrita aplicada. */
  CONTINUIDADE_APLICADA,
  /** Reescrita descartada pela forma (resposta, você, link, marcador, cópia de resposta). */
  DESCARTADA_FORMATO,
  /** Reescrita com cara de injeção. */
  DESCARTADA_INJECAO,
  /** Reescrita com palavra ou número de um texto livre de terceiro. */
  DESCARTADA_TERCEIRO,
  /** Reescrita com número que não está nas perguntas. */
  DESCARTADA_NUMERO,
  /** Reescrita com período fora das perguntas ou diferente. */
  DESCARTADA_PERIODO,
  /** Reescrita sem uma palavra de conteúdo da mensagem. */
  DESCARTADA_CONTEUDO,
  /** Reescrita sem âncora no referente. */
  DESCARTADA_ANCORA,
  /** Reexecução com o mesmo resultado. */
  RESULTADO_IGUAL,
  /** Reexecução com resultado diferente (dados mudaram ou o dia virou). */
  RESULTADO_MUDOU,
  /** A consulta anterior não batia e a nova corrigiu o resultado. */
  CORRIGIDA,
  /** Consulta refeita (sem afirmar erro). */
  REFEITA,
  /** Reexecução que falhou: texto fixo, sem turno. */
  REEXECUCAO_FALHOU,
  /** Julgamento de produto (#591): a pergunta fala de outro produto que a consulta anterior. */
  JULGAMENTO_PRODUTO_OUTRO,
  /** Julgamento de produto (#591): mesmo produto, incerto, falha ou corte; sem prova de erro. */
  JULGAMENTO_PRODUTO_SEM_PROVA,
  /** Reconferência por texto fixo, sem o modelo. */
  RECONFERENCIA_TEXTO_FIXO,
  /** Reconferência confirmou a resposta anterior. */
  RECONFERENCIA_CONFIRMOU,
  /** Reconferência corrigiu um ou dois pontos. */
  RECONFERENCIA_CORRIGIU,
  /**
   * O modelo indicou uma correção e as guardas do trecho passaram: aviso fixo do backend de que a
   * resposta anterior não confere com o texto da lei, com o trecho literal (#584); nenhuma frase
   * do modelo vai ao produtor.
   */
  RECONFERENCIA_AVISO_DIVERGENCIA,
  /**
   * O modelo indicou uma correção e a resposta saiu como apoio neutro porque o aviso de
   * divergência está desligado (#584): nenhuma frase do modelo vai ao produtor.
   */
  RECONFERENCIA_APOIO_SEM_CORRECAO,
  /** O modelo disse que os trechos não sustentam: apoio. */
  RECONFERENCIA_NAO_SUSTENTA,
  /** Saída fora do formato ou cortada: apoio. */
  RECONFERENCIA_DESCARTADA_FORMATO,
  /** Citação que não é pedaço literal do trecho: apoio. */
  RECONFERENCIA_DESCARTADA_CITACAO,
  /** Número sem apoio no trecho: apoio. */
  RECONFERENCIA_DESCARTADA_NUMERO,
  /** Frase anterior que não está na resposta: apoio. */
  RECONFERENCIA_DESCARTADA_ANCORA,
  /** Correção que fala dos dados do produtor: apoio. */
  RECONFERENCIA_DESCARTADA_DADOS,
  /** Correção só com regime específico: apoio. */
  RECONFERENCIA_DESCARTADA_REGIME,
  /** Correção sem diferença conferível (número ou sim/não): apoio. */
  RECONFERENCIA_DESCARTADA_DIFERENCA,
  /** O número antigo tinha apoio literal no trecho citado: apoio. */
  RECONFERENCIA_DESCARTADA_APOIO_LITERAL,
  /** A rota de lei normal não concordou com a correção: apoio. */
  RECONFERENCIA_DESCARTADA_CONCORDANCIA,
  /** Modelo fora, disjuntor aberto ou injeção na resposta anterior: apoio. */
  RECONFERENCIA_SEM_MODELO
}

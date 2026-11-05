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
  /** Nenhum trecho de lei vigente acima do limiar de similaridade: o modelo nem é chamado. */
  SEM_FUNDAMENTO_LEGAL,
  /** Resposta de legislação sem marca de citação ou com marca inexistente: descartada. */
  CITACAO_INVALIDA,
  /**
   * MISTO: a parte de legislação falou dos dados do produtor (que ela não vê) e o texto dela foi
   * trocado pelos trechos citados (#170). Desfecho próprio para medir o problema na avaliação.
   */
  AFIRMACAO_SOBRE_DADOS,
  /**
   * Rota de dados: a resposta do modelo dizia que algo não existe ("não tem", "não há"...) sobre uma
   * consulta que voltou vazia, sem dizer o recorte consultado. A negativa foi trocada pela frase
   * fixa do backend com o período e o filtro reais (#274, {@code VerificadorNegativaDados}); o
   * resto da resposta ficou. A resposta é entregue, com as fontes.
   */
  NEGATIVA_SUBSTITUIDA,
  /**
   * A geração parou no teto de tokens da chamada (#270, {@code app.assistente.tetos}): o texto
   * cortado foi descartado, sem nada executado nem afirmado a partir dele. Na lei, saem os trechos
   * mais próximos sem afirmar que respondem. Desfecho próprio para medir se o teto está curto.
   */
  RESPOSTA_CORTADA,
  /** Fora de escopo ou intenção incerta: resposta fixa, sem nova chamada ao modelo (#157). */
  FORA_DE_ESCOPO,
  /** O modelo não respondeu (fora do ar, timeout, recusa do provedor). */
  MODELO_INDISPONIVEL,
  /**
   * Modelo indisponível e resposta montada sem ele (#146): trechos de lei por busca textual e/ou
   * atalhos para as telas. Nenhum texto gerado; a resposta sai com {@code modoFallback: true}.
   */
  MODO_FALLBACK,
  /** O cliente do streaming desconectou antes da resposta; o restante foi abandonado (#157). */
  CANCELADA,
  /**
   * A pergunta passou de {@code app.assistente.timeout-pergunta} e foi interrompida (#157). O
   * cliente recebeu 503 {@code ASSISTENTE_TEMPO_ESGOTADO}.
   */
  TEMPO_ESGOTADO,
  /** Falha inesperada do backend. */
  ERRO
}

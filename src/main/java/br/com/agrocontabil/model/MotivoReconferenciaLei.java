package br.com.agrocontabil.model;

/**
 * Por que a reconferência de lei da continuidade (#499) terminou como terminou. Lista fechada,
 * gravada em {@code assistente_interacao.reconferencia_lei} (changeSet 0066): só o código, nunca
 * texto de pergunta, de resposta, de lei ou do modelo (#144). Nulo fora da reconferência de lei.
 *
 * <p>Os grupos seguem a ordem do código da {@code ReconferenciaLei}: textos fixos, caminhos sem
 * modelo, a saída do modelo, o {@code CONFIRMO} e o {@code CORRIJO}, cada guarda com o seu código.
 */
public enum MotivoReconferenciaLei {

  // --- sucesso ---
  /** O modelo confirmou a resposta anterior e as conferências aceitaram. */
  CONFIRMOU,
  /** O modelo corrigiu um ou dois pontos e todas as guardas, a concordância inclusive, aceitaram. */
  CORRIGIU,

  // --- texto fixo, sem modelo: aponta os dispositivos (ou o texto revisado) sem recuar ---
  /** Atalho, sem fundamento, sem confirmação, só trechos ou já reconferida: texto fixo. */
  TEXTO_FIXO,

  // --- apoio sem chamar o modelo ---
  /** Disjuntor do modelo aberto: apoio. */
  SEM_MODELO_DISJUNTOR,
  /** A resposta anterior não tem dispositivo citado legível na fatia: apoio. */
  SEM_MODELO_SEM_DISPOSITIVO,
  /** A busca (dos citados ou a vetorial) falhou: apoio. Qual delas, em {@code reconferencia_busca}. */
  SEM_MODELO_BUSCA_FALHOU,
  /** Nenhum dispositivo citado foi achado na base vigente: apoio. */
  SEM_MODELO_SEM_TRECHO_CITADO,
  /** A mensagem ao modelo não serializou: apoio. */
  SEM_MODELO_FALHA_JSON,
  /** A chamada ao modelo falhou (provedor fora, timeout): apoio. */
  SEM_MODELO_CHAMADA_FALHOU,

  // --- o modelo respondeu, a saída não serve ---
  /** A saída foi cortada pelo teto de tokens: apoio. */
  CORTADA_PELO_TETO,
  /** A saída não tem um objeto JSON legível: apoio. */
  SAIDA_NAO_E_JSON,
  /** O veredito não é CONFIRMO, CORRIJO nem NAO_SUSTENTA: apoio. */
  VEREDITO_DESCONHECIDO,
  /** O modelo disse que os trechos não sustentam a resposta anterior: apoio. */
  NAO_SUSTENTA,

  /**
   * O modelo indicou uma correção e a resposta saiu como apoio, sem a correção por texto
   * ({@code app.assistente.continuidade.lei-corrijo-por-texto} desligada, o padrão): distingue
   * este caso do apoio por descarte de uma guarda.
   */
  CORRIJO_INDICADO_APOIO,

  // --- CONFIRMO descartado ---
  /** Lista de fundamentos ausente, vazia, com mais de 3 itens ou item fora do formato. */
  CONFIRMO_FORMATO,
  /** Fundamento com marca que não é de trecho citado pela resposta anterior. */
  CONFIRMO_MARCA_NAO_CITADA,
  /** Citação do fundamento que não é pedaço literal do trecho. */
  CONFIRMO_CITACAO_NAO_LITERAL,
  /** Número de uma frase da fatia que não está no trecho que ela cita. */
  CONFIRMO_NUMERO_FATIA,
  /** Número da fatia que não está nas citações, ou citação sem 2 palavras de conteúdo da fatia. */
  CONFIRMO_NUMERO_CITACAO,

  // --- CORRIJO descartado (na ordem das guardas) ---
  /** Lista de correções ausente, vazia ou com mais de 2 itens. */
  CORRIJO_FORMATO_LISTA,
  /** Item sem frase, sem fundamento, frase longa demais ou com cara de injeção. */
  CORRIJO_FORMATO_ITEM,
  /** A marca do fundamento não existe entre os trechos do prompt. */
  CORRIJO_MARCA_INEXISTENTE,
  /** A frase anterior não é pedaço contínuo de 5+ palavras da resposta entregue (âncora). */
  CORRIJO_SEM_ANCORA,
  /** Citação do fundamento que não é pedaço literal do trecho. */
  CORRIJO_CITACAO_NAO_LITERAL,
  /** Frase corrigida sem citação válida ou sem a marca do fundamento. */
  CORRIJO_CITACAO_INVALIDA,
  /** Número da frase corrigida que não está nos trechos marcados nela. */
  CORRIJO_NUMERO,
  /** Frase corrigida que fala dos dados do produtor. */
  CORRIJO_DADOS,
  /** Citações só de regime específico sem o setor na pergunta. */
  CORRIJO_REGIME,
  /** Sem número que muda nem polaridade que muda (nada conferível). */
  CORRIJO_SEM_DIFERENCA,
  /** O número antigo tem apoio literal no trecho que a frase anterior citava. */
  CORRIJO_APOIO_LITERAL,
  /** Concordância: o modelo falhou na rota de lei normal. */
  CORRIJO_CONCORDANCIA_INDISPONIVEL,
  /** Concordância: a rota de lei normal não foi aceita (descartada, cortada, sem detalhe). */
  CORRIJO_CONCORDANCIA_NAO_ACEITA,
  /** Concordância: a polaridade da rota normal difere da correção. */
  CORRIJO_CONCORDANCIA_POLARIDADE,
  /** Concordância: o trecho da correção não está entre os da rota normal. */
  CORRIJO_CONCORDANCIA_SEM_TRECHO,
  /** Concordância: nenhuma frase da rota normal tem o número novo sem o antigo. */
  CORRIJO_CONCORDANCIA_SEM_FRASE
}

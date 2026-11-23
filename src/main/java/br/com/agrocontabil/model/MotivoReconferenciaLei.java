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
   * O modelo indicou uma correção, as guardas do trecho passaram e a resposta foi o aviso fixo de
   * divergência (#584): a resposta anterior não confere com o texto da lei, com o trecho literal.
   */
  CORRIJO_AVISO_DIVERGENCIA,

  /**
   * O modelo indicou uma correção e a resposta saiu como apoio neutro porque o aviso de divergência
   * está desligado ({@code app.assistente.continuidade.lei-aviso-de-divergencia}): nenhuma guarda
   * foi avaliada. Com o aviso ligado, cada guarda que descarta tem o seu código.
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
  /**
   * Número de uma frase da fatia que não está na citação literal do trecho que ela cita (a prova é
   * por frase, não pela união das citações; #584).
   */
  CONFIRMO_NUMERO_CITACAO,
  /**
   * Sentido sem prova literal (#584): frase afirmativa com negação ou restrição na citação, frase
   * que nega sem negação na citação, ou "Sim" inicial com negação ou restrição no trecho citado.
   */
  CONFIRMO_SENTIDO,
  /**
   * Frase sem número cujas palavras de conteúdo o trecho citado e a citação cobrem em menos da
   * metade (#584).
   */
  CONFIRMO_COBERTURA,

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
  /**
   * Sem divergência que o backend verifique: nem número da resposta sem apoio no trecho com outro
   * número na citação, nem Sim/Não que muda (nada conferível).
   */
  CORRIJO_SEM_DIFERENCA,
  /**
   * O número que a resposta anterior afirma no ponto tem apoio literal no trecho que sustenta o
   * veredito: não há aviso (a resposta confere com o texto).
   */
  CORRIJO_APOIO_LITERAL,
  /** O trecho do fundamento do veredito não é um dos que a resposta anterior citou. */
  CORRIJO_TRECHO_NAO_CITADO,
  /** Concordância: o modelo falhou na rota de lei normal. */
  CORRIJO_CONCORDANCIA_INDISPONIVEL,
  /** Concordância: a rota de lei normal não foi aceita (descartada, cortada, sem detalhe). */
  CORRIJO_CONCORDANCIA_NAO_ACEITA,
  /** Concordância: a polaridade da rota normal difere da correção. */
  CORRIJO_CONCORDANCIA_POLARIDADE,
  /** Concordância: o trecho da correção não está entre os da rota normal. */
  CORRIJO_CONCORDANCIA_SEM_TRECHO,
  /** Concordância: nenhuma frase da rota normal tem o número novo sem o antigo. */
  CORRIJO_CONCORDANCIA_SEM_FRASE;

  /** O veredito do modelo que o motivo representa, para ler a medição por grupo (#584). */
  public enum Veredito {
    /** CORRIJO: o aviso, a guarda que o barrou ou o apoio neutro por chave. */
    CORRECAO,
    /** CONFIRMO: a confirmação validada ou a que uma guarda descartou. */
    CONFIRMACAO,
    /** NAO_SUSTENTA. */
    NAO_SUSTENTA,
    /** Sem veredito: texto fixo, caminho sem modelo ou saída que não serve. */
    NENHUM
  }

  /**
   * O grupo do veredito. {@code CORRIGIU} e os {@code CORRIJO_*} são de correção (o aviso
   * emitido, {@link #CORRIJO_AVISO_DIVERGENCIA}, e o barrado por guarda, cada um com o seu
   * código); {@code CONFIRMOU} e os {@code CONFIRMO_*}, de confirmação; os demais, sem veredito.
   */
  public Veredito veredito() {
    if (this == CORRIGIU || name().startsWith("CORRIJO_")) {
      return Veredito.CORRECAO;
    }
    if (this == CONFIRMOU || name().startsWith("CONFIRMO_")) {
      return Veredito.CONFIRMACAO;
    }
    return this == NAO_SUSTENTA ? Veredito.NAO_SUSTENTA : Veredito.NENHUM;
  }
}

package br.com.agrocontabil.common;

/**
 * Catálogo central de códigos de erro estáveis expostos ao cliente no campo
 * {@code errors[].code}. O frontend pode reagir programaticamente por código.
 */
public enum ErrorCode {
  // Genéricos
  VALIDATION_ERROR,
  RESOURCE_NOT_FOUND,
  ILLEGAL_ARGUMENT,
  INTERNAL_ERROR,
  // Nota fiscal
  NOTA_NAO_EDITAVEL,
  NOTA_INVALIDA_EMISSAO,
  XML_GENERATION_ERROR,
  // Assinatura digital
  CERTIFICADO_AUSENTE,
  CERTIFICADO_EXPIRADO,
  CERTIFICADO_INVALIDO,
  ASSINATURA_FALHOU,
  ASSINATURA_INVALIDA,
  // Geração de PDF (DANFE — issue #14)
  /** Falha técnica ao gerar o PDF da DANFE. HTTP 500. */
  DANFE_GENERATION_ERROR,
  /** DANFE indisponível: a nota ainda não gerou o XML. HTTP 409. */
  DANFE_INDISPONIVEL,
  /**
   * Preview da DANFE pedido para nota já emitida (XML gerado em diante): ali vale a DANFE de
   * verdade, {@code GET /api/notas/{id}/pdf} (issue #89). HTTP 409.
   */
  DANFE_PREVIEW_INDISPONIVEL,
  /** XML da NF-e indisponível: a nota existe, mas o XML ainda não foi gerado. HTTP 409. */
  XML_INDISPONIVEL,
  // Relatório de conformidade (Sprint 4 — issue Agrocontabil#32)
  /** Falha técnica ao gerar o PDF do relatório de conformidade. HTTP 500. */
  RELATORIO_GENERATION_ERROR,
  // Integração SEFAZ (Sprint 3 — issue #15)
  /** Falha de comunicação com a SEFAZ (timeout, conexão recusada, I/O). HTTP 502. */
  SEFAZ_COMUNICACAO_FALHOU,
  /** Nota fiscal não está em status XML_GERADO para envio à SEFAZ. HTTP 409. */
  NOTA_NAO_ENVIAVEL,
  /** Nota fiscal ainda não foi transmitida — não há status SEFAZ a consultar. HTTP 409. */
  NOTA_NAO_CONSULTAVEL,
  // Cancelamento de NF-e (Sprint 3 — issue #21, RN006)
  /** Nota fiscal não está em status AUTORIZADA para cancelamento. HTTP 409. */
  NOTA_NAO_CANCELAVEL,
  /** A SEFAZ recusou o evento de cancelamento (cStat fora de 135/155 — issues #84/#96). HTTP 422. */
  CANCELAMENTO_REJEITADO,
  /** Cancelamento fora do prazo legal de 30 dias corridos da emissão (RN006). HTTP 422. */
  CANCELAMENTO_FORA_PRAZO,
  // Reabertura de apuração mensal (Sprint 4 — issue Agrocontabil#42, RN005)
  /** Apuração não está em status FECHADO para reabertura. HTTP 409. */
  APURACAO_NAO_REABRIVEL,
  /** Reabertura fora do limite de 3 meses retroativos (RN005). HTTP 422. */
  REABERTURA_FORA_PRAZO,
  /**
   * Consolidação tentou rebaixar um período FECHADO para ABERTO fora do fluxo de reabertura
   * auditada (RN005). HTTP 409 (issue Agrocontabil#54).
   */
  CONSOLIDACAO_PERIODO_FECHADO,
  // Geração de NFA (Sprint 2 — issue #18)
  /** NFA exige produtor pessoa física; o produtor autenticado não é PF. HTTP 422. */
  NFA_PRODUTOR_NAO_PF,
  /** CPF/CNPJ do emitente inválido (dígito verificador ou formato). HTTP 422. */
  DOCUMENTO_INVALIDO,
  // Importacao de XML (Sprint 2 — issue #19)
  /** XML importado invalido: nao parseavel, sem NFe ou reprovado no XSD. HTTP 422. */
  XML_IMPORTACAO_INVALIDA,
  // Infraestrutura HTTP (Sprint 5 — issue Agrocontabil#66)
  /** Método HTTP não suportado pela rota. HTTP 405. */
  METHOD_NOT_ALLOWED,
  /** Content-Type da requisição não suportado pelo endpoint. HTTP 415. */
  MEDIA_TYPE_NOT_SUPPORTED,
  /** Upload excede o tamanho máximo permitido. HTTP 413. */
  PAYLOAD_TOO_LARGE,
  // Guias fiscais do Contador (issue #74)
  /** Guia fiscal já está no status PAGA. HTTP 409. */
  GUIA_JA_PAGA,
  /**
   * Janela {@code de}/{@code ate} do calendário fiscal inválida: início depois do fim ou maior que
   * o máximo permitido (issue #103). HTTP 400.
   */
  JANELA_CALENDARIO_INVALIDA,
  /**
   * Faixa de {@code GET /api/apuracao} inválida: combinação de parâmetros, faixa invertida, acima
   * de 60 meses ou {@code ultimos} fora de 6/12/36/60 (issue #179). HTTP 400.
   */
  FAIXA_APURACAO_INVALIDA,
  /**
   * Filtro de {@code GET /api/notas} inválido: {@code emitidaDe} depois de {@code emitidaAte} ou
   * ordenação por propriedade fora da lista permitida (issue #174). HTTP 400.
   */
  FILTRO_NOTAS_INVALIDO,
  // Persistência / integridade de dados
  /**
   * Violação de restrição de integridade no banco (unique, chave estrangeira, not-null) — ex.:
   * nota com {@code (produtor, numero, serie, modelo)} ou {@code chave_acesso} já existente.
   * HTTP 409.
   */
  VIOLACAO_INTEGRIDADE,
  /**
   * Duas operacoes concorrentes tentaram alterar o mesmo registro e a versao lida ficou obsoleta
   * (lock otimista via {@code @Version} — issue #95). O cliente resolve recarregando o recurso e
   * repetindo a operacao. HTTP 409.
   */
  CONFLITO_CONCORRENCIA,
  // Identidade / autenticacao (Onda 4 — issue #92)
  /**
   * Requisicao sem identidade de usuario valida: nenhum mecanismo de identidade ativo (fora dos
   * profiles dev/homolog) ou header {@code X-Usuario-Id} que nao resolve. HTTP 401.
   */
  NAO_AUTENTICADO,
  // Autorizacao por papel (Onda 7 — issue #93)
  /**
   * Identidade autenticada, mas com papel que nao pode executar a operacao (ex.: produtor em
   * endpoint exclusivo do contador). HTTP 403 — distinto do anti-IDOR, que responde 404 para nao
   * revelar a existencia do recurso alheio.
   */
  PAPEL_NAO_AUTORIZADO,
  // Credencial e cadastro (Sprint 8 — issue #205)
  /**
   * A senha escolhida não atende a política (tamanho, senha comum, contém o e-mail ou o
   * documento). HTTP 400. A resposta nunca ecoa a senha.
   */
  SENHA_FRACA,
  /**
   * E-mail ou CPF/CNPJ já cadastrado. HTTP 409. A mensagem não diz qual dos dois; a enumeração
   * campo a campo é contida pelo limite de tentativas (issues #208/#150).
   */
  CADASTRO_CONFLITO,
  // Login e JWT (Sprint 8 — issue #206)
  /**
   * E-mail e senha não conferem — inclusive e-mail sem conta e credencial desativada, que
   * respondem igual para não revelar quem tem conta. HTTP 401.
   */
  CREDENCIAIS_INVALIDAS,
  /**
   * O refresh token não renova a sessão: ausente, desconhecido, expirado, revogado ou reusado
   * (issue #207). HTTP 401 — o cliente volta ao login.
   */
  SESSAO_INVALIDA,
  // Forca bruta (Sprint 8 — issue #208)
  /**
   * Limite de tentativas por IP excedido em login, cadastro ou refresh. HTTP 429 com
   * {@code Retry-After}.
   */
  MUITAS_TENTATIVAS,
  // Recuperacao e troca de senha (Sprint 8 — issue #209)
  /** Link de redefinição desconhecido, expirado, já usado ou substituído. HTTP 400. */
  TOKEN_REDEFINICAO_INVALIDO,
  /** Senha atual não confere na troca de senha. HTTP 400. */
  SENHA_ATUAL_INCORRETA,
  // Ceres — assistente fiscal (issue #141)
  /**
   * O modelo de linguagem nao respondeu (Ollama fora do ar, provedor por API recusou, timeout).
   * O restante do sistema segue funcionando; o cliente pode tentar de novo depois. HTTP 503.
   */
  ASSISTENTE_INDISPONIVEL,
  /**
   * A Ceres ja esta processando o maximo de perguntas simultaneas
   * ({@code app.assistente.max-conversas-simultaneas}), ou o produtor ja tem uma pergunta em
   * andamento. HTTP 503 com header {@code Retry-After} (issue #157).
   */
  ASSISTENTE_OCUPADO,
  /**
   * A pergunta passou do tempo maximo ({@code app.assistente.timeout-pergunta}) e foi interrompida.
   * O cliente pode reformular a pergunta ou tentar mais tarde. HTTP 503 (issue #157).
   */
  ASSISTENTE_TEMPO_ESGOTADO,
  /**
   * O titular gastou os créditos da semana da Ceres (issue #166). 429 com {@code Retry-After}
   * até a virada da semana (segunda 00:00, {@code America/Sao_Paulo}).
   */
  LIMITE_USO_ASSISTENTE;

  /** Retorna o código estável (igual ao nome da constante). */
  public String code() {
    return name();
  }
}

package br.com.agrocontabil.assistente.orquestracao;

/** Intenção de uma pergunta à Ceres — decide a camada que responde (issue #157). */
public enum Intencao {
  /** Dados do próprio produtor: valores, notas, apuração, créditos, vencimentos (Tool Calling). */
  DADOS,
  /** Regra da reforma tributária: a lei (RAG com citação). */
  LEGISLACAO,
  /** Precisa dos dados do produtor e da regra legal. */
  MISTO,
  /** Qualquer outro assunto: resposta fixa, sem nova chamada ao modelo. */
  FORA_DE_ESCOPO
}

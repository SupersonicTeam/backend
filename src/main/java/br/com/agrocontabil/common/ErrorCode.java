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
  // Integração SEFAZ (Sprint 3 — issue #15)
  /** Falha de comunicação com a SEFAZ (timeout, conexão recusada, I/O). HTTP 502. */
  SEFAZ_COMUNICACAO_FALHOU,
  /** Nota fiscal não está em status XML_GERADO para envio à SEFAZ. HTTP 409. */
  NOTA_NAO_ENVIAVEL;

  /** Retorna o código estável (igual ao nome da constante). */
  public String code() {
    return name();
  }
}

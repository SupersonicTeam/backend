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
  // Integração SEFAZ (Sprint 3 — issue #15)
  /** Falha de comunicação com a SEFAZ (timeout, conexão recusada, I/O). HTTP 502. */
  SEFAZ_COMUNICACAO_FALHOU,
  /** Nota fiscal não está em status XML_GERADO para envio à SEFAZ. HTTP 409. */
  NOTA_NAO_ENVIAVEL,
  /** Nota fiscal ainda não foi transmitida — não há status SEFAZ a consultar. HTTP 409. */
  NOTA_NAO_CONSULTAVEL;

  /** Retorna o código estável (igual ao nome da constante). */
  public String code() {
    return name();
  }
}

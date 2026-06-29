package br.com.agrocontabil.sefaz;

/**
 * Ambiente de comunicação com a SEFAZ.
 *
 * <p>O valor persiste na coluna {@code sefaz_ambiente} da tabela {@code nota_fiscal}
 * como string. Na Sprint 3, somente {@link #HOMOLOGACAO} é utilizado — a transmissão
 * real para produção exige certificado ICP-Brasil válido (Sprint 7).
 */
public enum SefazEnvironment {

  /**
   * Ambiente de homologação (testes). Padrão da Sprint 3.
   * Notas transmitidas aqui não têm validade fiscal.
   */
  HOMOLOGACAO,

  /**
   * Ambiente de produção. Exige certificado ICP-Brasil real e envio via SOAP+mTLS.
   * Implementação do transporte real na Sprint 7.
   */
  PRODUCAO
}

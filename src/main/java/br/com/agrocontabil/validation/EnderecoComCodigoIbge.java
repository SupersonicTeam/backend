package br.com.agrocontabil.validation;

/**
 * Endereço com código IBGE do município e UF, conferido pelo {@link CodigoIbgeCoerenteComUf}: o
 * do destinatário da nota (issue #88) e o postal do escritório (issue #519).
 */
public interface EnderecoComCodigoIbge {

  /**
   * Código IBGE do município, 7 dígitos.
   *
   * @return o código, ou nulo
   */
  String codigoMunicipioIbge();

  /**
   * Sigla da UF.
   *
   * @return a sigla, ou nulo
   */
  String uf();
}

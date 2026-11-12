package br.com.agrocontabil.model;

/**
 * Como o CNPJ do escritório foi verificado (issue #520). Gravado em
 * {@code escritorio.cnpj_verificacao_metodo}; o CHECK do changeSet 0063 lista os valores, então um
 * método novo (e-mail no domínio do CNPJ, consulta à Receita) entra com changeSet próprio.
 */
public enum MetodoVerificacaoCnpj {

  /**
   * A GWNext conferiu a titularidade fora do sistema e marcou pelo comando do profile
   * {@code verificacao-cnpj} (o único meio na v1).
   */
  MANUAL,

  /**
   * O escritório fictício da demonstração da banca, marcado pelo seed: o CNPJ dele só tem o dígito
   * verificador calculado, não pertence a ninguém.
   */
  DEMONSTRACAO
}

package br.com.agrocontabil.model;

/**
 * Tipo da conta do escritório de contabilidade (issue #519), escolhido no cadastro do contador.
 *
 * <p>Gravado em {@code escritorio.tipo}, nunca derivado do CNPJ.
 */
public enum TipoEscritorio {

  /**
   * Um usuário só: sem equipe e sem endereço próprio. O escritório usa o nome da pessoa e pode
   * virar {@link #EMPRESARIAL} depois, dentro do sistema.
   */
  PESSOAL,

  /** Escritório com CNPJ, que pode ter equipe e endereço próprio ({@code {slug}.agroconia...}). */
  EMPRESARIAL
}

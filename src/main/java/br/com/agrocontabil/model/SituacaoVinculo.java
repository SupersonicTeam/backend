package br.com.agrocontabil.model;

/**
 * Situação do vínculo do escritório com o produtor (issue #177).
 *
 * <ul>
 *   <li>{@code ATIVO} — o único que autoriza o contador sobre os dados do produtor;
 *   <li>{@code SUSPENSO} — pelo escritório: sai do alcance (404), mas a ficha fica e o escritório
 *       reativa sem novo convite;
 *   <li>{@code ENCERRADO} — pelo produtor ou pelo escritório: acesso cortado na hora; voltar exige
 *       novo convite. A linha fica como histórico.
 * </ul>
 */
public enum SituacaoVinculo {
  ATIVO,
  SUSPENSO,
  ENCERRADO
}

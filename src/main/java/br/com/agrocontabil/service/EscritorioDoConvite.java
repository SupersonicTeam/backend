package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Escritorio;

/**
 * Quem convida, no e-mail de convite (da carteira ou da equipe): o nome do escritório e, só se
 * verificado, o CNPJ (issue #520). O nome é texto livre, que o próprio escritório escolhe; o selo
 * mostra o CNPJ verificado ao lado dele, e o convite de escritório não verificado leva o aviso.
 *
 * @param nome           nome do escritório, já limpo para o e-mail (pode ficar vazio)
 * @param cnpjVerificado CNPJ só com dígitos, só quando verificado; nulo = não verificado
 */
public record EscritorioDoConvite(String nome, String cnpjVerificado) {

  /**
   * Quem convida, a partir do escritório.
   *
   * @param escritorio escritório que convida
   * @param nome       nome já limpo para o e-mail
   * @return o remetente do convite
   */
  public static EscritorioDoConvite de(Escritorio escritorio, String nome) {
    return new EscritorioDoConvite(nome,
        escritorio.cnpjVerificado() ? escritorio.getCnpj() : null);
  }

  /**
   * O CNPJ do escritório foi verificado?
   *
   * @return {@code true} se verificado
   */
  public boolean verificado() {
    return cnpjVerificado != null;
  }
}

package br.com.agrocontabil.service;

import java.time.OffsetDateTime;

/**
 * Porta de e-mail do convite da carteira (issue #177), irmã da {@link EnvioEmail} da redefinição
 * de senha e no mesmo modo ({@code app.seguranca.redefinicao.modo-email}).
 *
 * <p>O e-mail não leva token nem link de aceite: aceitar exige estar logado com o e-mail
 * convidado. Assim um e-mail encaminhado ou interceptado não vincula ninguém.
 */
@FunctionalInterface
public interface EnvioConviteCarteira {

  /**
   * Avisa o convidado.
   *
   * @param destinatario e-mail convidado
   * @param escritorio   nome do escritório que convidou
   * @param link         página onde o produtor responde os convites
   * @param expiraEm     fim da validade do convite (vai no texto como data e hora de Brasília)
   */
  void enviarConvite(String destinatario, String escritorio, String link,
      OffsetDateTime expiraEm);
}

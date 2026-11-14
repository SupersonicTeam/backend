package br.com.agrocontabil.service;

import java.time.OffsetDateTime;

/**
 * Porta de e-mail do convite de equipe (issue #228), no mesmo modo de envio da recuperação de
 * senha. O link leva o token no fragmento ({@code #token=}): não vai a log de servidor nem ao
 * {@code Referer}.
 */
@FunctionalInterface
public interface EnvioConviteEquipe {

  /**
   * Envia o convite.
   *
   * @param destinatario e-mail convidado
   * @param escritorio   nome do escritório e, só se verificado, o CNPJ (#520)
   * @param link         link com o token no fragmento
   * @param expiraEm     fim da validade do convite (vai no texto como data e hora de Brasília)
   */
  void enviarConvite(String destinatario, EscritorioDoConvite escritorio, String link,
      OffsetDateTime expiraEm);
}

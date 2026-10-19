package br.com.agrocontabil.service;

import java.time.Duration;

/**
 * Porta de envio de e-mail (issue #209). A implementação vem do {@code EmailConfig}, conforme
 * {@code app.seguranca.redefinicao.modo-email}: SMTP real, log (só dev) ou desligado.
 */
public interface EnvioEmail {

  /**
   * Envia o link de redefinição de senha.
   *
   * @param destinatario e-mail do titular
   * @param link         link com o token no fragmento
   * @param validade     por quanto tempo o link vale (vai no texto)
   */
  void enviarRedefinicaoSenha(String destinatario, String link, Duration validade);
}

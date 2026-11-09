package br.com.agrocontabil.service;

/**
 * Falha no envio de um e-mail pelo SMTP (issue #460), sem a exceção original: a
 * {@code SendFailedException} e a resposta do servidor trazem o endereço do destinatário, e o log
 * não pode depender só da máscara (LGPD). Leva só o tipo do e-mail e a categoria da falha.
 *
 * <p>Nunca chega ao cliente HTTP: os três envios rodam em segundo plano, depois do 202.
 */
public class EnvioEmailFalhouException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Cria a exceção.
   *
   * @param mensagem tipo do e-mail, categoria e classes da causa, sem endereço
   */
  public EnvioEmailFalhouException(String mensagem) {
    super(mensagem, null, false, false);
  }

  /**
   * Descrição segura para o log de quem chamou o envio: a mensagem desta exceção (sem endereço)
   * ou, para qualquer outra, só o nome da classe.
   *
   * @param falha exceção do envio
   * @return texto para o log
   */
  public static String paraLog(RuntimeException falha) {
    return falha instanceof EnvioEmailFalhouException ? falha.getMessage()
        : falha.getClass().getSimpleName();
  }
}

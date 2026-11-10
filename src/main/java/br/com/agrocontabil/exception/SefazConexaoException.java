package br.com.agrocontabil.exception;

/**
 * Falha de comunicação em que a requisição <strong>comprovadamente não chegou</strong> à SEFAZ
 * (issue #83). HTTP 502.
 *
 * <p>O transporte lança esta exceção só quando a falha acontece antes de o corpo da requisição
 * sair: conexão recusada, nome não resolvido, timeout de conexão, handshake TLS recusado. Nesses
 * casos o fisco não viu nada, então reenviar o mesmo XML ou evento é seguro — é a única falha que
 * o {@link br.com.agrocontabil.sefaz.SefazClient} re-tenta em {@code enviar} e {@code cancelar}.
 *
 * <p>Na dúvida sobre o que aconteceu, o transporte deve lançar
 * {@link SefazRespostaIndeterminadaException}: classificar como conexão uma falha que ocorreu
 * depois do envio reabre a duplicidade que esta hierarquia existe para fechar.
 */
public class SefazConexaoException extends SefazComunicacaoException {

  /** Texto ao cliente ({@code errors[0]} e {@code message} de topo do 5xx, #251). */
  private static final String MENSAGEM_CLIENTE =
      "Não foi possível conectar à SEFAZ. Nada foi transmitido; tente novamente em instantes.";

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original (ex.: {@code ConnectException}); pode ser nula
   */
  public SefazConexaoException(String mensagemTecnica, Throwable cause) {
    super(mensagemTecnica, cause, MENSAGEM_CLIENTE);
  }

  /**
   * Cria a exceção sem causa encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   */
  public SefazConexaoException(String mensagemTecnica) {
    this(mensagemTecnica, null);
  }
}

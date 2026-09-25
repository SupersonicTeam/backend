package br.com.agrocontabil.exception;

/**
 * Falha de comunicação em que a requisição <strong>pode ter sido processada</strong> pela SEFAZ
 * sem que a resposta chegasse (issue #83). HTTP 502.
 *
 * <p>Casos típicos: timeout de leitura, conexão resetada depois do envio, resposta ilegível,
 * HTTP 5xx do webservice. A SEFAZ pode ter autorizado a NF-e (ou registrado o cancelamento) e a
 * resposta se perdeu no caminho. Reenviar às cegas faria o fisco responder duplicidade (cStat 204
 * no envio, 573 no evento) e a nota ficaria rejeitada no sistema e autorizada no fisco.
 *
 * <p>Por isso o {@link br.com.agrocontabil.sefaz.SefazClient} <strong>nunca</strong> re-tenta
 * esta exceção em {@code enviar}/{@code cancelar}: o
 * {@link br.com.agrocontabil.service.SefazService} consulta a situação pela chave de acesso e
 * decide a partir do que o fisco responder.
 */
public class SefazRespostaIndeterminadaException extends SefazComunicacaoException {

  /**
   * Cria a exceção com a causa técnica encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   * @param cause           causa original (ex.: {@code SocketTimeoutException}); pode ser nula
   */
  public SefazRespostaIndeterminadaException(String mensagemTecnica, Throwable cause) {
    super(mensagemTecnica, cause,
        "A SEFAZ nao confirmou o recebimento. A situacao da nota sera conferida pela chave de "
            + "acesso; consulte o status antes de tentar de novo.");
  }

  /**
   * Cria a exceção sem causa encadeada.
   *
   * @param mensagemTecnica detalhe técnico (vai para o log, nunca ao cliente)
   */
  public SefazRespostaIndeterminadaException(String mensagemTecnica) {
    this(mensagemTecnica, null);
  }
}

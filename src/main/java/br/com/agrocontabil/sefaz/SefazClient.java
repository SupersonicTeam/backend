package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.exception.SefazConexaoException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Encapsula o {@link SefazTransport} com política de retry automático.
 *
 * <p>O {@code @Retryable} só funciona via proxy Spring, então o
 * {@link br.com.agrocontabil.service.SefazService} invoca este componente (e não um método
 * privado da própria classe) para que o retry seja efetivamente aplicado. O serviço chama este
 * client <strong>fora</strong> de transação (issue #96): o retry, com seus backoffs e timeouts de
 * rede, não segura conexão do pool nem lock de linha.
 *
 * <p><strong>Retry pela idempotência da operação (issue #83):</strong>
 * <ul>
 *   <li>{@link #enviar} e {@link #cancelar} <strong>não</strong> são idempotentes: reenviar um XML
 *       ou evento que a SEFAZ já processou vira duplicidade (cStat 204/573). Só re-tentam
 *       {@link SefazConexaoException}, a falha em que a requisição comprovadamente não saiu. A
 *       {@link br.com.agrocontabil.exception.SefazRespostaIndeterminadaException} (e a
 *       {@link SefazComunicacaoException} não classificada) sobe na primeira ocorrência, para o
 *       serviço consultar pela chave.</li>
 *   <li>{@link #consultarStatus} é leitura: re-tenta qualquer {@link SefazComunicacaoException}.</li>
 * </ul>
 *
 * <p>Esgotadas as tentativas, a última exceção é propagada ao chamador (HTTP 502). Rejeição pelo
 * fisco não é exceção: retorna {@link SefazRetorno} com situação REJEITADA.
 *
 * <p>Parâmetros de retry configuráveis em {@code app.nfe.sefaz.retry.*}
 * (ver {@link br.com.agrocontabil.config.SefazProperties}).
 */
@Component
public class SefazClient {

  private final SefazTransport transport;

  /** Constrói o client com o transporte ativo (mock na Sprint 3; SOAP real na Sprint 7). */
  public SefazClient(SefazTransport transport) {
    this.transport = transport;
  }

  /**
   * Envia o XML assinado à SEFAZ, com retry <strong>só</strong> em falha de conexão.
   *
   * @param xmlAssinado XML completo e assinado da NF-e (UTF-8)
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return retorno do fisco (autorizada, rejeitada, pendente ou denegada)
   * @throws SefazComunicacaoException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazConexaoException.class,
      maxAttemptsExpression = "${app.nfe.sefaz.retry.max-attempts:3}",
      backoff = @Backoff(delayExpression = "${app.nfe.sefaz.retry.backoff-ms:1000}"))
  public SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente) {
    return transport.enviar(xmlAssinado, chaveAcesso, ambiente);
  }

  /**
   * Consulta o status de uma NF-e na SEFAZ, com retry automático em falha de comunicação.
   *
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return situação atual da NF-e no fisco
   * @throws SefazComunicacaoException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazComunicacaoException.class,
      maxAttemptsExpression = "${app.nfe.sefaz.retry.max-attempts:3}",
      backoff = @Backoff(delayExpression = "${app.nfe.sefaz.retry.backoff-ms:1000}"))
  public SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente) {
    return transport.consultarStatus(chaveAcesso, ambiente);
  }

  /**
   * Solicita o cancelamento de uma NF-e à SEFAZ (issue #21), com retry <strong>só</strong> em
   * falha de conexão, como {@link #enviar}.
   *
   * @param chaveAcesso          chave de acesso de 44 dígitos da NF-e a cancelar
   * @param justificativa        motivo do cancelamento (15 a 255 caracteres)
   * @param protocoloAutorizacao protocolo de autorização da NF-e
   * @param ambiente             ambiente de destino
   * @return retorno do evento de cancelamento (cStat, motivo, protocolo)
   * @throws SefazComunicacaoException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazConexaoException.class,
      maxAttemptsExpression = "${app.nfe.sefaz.retry.max-attempts:3}",
      backoff = @Backoff(delayExpression = "${app.nfe.sefaz.retry.backoff-ms:1000}"))
  public SefazRetorno cancelar(
      String chaveAcesso, String justificativa, String protocoloAutorizacao,
      SefazAmbiente ambiente) {
    return transport.cancelar(chaveAcesso, justificativa, protocoloAutorizacao, ambiente);
  }
}

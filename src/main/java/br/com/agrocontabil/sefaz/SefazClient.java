package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.exception.SefazCommunicationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Encapsula o {@link SefazTransport} com política de retry automático.
 *
 * <p>Isola a chamada de I/O (transporte) numa fronteira própria, fora da transação do
 * {@link br.com.agrocontabil.service.SefazService}: o {@code @Retryable} só funciona via
 * proxy Spring, então o serviço transacional invoca este componente (e não um método
 * privado da própria classe) para que o retry seja efetivamente aplicado.
 *
 * <p>Somente {@link SefazCommunicationException} (falha técnica de comunicação) aciona o
 * retry. Esgotadas as tentativas, a exceção é propagada ao chamador (HTTP 502). Rejeição
 * pelo fisco não é exceção — retorna {@link SefazReturn} com situação REJEITADA.
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
   * Envia o XML assinado à SEFAZ, com retry automático em falha de comunicação.
   *
   * @param xmlAssinado XML completo e assinado da NF-e (UTF-8)
   * @param accessKey key de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return retorno do fisco (autorizada, rejeitada, pendente ou denegada)
   * @throws SefazCommunicationException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazCommunicationException.class,
      maxAttemptsExpression = "${app.nfe.sefaz.retry.max-attempts:3}",
      backoff = @Backoff(delayExpression = "${app.nfe.sefaz.retry.backoff-ms:1000}"))
  public SefazReturn send(String xmlAssinado, String accessKey, SefazEnvironment ambiente) {
    return transport.send(xmlAssinado, accessKey, ambiente);
  }

  /**
   * Consulta o status de uma NF-e na SEFAZ, com retry automático em falha de comunicação.
   *
   * @param accessKey key de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return situação atual da NF-e no fisco
   * @throws SefazCommunicationException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazCommunicationException.class,
      maxAttemptsExpression = "${app.nfe.sefaz.retry.max-attempts:3}",
      backoff = @Backoff(delayExpression = "${app.nfe.sefaz.retry.backoff-ms:1000}"))
  public SefazReturn queryStatus(String accessKey, SefazEnvironment ambiente) {
    return transport.queryStatus(accessKey, ambiente);
  }
}

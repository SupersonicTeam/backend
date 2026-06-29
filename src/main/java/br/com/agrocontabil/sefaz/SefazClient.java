package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.exception.SefazComunicacaoException;
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
 * <p>Somente {@link SefazComunicacaoException} (falha técnica de comunicação) aciona o
 * retry. Esgotadas as tentativas, a exceção é propagada ao chamador (HTTP 502). Rejeição
 * pelo fisco não é exceção — retorna {@link SefazRetorno} com situação REJEITADA.
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
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return retorno do fisco (autorizada, rejeitada, pendente ou denegada)
   * @throws SefazComunicacaoException se a comunicação falhar após esgotar as tentativas
   */
  @Retryable(
      retryFor = SefazComunicacaoException.class,
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
}

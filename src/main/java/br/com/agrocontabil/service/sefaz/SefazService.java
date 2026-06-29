package br.com.agrocontabil.service.sefaz;

/**
 * Abstração da integração com a SEFAZ para eventos da NF-e.
 *
 * <p>Nesta fase existe apenas a implementação mock ({@link MockSefazService}). A
 * integração real (transmissão SOAP, assinatura ICP-Brasil, evento 110111 de
 * cancelamento) será implementada na Sprint 7, sem alterar este contrato.
 */
public interface SefazService {

  /**
   * Solicita o cancelamento de uma NF-e autorizada.
   *
   * @param accessKey key de acesso (44 dígitos) da NF-e
   * @param reason      justificativa do cancelamento (15–255 caracteres)
   * @return result com o protocol e a data/hora do cancelamento
   */
  SefazCancellationResult cancel(String accessKey, String reason);
}

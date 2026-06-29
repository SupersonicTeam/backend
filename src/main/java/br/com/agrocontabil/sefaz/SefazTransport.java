package br.com.agrocontabil.sefaz;

/**
 * Porta de transporte para comunicação com a SEFAZ.
 *
 * <p>Abstrai o protocol de comunicação (SOAP+mTLS com certificado ICP-Brasil) da lógica
 * de negócio. Na Sprint 3, a implementação ativa é {@link MockSefazTransport}, que simula
 * retorno determinístico sem conexão real. O transporte SOAP real será plugado na Sprint 7
 * implementando esta interface sem alterar os serviços que a consomem.
 *
 * <p>Contratos de exceção:
 * <ul>
 *   <li>Falha de comunicação (timeout, conexão recusada, I/O) →
 *       {@link br.com.agrocontabil.exception.SefazCommunicationException} (HTTP 502).
 *       O {@link br.com.agrocontabil.service.SefazService} aplica retry automático.</li>
 *   <li>Rejeição pelo fisco (dados inválidos) → <em>não é exceção</em>: retornar
 *       {@link SefazReturn} com {@link SefazSituation#REJEITADA}.</li>
 * </ul>
 */
public interface SefazTransport {

  /**
   * Envia o XML assinado da NF-e para a SEFAZ no ambiente especificado.
   *
   * @param xmlAssinado  XML completo e assinado da NF-e (UTF-8)
   * @param accessKey  key de acesso de 44 dígitos (usada para log/auditoria)
   * @param ambiente     ambiente de destino (homologação ou produção)
   * @return retorno com situação, código, reason, protocol e data de autorização
   * @throws br.com.agrocontabil.exception.SefazCommunicationException se falhar por comunicação
   */
  SefazReturn send(String xmlAssinado, String accessKey, SefazEnvironment ambiente);

  /**
   * Consulta o status de uma NF-e previamente enviada.
   *
   * <p>Declarado aqui para suportar a issue #16 (consulta de status) sem alterar a interface.
   * O {@link MockSefazTransport} retorna {@link SefazSituation#AUTORIZADA} de forma
   * determinística. O transporte SOAP real implementará a consulta NfeConsultaProtocolo
   * na Sprint 7.
   *
   * @param accessKey key de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return situação atual da NF-e na SEFAZ
   * @throws br.com.agrocontabil.exception.SefazCommunicationException se falhar por comunicação
   */
  SefazReturn queryStatus(String accessKey, SefazEnvironment ambiente);
}

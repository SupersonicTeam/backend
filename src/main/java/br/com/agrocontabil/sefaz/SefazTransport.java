package br.com.agrocontabil.sefaz;

/**
 * Porta de transporte para comunicação com a SEFAZ.
 *
 * <p>Abstrai o protocolo de comunicação (SOAP+mTLS com certificado ICP-Brasil) da lógica
 * de negócio. Na Sprint 3, a implementação ativa é {@link MockSefazTransport}, que simula
 * retorno determinístico sem conexão real. O transporte SOAP real será plugado na Sprint 7
 * implementando esta interface sem alterar os serviços que a consomem.
 *
 * <p>Contratos de exceção:
 * <ul>
 *   <li>Falha de comunicação (timeout, conexão recusada, I/O) →
 *       {@link br.com.agrocontabil.exception.SefazComunicacaoException} (HTTP 502).
 *       O {@link br.com.agrocontabil.service.SefazService} aplica retry automático.</li>
 *   <li>Rejeição pelo fisco (dados inválidos) → <em>não é exceção</em>: retornar
 *       {@link SefazRetorno} com {@link SituacaoSefaz#REJEITADA}.</li>
 * </ul>
 */
public interface SefazTransport {

  /**
   * Envia o XML assinado da NF-e para a SEFAZ no ambiente especificado.
   *
   * @param xmlAssinado  XML completo e assinado da NF-e (UTF-8)
   * @param chaveAcesso  chave de acesso de 44 dígitos (usada para log/auditoria)
   * @param ambiente     ambiente de destino (homologação ou produção)
   * @return retorno com situação, código, motivo, protocolo e data de autorização
   * @throws br.com.agrocontabil.exception.SefazComunicacaoException se falhar por comunicação
   */
  SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente);

  /**
   * Consulta o status de uma NF-e previamente enviada.
   *
   * <p>Declarado aqui para suportar a issue #16 (consulta de status) sem alterar a interface.
   * O {@link MockSefazTransport} retorna {@link SituacaoSefaz#AUTORIZADA} de forma
   * determinística. O transporte SOAP real implementará a consulta NfeConsultaProtocolo
   * na Sprint 7.
   *
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @param ambiente    ambiente de destino
   * @return situação atual da NF-e na SEFAZ
   * @throws br.com.agrocontabil.exception.SefazComunicacaoException se falhar por comunicação
   */
  SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente);

  /**
   * Solicita o cancelamento de uma NF-e autorizada (evento de cancelamento — issue #21).
   *
   * <p>No transporte real (Sprint 7) corresponde ao evento SOAP 110111 (RecepcaoEvento).
   * O {@link MockSefazTransport} retorna sucesso determinístico com {@code cStat="135"}
   * ("Evento registrado e vinculado a NF-e"). O serviço interpreta o sucesso pelo
   * {@code cStat} do retorno (evento vinculado ∈ {135, 155}), sem inflar
   * {@link SituacaoSefaz} com um valor novo.
   *
   * @param chaveAcesso           chave de acesso de 44 dígitos da NF-e a cancelar
   * @param justificativa         motivo do cancelamento (15 a 255 caracteres)
   * @param protocoloAutorizacao  protocolo de autorização da NF-e (obrigatório no evento)
   * @param ambiente              ambiente de destino
   * @return retorno do evento com {@code cStat}, motivo e protocolo do cancelamento
   * @throws br.com.agrocontabil.exception.SefazComunicacaoException se falhar por comunicação
   */
  SefazRetorno cancelar(
      String chaveAcesso, String justificativa, String protocoloAutorizacao,
      SefazAmbiente ambiente);
}

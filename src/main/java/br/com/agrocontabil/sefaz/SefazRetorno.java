package br.com.agrocontabil.sefaz;

import java.time.OffsetDateTime;

/**
 * Resultado do envio ou consulta de uma NF-e junto à SEFAZ.
 *
 * <p>Retornado por {@link SefazTransport}. Imutável por ser {@code record}.
 *
 * @param situacao        resultado semântico da operação
 * @param cStat           código de status da SEFAZ (ex.: "100", "204")
 * @param xMotivo         descrição textual do status (ex.: "Autorizado o uso da NF-e")
 * <p><strong>Consulta de NF-e cancelada</strong> ({@link SituacaoSefaz#CANCELADA}, issue #83):
 * {@code protocolo} e {@code dataAutorizacao} carregam o protocolo e o instante do
 * <em>evento de cancelamento</em> ({@code procEventoNFe}), e não os da autorização. São eles que
 * viram {@code protocoloCancelamento}/{@code dataCancelamento} da nota; o transporte que devolver o
 * {@code nProt} da autorização aqui gravaria o protocolo errado no cancelamento.
 *
 * @param protocolo       número do protocolo de autorização (nulo se rejeitado/pendente); do
 *                        evento de cancelamento quando a situação é CANCELADA
 * @param dataAutorizacao instante da autorização pelo fisco (nulo se não autorizado); do evento de
 *                        cancelamento quando a situação é CANCELADA
 * @param digestValue     {@code digVal} do protocolo: o DigestValue da assinatura do XML que o fisco
 *                        autorizou (issue #223). Só na consulta de NF-e autorizada; nulo quando o
 *                        transporte não o informa. É o que prova que o XML importado é o autorizado,
 *                        e não um com a mesma chave e outro conteúdo
 */
public record SefazRetorno(
    SituacaoSefaz situacao,
    String cStat,
    String xMotivo,
    String protocolo,
    OffsetDateTime dataAutorizacao,
    String digestValue) {

  /** Retorno sem {@code digVal}: envio, cancelamento e consultas que não o trazem. */
  public SefazRetorno(SituacaoSefaz situacao, String cStat, String xMotivo, String protocolo,
      OffsetDateTime dataAutorizacao) {
    this(situacao, cStat, xMotivo, protocolo, dataAutorizacao, null);
  }
}

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
 * @param protocolo       número do protocolo de autorização (nulo se rejeitado/pendente)
 * @param dataAutorizacao instante da autorização pelo fisco (nulo se não autorizado)
 */
public record SefazRetorno(
    SituacaoSefaz situacao,
    String cStat,
    String xMotivo,
    String protocolo,
    OffsetDateTime dataAutorizacao) {}

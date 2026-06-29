package br.com.agrocontabil.sefaz;

import java.time.OffsetDateTime;

/**
 * Resultado do envio ou consulta de uma NF-e junto à SEFAZ.
 *
 * <p>Retornado por {@link SefazTransport}. Imutável por ser {@code record}.
 *
 * @param situation        result semântico da operação
 * @param cStat           código de status da SEFAZ (ex.: "100", "204")
 * @param xMotivo         descrição textual do status (ex.: "Autorizado o uso da NF-e")
 * @param protocol       número do protocol de autorização (nulo se rejeitado/pendente)
 * @param authorizationDate instante da autorização pelo fisco (nulo se não autorizado)
 */
public record SefazReturn(
    SefazSituation situation,
    String cStat,
    String xMotivo,
    String protocol,
    OffsetDateTime authorizationDate) {}

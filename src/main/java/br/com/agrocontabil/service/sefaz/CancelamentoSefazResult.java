package br.com.agrocontabil.service.sefaz;

import java.time.OffsetDateTime;

/**
 * Resultado do pedido de cancelamento junto à SEFAZ.
 *
 * @param protocolo número do protocolo do evento de cancelamento
 * @param dataHora  data/hora do registro do cancelamento
 */
public record CancelamentoSefazResult(String protocolo, OffsetDateTime dataHora) {}

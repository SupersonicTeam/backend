package br.com.agrocontabil.service.sefaz;

import java.time.OffsetDateTime;

/**
 * Resultado do pedido de cancelamento junto à SEFAZ.
 *
 * @param protocol número do protocol do evento de cancelamento
 * @param dateTime  data/hora do registro do cancelamento
 */
public record SefazCancellationResult(String protocol, OffsetDateTime dateTime) {}

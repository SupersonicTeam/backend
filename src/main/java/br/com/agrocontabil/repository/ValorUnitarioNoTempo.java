package br.com.agrocontabil.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@link ValorUnitarioReferencia} com a emissão da nota, para filtrar a janela de cada nota em
 * memória (issue #579, avaliação dos sinais em lote).
 *
 * @param notaId        nota de onde veio
 * @param dataEmissao   emissão da nota
 * @param valorUnitario valor unitário do item
 */
public record ValorUnitarioNoTempo(UUID notaId, OffsetDateTime dataEmissao,
    BigDecimal valorUnitario) {}

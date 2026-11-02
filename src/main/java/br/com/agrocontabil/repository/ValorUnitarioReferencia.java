package br.com.agrocontabil.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Valor unitário de um item numa nota de referência (issue #276, {@code VALOR_UNITARIO_FORA_PADRAO}).
 *
 * @param notaId        nota de onde veio (conta as notas distintas da referência)
 * @param valorUnitario valor unitário do item
 */
public record ValorUnitarioReferencia(UUID notaId, BigDecimal valorUnitario) {}

package br.com.agrocontabil.event;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Publicado quando um XML de NF-e importado (entrada) é persistido já {@code AUTORIZADA} (issue
 * Agrocontabil#64 — RN005/RN004).
 *
 * <p>O crédito de IBS/CBS da apuração mensal só considera entradas {@code AUTORIZADA} — por isso
 * o evento só é disparado quando a nota importada chega nesse status (nfeProc autorizado).
 *
 * @param produtorId produtor destinatário da nota (dono, na importação)
 * @param periodo    mês ({@code America/Sao_Paulo}) de {@code dataEmissao} da nota importada
 */
public record NotaImportadaEvent(UUID produtorId, YearMonth periodo) {}

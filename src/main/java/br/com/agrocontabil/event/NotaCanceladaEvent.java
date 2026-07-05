package br.com.agrocontabil.event;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Publicado quando uma NF-e autorizada é cancelada na SEFAZ (issue Agrocontabil#64 — RN005/RN004).
 *
 * <p>O cancelamento retira a nota do débito da apuração mensal; o período carregado é o da
 * {@code dataEmissao} original (mês cujo débito foi afetado), não o da data do cancelamento.
 *
 * @param produtorId produtor dono da nota
 * @param periodo    mês ({@code America/Sao_Paulo}) de {@code dataEmissao} da nota cancelada
 */
public record NotaCanceladaEvent(UUID produtorId, YearMonth periodo) {}

package br.com.agrocontabil.event;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Publicado quando uma NF-e própria é autorizada pela SEFAZ (issue Agrocontabil#64 — RN005/RN004).
 *
 * <p>O débito de IBS/CBS da apuração mensal só considera saídas {@code AUTORIZADA} — por isso o
 * evento é disparado na transição para esse status ({@link
 * br.com.agrocontabil.service.SefazService#enviar}, não na criação da nota em RASCUNHO), e carrega
 * o período (mês de {@code dataEmissao}) cuja apuração pode precisar de recálculo.
 *
 * @param produtorId produtor dono da nota
 * @param periodo    mês ({@code America/Sao_Paulo}) de {@code dataEmissao} da nota
 */
public record NotaEmitidaEvent(UUID produtorId, YearMonth periodo) {}

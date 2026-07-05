package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.OrigemNotaFiscal;

/**
 * Projeção de agregação do volume de notas autorizadas de um produtor por mês e origem
 * (issue Agrocontabil#65 — Sprint 5). Preenchida pela query
 * {@link NotaFiscalRepository#contarNotasPorMesEorigem}.
 *
 * <p>O recorte por mês vem da extração {@code year()/month()} do HQL (portável entre H2 e
 * PostgreSQL) sobre a {@code dataEmissao}. Cada linha representa a contagem de notas de uma
 * ({@code ano}, {@code mes}, {@code origem}); o serviço distribui as linhas na janela de 6 meses
 * do dashboard, preenchendo com zero os meses sem notas.
 *
 * @param ano        ano da {@code dataEmissao} (ex.: {@code 2026})
 * @param mes        mês da {@code dataEmissao} (1–12)
 * @param origem     origem das notas contadas ({@link OrigemNotaFiscal#PROPRIA} = saída,
 *                   {@link OrigemNotaFiscal#IMPORTADA} = entrada)
 * @param quantidade quantidade de notas autorizadas naquele mês e origem
 */
public record NotasPorMesAgregado(
    int ano,
    int mes,
    OrigemNotaFiscal origem,
    long quantidade) {}

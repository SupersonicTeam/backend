package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Projeção de uma nota consultada pelo número (issue #256): só os campos que a Ceres usa, sem o
 * XML assinado nem os dados do destinatário (minimização, #144).
 *
 * @param id          id interno, para buscar a revisão e a validação (não vai ao modelo)
 * @param numero      número
 * @param serie       série
 * @param dataEmissao emissão
 * @param origem      própria (venda) ou importada (compra)
 * @param status      situação
 * @param valorTotal  valor total da nota
 * @param valorIbs    IBS destacado na nota
 * @param valorCbs    CBS destacada na nota
 */
public record NotaPorNumeroLinha(UUID id, Long numero, Integer serie, OffsetDateTime dataEmissao,
    OrigemNotaFiscal origem, StatusNotaFiscal status, BigDecimal valorTotal, BigDecimal valorIbs,
    BigDecimal valorCbs) {}

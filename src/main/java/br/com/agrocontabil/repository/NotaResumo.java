package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Resumo de uma nota fiscal para a Ceres (issue #142). Minimização por construção (issue #144):
 * nenhum nome, CPF/CNPJ, endereço ou chave de acesso — só o necessário para responder sobre valores
 * e situação.
 *
 * @param numero      número da nota
 * @param serie       série
 * @param dataEmissao emissão
 * @param origem      própria (saída) ou importada (entrada)
 * @param status      situação
 * @param valorTotal  valor total da nota
 * @param valorIbs    IBS da nota; pode ser nulo
 * @param valorCbs    CBS da nota; pode ser nulo
 */
public record NotaResumo(
    Long numero,
    Integer serie,
    OffsetDateTime dataEmissao,
    OrigemNotaFiscal origem,
    StatusNotaFiscal status,
    BigDecimal valorTotal,
    BigDecimal valorIbs,
    BigDecimal valorCbs) {}

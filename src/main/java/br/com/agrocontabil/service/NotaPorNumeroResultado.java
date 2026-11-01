package br.com.agrocontabil.service;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Uma nota consultada pelo número para a Ceres (issue #256), sem dado pessoal: nada do
 * destinatário, do emitente, da chave nem do XML (minimização, #144).
 *
 * @param numero           número
 * @param serie            série
 * @param dataEmissao      emissão
 * @param origem           própria (venda) ou importada (compra)
 * @param status           situação atual
 * @param valorTotal       valor total da nota
 * @param valorIbs         IBS da nota (o débito na venda, o crédito na compra)
 * @param valorCbs         CBS da nota (idem)
 * @param motivoDevolucao  motivo da última devolução pelo contador (#178); nulo se nunca foi
 *                         devolvida
 * @param inconsistencias  as do último lote de validação (#22), só código, campo, mensagem e
 *                         sugestão (o valor rejeitado pode ser dado pessoal e fica fora)
 */
public record NotaPorNumeroResultado(long numero, int serie, OffsetDateTime dataEmissao,
    OrigemNotaFiscal origem, StatusNotaFiscal status, BigDecimal valorTotal, BigDecimal valorIbs,
    BigDecimal valorCbs, String motivoDevolucao, List<Inconsistencia> inconsistencias) {

  /** Cópia defensiva. */
  public NotaPorNumeroResultado {
    inconsistencias = List.copyOf(inconsistencias);
  }

  /**
   * Uma inconsistência do último lote.
   *
   * @param codigo   código do erro
   * @param campo    campo da nota (pode ser nulo)
   * @param mensagem mensagem do sistema
   * @param sugestao como corrigir (pode ser nula)
   */
  public record Inconsistencia(String codigo, String campo, String mensagem, String sugestao) {}
}

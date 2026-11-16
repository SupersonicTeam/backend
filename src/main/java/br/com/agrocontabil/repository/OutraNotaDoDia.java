package br.com.agrocontabil.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outra nota do produtor candidata a duplicidade, com o que as candidatas comparam (issue #579,
 * avaliação dos sinais em lote): o destinatário, o valor e a emissão, para aplicar em memória a
 * mesma regra da consulta por nota.
 *
 * @param id           nota
 * @param numero       número
 * @param serie        série
 * @param destinatario documento do destinatário
 * @param valorTotal   valor total da nota
 * @param dataEmissao  emissão
 */
public record OutraNotaDoDia(UUID id, Long numero, Integer serie, String destinatario,
    BigDecimal valorTotal, OffsetDateTime dataEmissao) {

  /** A nota como o resultado da consulta por nota a devolve. */
  public OutraNota comoOutraNota() {
    return new OutraNota(id, numero, serie);
  }
}

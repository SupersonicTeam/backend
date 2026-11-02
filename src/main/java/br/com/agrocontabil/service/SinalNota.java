package br.com.agrocontabil.service;

import java.time.LocalDate;

/**
 * Sinal de nota possivelmente emitida errada (issue #276): uma suspeita, nunca uma certeza. A
 * Ceres o mostra com frase fixa ("confira com seu contador"), montada pelo backend.
 *
 * @param codigo        qual sinal
 * @param numeroItem    item da nota (nulo num sinal da nota inteira)
 * @param descricaoItem descrição do item, como digitada (vai ao usuário, nunca ao modelo)
 * @param cfop          CFOP do item
 * @param ncm           NCM do item
 * @param prazoCancelamento último dia para cancelar (RN006), só com a nota autorizada dentro do
 *                      prazo; nulo caso contrário
 */
public record SinalNota(CodigoSinalNota codigo, Integer numeroItem, String descricaoItem,
    String cfop, String ncm, LocalDate prazoCancelamento) {

  /** Sinais conhecidos. Sinal novo = código aqui + regra no service + frase fixa na Ceres. */
  public enum CodigoSinalNota {
    /** Item de NF-e de produto com cara de serviço (colheita, frete, aluguel...). */
    PRODUTO_PARECE_SERVICO
  }
}

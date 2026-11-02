package br.com.agrocontabil.assistente.porta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Porta por onde a Ceres lê o tratamento do IBS/CBS de um produto (issue #283, parte 1): a tabela
 * oficial de classificação tributária, a mesma do cálculo da nota. Não é dado do produtor (vale
 * igual para todos), por isso fica fora da {@link DadosProdutorPorta} e não recebe produtor.
 */
public interface TratamentoProdutoPorta {

  /**
   * Tratamento do produto na data.
   *
   * @param termo nome do produto ou NCM de 8 dígitos
   * @param data  data de referência
   * @return o que foi achado; lista vazia se nada casou
   */
  TratamentoConsultado consultar(String termo, LocalDate data);

  /**
   * Resultado da consulta.
   *
   * @param termo        termo consultado
   * @param produtos     produtos achados (no máximo 3)
   * @param versaoTabela versão da tabela oficial; nula sem tabela carregada
   */
  record TratamentoConsultado(String termo, List<Produto> produtos, String versaoTabela) {

    /** Cópia defensiva. */
    public TratamentoConsultado {
      produtos = List.copyOf(produtos);
    }
  }

  /**
   * Tratamento de um produto.
   *
   * @param ncm               NCM de 8 dígitos; nulo num grupo de NCMs de um item de anexo
   * @param descricao         descrição oficial; pode ser nula
   * @param quantidadeNcms    NCMs do grupo
   * @param cClassTrib        classificação tributária aplicada
   * @param percentualReducao redução da alíquota, de 0 a 100
   * @param inNatura          {@code true} se a redução é a do produto in natura (art. 137)
   * @param anexo             número do anexo da LC 214/2025; nulo fora de anexo
   * @param artigo            artigo da LC 214/2025; nulo na tributação integral
   * @param condicao          condição que o dado não decide; nula quando não há
   * @param ncmConhecido      {@code false}: NCM pedido fora da referência e de todo anexo (a
   *                          resposta pede para conferir o código)
   */
  record Produto(String ncm, String descricao, int quantidadeNcms, String cClassTrib,
      BigDecimal percentualReducao, boolean inNatura, Integer anexo, String artigo,
      String condicao, boolean ncmConhecido) {}
}

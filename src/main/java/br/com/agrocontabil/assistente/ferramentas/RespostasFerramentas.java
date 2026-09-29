package br.com.agrocontabil.assistente.ferramentas;

import java.util.List;

/**
 * O que cada função da Ceres devolve ao modelo (issue #142).
 *
 * <p>DTOs próprios e enxutos, nunca entidade. <strong>Minimização (issue #144):</strong> só totais,
 * datas, contagens e listas curtas — nenhum nome, CPF/CNPJ, endereço, chave de acesso ou
 * observação livre. Tudo que está aqui vai ao provedor do modelo, que pode ser uma API externa.
 * Valores já formatados em pt-BR (ver {@link FormatoBr}).
 */
public final class RespostasFerramentas {

  private RespostasFerramentas() {}

  /**
   * Resultado de {@code totalComprasPorInsumo}.
   *
   * @param insumo          termo ou NCM consultado
   * @param criterio        {@code NCM} ou {@code DESCRICAO}
   * @param dataInicio      primeiro dia do período
   * @param dataFim         último dia do período
   * @param valorTotal      soma do valor dos itens comprados
   * @param quantidadeNotas notas de entrada em que o insumo aparece
   * @param porUnidade      quantidade e valor por unidade de medida
   */
  public record ComprasInsumo(
      String insumo, String criterio, String dataInicio, String dataFim, String valorTotal,
      long quantidadeNotas, List<CompraPorUnidade> porUnidade) {}

  /**
   * Compras de um insumo numa unidade de medida.
   *
   * @param unidade    unidade ({@code KG}, {@code L}...)
   * @param quantidade quantidade comprada
   * @param valor      valor comprado nessa unidade
   */
  public record CompraPorUnidade(String unidade, String quantidade, String valor) {}

  /**
   * Resultado de {@code consultarApuracao}.
   *
   * @param mes        mês ({@code AAAA-MM})
   * @param situacao   ABERTO (parcial, pode mudar) ou FECHADO
   * @param debitoIbs  IBS das vendas
   * @param debitoCbs  CBS das vendas
   * @param creditoIbs IBS das compras
   * @param creditoCbs CBS das compras
   * @param saldoIbs   IBS a recolher
   * @param saldoCbs   CBS a recolher
   * @param totalNotas notas consideradas
   */
  public record Apuracao(
      String mes, String situacao, String debitoIbs, String debitoCbs, String creditoIbs,
      String creditoCbs, String saldoIbs, String saldoCbs, long totalNotas) {}

  /**
   * Resultado de {@code consultarCreditos}.
   *
   * @param dataInicio      primeiro dia
   * @param dataFim         último dia
   * @param creditoIbs      crédito de IBS
   * @param creditoCbs      crédito de CBS
   * @param creditoTotal    soma dos dois
   * @param quantidadeNotas notas de entrada consideradas
   */
  public record Creditos(
      String dataInicio, String dataFim, String creditoIbs, String creditoCbs,
      String creditoTotal, long quantidadeNotas) {}

  /**
   * Resultado de {@code listarNotas}.
   *
   * @param totalEncontrado quantas notas atendem ao filtro (pode ser mais que as listadas)
   * @param notas           as mais recentes, no máximo 10
   */
  public record Notas(long totalEncontrado, List<Nota> notas) {}

  /**
   * Uma nota da lista.
   *
   * @param numero      número
   * @param serie       série
   * @param dataEmissao emissão
   * @param tipo        SAIDA (venda) ou ENTRADA (compra)
   * @param situacao    status da nota
   * @param valorTotal  valor total
   * @param valorIbs    IBS
   * @param valorCbs    CBS
   */
  public record Nota(
      long numero, int serie, String dataEmissao, String tipo, String situacao,
      String valorTotal, String valorIbs, String valorCbs) {}

  /**
   * Resultado de {@code proximosVencimentos}.
   *
   * @param de              primeiro dia da janela
   * @param ate             último dia da janela
   * @param totalEncontrado guias não pagas na janela (pode ser mais que as listadas)
   * @param guias           as mais próximas, no máximo 10
   */
  public record Vencimentos(String de, String ate, long totalEncontrado, List<Guia> guias) {}

  /**
   * Uma guia a vencer.
   *
   * @param tipo       tipo da guia (ICMS, FUNRURAL...)
   * @param vencimento vencimento
   * @param valor      valor
   * @param situacao   PENDENTE ou VENCIDA
   */
  public record Guia(String tipo, String vencimento, String valor, String situacao) {}
}

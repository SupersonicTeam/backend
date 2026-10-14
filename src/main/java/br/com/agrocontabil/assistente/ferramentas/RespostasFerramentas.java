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
   * @param observacao      orientacao ao usuario quando nada foi encontrado; nulo caso contrario
   */
  public record ComprasInsumo(
      String insumo, String criterio, String dataInicio, String dataFim, String valorTotal,
      long quantidadeNotas, List<CompraPorUnidade> porUnidade, String observacao) {}

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
   * Resultado de {@code consultarRetencoes} (RN009, issue #145): as duas retenções sobre as vendas.
   *
   * @param dataInicio                primeiro dia
   * @param dataFim                   último dia
   * @param vendasAutorizadas         vendas autorizadas do período
   * @param funruralRetidoPeloComprador vendas a empresa ou cooperativa, com o Funrural retido
   * @param funruralSemRetencao       vendas a pessoa física ou no varejo: sem retenção, o próprio
   *                                  produtor recolhe
   * @param exportacao                vendas ao exterior: imunes, sem Funrural
   * @param splitPaymentIbs           IBS destacado, separado no pagamento
   * @param splitPaymentCbs           CBS destacada, separada no pagamento
   * @param splitPaymentTotal         soma dos dois
   * @param avisos                    avisos fixos do backend (o que o cálculo não sabe)
   */
  public record Retencoes(
      String dataInicio, String dataFim, long vendasAutorizadas,
      Funrural funruralRetidoPeloComprador, Funrural funruralSemRetencao, Funrural exportacao,
      String splitPaymentIbs, String splitPaymentCbs, String splitPaymentTotal,
      List<String> avisos) {}

  /**
   * Funrural de um conjunto de vendas.
   *
   * @param notas       vendas no conjunto
   * @param base        receita bruta (valor dos produtos)
   * @param aliquota    alíquota total
   * @param previdencia parcela de 1,2%
   * @param rat         parcela de 0,1%
   * @param senar       parcela de 0,2%
   * @param total       soma das parcelas
   */
  public record Funrural(long notas, String base, String aliquota, String previdencia,
      String rat, String senar, String total) {}

  /**
   * Resultado de {@code listarNotas}.
   *
   * @param dataInicio        primeiro dia do período consultado (o padrão é 90 dias atrás: a
   *                          resposta tem de dizer o período da soma, issue #169)
   * @param dataFim           último dia do período consultado
   * @param totalEncontrado   quantas notas atendem ao filtro (pode ser mais que as listadas)
   * @param valorTotal        soma calculada pelo backend sobre as notas descritas em
   *                          {@code abrangenciaTotal}, não sobre a lista (issue #169)
   * @param quantidadeNoTotal quantas notas entram em {@code valorTotal} (pode diferir de
   *                          {@code totalEncontrado}: sem filtro de situação só as autorizadas)
   * @param abrangenciaTotal  quais notas entram em {@code valorTotal}, em texto para o modelo
   * @param notas             as mais recentes, no máximo 10
   */
  public record Notas(
      String dataInicio, String dataFim, long totalEncontrado, String valorTotal,
      long quantidadeNoTotal, String abrangenciaTotal, List<Nota> notas) {}

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
   * @param valorTotal      soma de todas as guias não pagas da janela, calculada pelo backend
   *                        (issue #169) — inclusive as que não cabem na lista
   * @param guias           as mais próximas, no máximo 10
   */
  public record Vencimentos(
      String de, String ate, long totalEncontrado, String valorTotal, List<Guia> guias) {}

  /**
   * Resultado de {@code listarAlertas} (issue #140, RN010).
   *
   * @param totalEncontrado alertas ativos (pode ser mais que os listados)
   * @param alertas         os mais urgentes, no máximo 10
   */
  public record Alertas(long totalEncontrado, List<Alerta> alertas) {}

  /**
   * Um alerta tributário.
   *
   * @param tipo          VENCIMENTO_GUIA, FECHAMENTO_APURACAO ou PRAZO_CANCELAMENTO_NFE
   * @param descricao     o que vence ou fecha
   * @param prazo         último dia para agir
   * @param diasRestantes dias até o prazo (0 = hoje)
   */
  public record Alerta(String tipo, String descricao, String prazo, long diasRestantes) {}

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

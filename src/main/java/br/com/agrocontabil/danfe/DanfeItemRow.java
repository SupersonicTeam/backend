package br.com.agrocontabil.danfe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Linha de item da DANFE para o datasource do JasperReports.
 *
 * <p>É um JavaBean clássico (getters {@code getX}) — e não um {@code record} — porque o
 * {@code JRBeanCollectionDataSource} resolve os campos do relatório via
 * {@link java.beans.Introspector}, que reconhece propriedades pelo padrão getter/setter.
 *
 * <p>Todos os valores numéricos já chegam <strong>formatados como String</strong> (o template
 * {@code danfe.jrxml} apenas exibe o texto), centralizando a formatação no
 * {@link DanfePdfService}. Colunas do quadro "Dados dos Produtos/Serviços" do leiaute retrato da
 * NT 2026.010 (item 4.3); as células de várias linhas chegam com {@code \n}.
 */
@Getter
@AllArgsConstructor
public class DanfeItemRow {

  /** Código e descrição do produto, com {@code [NCM ...] [cClassTrib ...]} na linha de baixo. */
  private final String descricao;

  /** CST do IBS/CBS e CFOP, um por linha ({@code CST 200}, {@code CFOP 5101}). */
  private final String cstCfop;

  /** Quantidade e unidade, uma por linha. */
  private final String quantidadeUnidade;

  /** Valor unitário já formatado. */
  private final String valorUnitario;

  /** Valor total do item já formatado. */
  private final String valorTotal;

  /** Base de cálculo do IBS/CBS ({@code vBC}, UB16): o valor do item, sem redução de base. */
  private final String baseIbsCbs;

  /**
   * Alíquotas de CBS, IBS UF e IBS Município, uma por linha: a efetiva ({@code pAliqEfet}) com
   * redução ({@code gRed}), a vigente sem ela (NT 2026.010, item 4.3).
   */
  private final String aliquotas;

  /** Valores de CBS, IBS UF e IBS Município, um por linha. */
  private final String valores;
}

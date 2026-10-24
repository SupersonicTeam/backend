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
 * {@link DanfePdfService}.
 */
@Getter
@AllArgsConstructor
public class DanfeItemRow {

  /** Código do produto informado pelo emitente; sem ele, o número do item. */
  private final String codigo;

  /** Descrição do produto/serviço. */
  private final String descricao;

  /** NCM formatado ({@code 0000.00.00}). */
  private final String ncm;

  /** CFOP da operação do item. */
  private final String cfop;

  /** Unidade comercial (ex.: KG, UN). */
  private final String unidade;

  /** Quantidade já formatada. */
  private final String quantidade;

  /** Valor unitário já formatado. */
  private final String valorUnitario;

  /** Valor total do item já formatado. */
  private final String valorTotal;

  /** Alíquota do IBS em percentual já formatada. */
  private final String aliquotaIbs;

  /** Valor do IBS do item já formatado. */
  private final String valorIbs;

  /** Alíquota da CBS em percentual já formatada. */
  private final String aliquotaCbs;

  /** Valor da CBS do item já formatado. */
  private final String valorCbs;
}

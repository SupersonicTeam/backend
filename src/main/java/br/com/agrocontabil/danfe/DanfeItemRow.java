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

  /** Número sequencial do item na invoice. */
  private final Integer itemNumber;

  /** Descrição do produto/serviço. */
  private final String description;

  /** Unidade comercial (ex.: KG, UN). */
  private final String unit;

  /** Quantidade já formatada. */
  private final String quantity;

  /** Valor unitário já formatado. */
  private final String unitPrice;

  /** Valor total do item já formatado. */
  private final String totalValue;

  /** Valor do IBS do item já formatado. */
  private final String ibsValue;

  /** Valor da CBS do item já formatado. */
  private final String cbsValue;
}

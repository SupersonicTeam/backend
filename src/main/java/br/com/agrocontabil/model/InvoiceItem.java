package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Item (linha de produto/serviço) de uma {@link Invoice}.
 *
 * <p>Mapeado para a tabela {@code nota_fiscal_item}. Cada item pertence a exatamente uma
 * invoice fiscal e é removido em cascata quando a invoice é excluída
 * (gerenciado pelo lado {@link Invoice#getItems()}).
 *
 * <p><strong>Campos IBS/CBS (Sprint 2 – Sessão 4):</strong> adicionados para conformidade com a
 * reforma tributária. Base legal:
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A e 195, III) — institui o IBS (Imposto sobre
 *       Bens e Serviços, de competência estadual/municipal) e a CBS (Contribuição sobre Bens e
 *       Serviços, federal), em substituição gradual ao ICMS, ISS, PIS e COFINS.</li>
 *   <li><strong>LC 214/2025</strong> — regulamenta a apuração, alíquotas, regimes diferenciados
 *       e obrigações acessórias do IBS e da CBS.</li>
 *   <li><strong>Ano-teste de 2026:</strong> alíquotas reduzidas (referência técnica amplamente
 *       citada: CBS 0,9 % e IBS 0,1 %). Os valores são destacados de forma informativa nas
 *       notas fiscais, não gerando recolhimento efetivo nesta fase. As alíquotas efetivas são
 *       parametrizadas em {@code application.yml} (prefix {@code agrocontabil.tributos}) e
 *       nunca fixadas em código.</li>
 *   <li><strong>Producer rural / agropecuária:</strong> a LC 214/2025 prevê regimes
 *       diferenciados e possíveis reduções de base para produtos agropecuários. O field
 *       {@link #reductionPercentage} permite modelar essa redução; o percentual exato por
 *       NCM/produto será refinado em sprint futura conforme regulamentação complementar —
 *       <em>incerteza registrada intencionalmente</em>.</li>
 * </ul>
 *
 * <p>Todos os campos IBS/CBS são nullable para compatibilidade com notas emitidas antes
 * desta sessão.
 */
@Entity
@Table(name = "nota_fiscal_item")
@Data
@EqualsAndHashCode(exclude = "invoice")
@ToString(exclude = "invoice")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Nota fiscal à qual este item pertence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "nota_fiscal_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Invoice invoice;

  /** Sequencial do item dentro da invoice (1-based). */
  @Column(name = "numero_item", nullable = false)
  private Integer itemNumber;

  /** Código interno do produto no sistema do emissor. */
  @Column(name = "codigo_produto", length = 60)
  private String productCode;

  /** Descrição do produto ou serviço. */
  @Column(name = "descricao", nullable = false)
  private String description;

  /** Código NCM (Nomenclatura Comum do Mercosul) — 8 dígitos. */
  @Column(name = "ncm", length = 8, nullable = false)
  private String ncm;

  /** CFOP — Código Fiscal de Operações e Prestações (4 dígitos). */
  @Column(name = "cfop", length = 4, nullable = false)
  private String cfop;

  /** Unidade comercial (ex.: UN, KG, CX). */
  @Column(name = "unidade", length = 6, nullable = false)
  private String unit;

  /** Quantidade comercializada, com até 4 casas decimais. */
  @Column(name = "quantidade", precision = 15, scale = 4, nullable = false)
  private BigDecimal quantity;

  /** Valor unitário do produto, com até 4 casas decimais. */
  @Column(name = "valor_unitario", precision = 15, scale = 4, nullable = false)
  private BigDecimal unitPrice;

  /** Valor total do item ({@code quantity * unitPrice}), com 2 casas decimais. */
  @Column(name = "valor_total", precision = 15, scale = 2, nullable = false)
  private BigDecimal totalValue;

  // -------------------------------------------------------------------------
  // Campos IBS/CBS — EC 132/2023 + LC 214/2025 (Sprint 2 – Sessão 4)
  // Todos nullable: compatibilidade com notas emitidas antes desta sessão.
  // -------------------------------------------------------------------------

  /**
   * Código de Situação Tributária do IBS/CBS (3 dígitos, ex.: {@code "000"}).
   *
   * <p>Tabela de CST definida pela LC 214/2025 e regulamentação complementar.
   * Analogia ao CST de PIS/COFINS (tabela A/B do SPED), adaptada ao novo tributo.
   */
  @Column(name = "cst_ibs_cbs", length = 3)
  private String cstIbsCbs;

  /**
   * Código de Classificação Tributária — cClassTrib (6 dígitos).
   *
   * <p>Identifica o enquadramento do item na tabela de classificações do IBS/CBS
   * conforme LC 214/2025 e Nota Técnica da RFB/CONFAZ.
   */
  @Column(name = "c_class_trib", length = 6)
  private String cClassTrib;

  /**
   * Base de cálculo do IBS/CBS deste item, com 2 casas decimais.
   *
   * <p>Calculado como: {@code totalValue × (1 − reductionPercentage / 100)}.
   * EC 132/2023, art. 156-A, § 1.º; LC 214/2025, arts. 12–18.
   */
  @Column(name = "base_calculo_ibs_cbs", precision = 15, scale = 2)
  private BigDecimal ibsCbsCalculationBase;

  /**
   * Alíquota do IBS aplicada a este item (proporção decimal, ex.: {@code 0.0010} = 0,10 %).
   *
   * <p>Alíquota de referência para 2026 (ano-teste): 0,10 %. Parametrizável via
   * {@code agrocontabil.tributos.ibs.aliquota} em {@code application.yml}.
   * EC 132/2023; LC 214/2025, arts. 87–92 (alíquotas de referência).
   */
  @Column(name = "aliquota_ibs", precision = 7, scale = 4)
  private BigDecimal ibsRate;

  /**
   * Valor do IBS calculado para este item, com 2 casas decimais.
   *
   * <p>{@code ibsValue = ibsCbsCalculationBase × ibsRate}, arredondamento HALF_UP.
   */
  @Column(name = "valor_ibs", precision = 15, scale = 2)
  private BigDecimal ibsValue;

  /**
   * Alíquota da CBS aplicada a este item (proporção decimal, ex.: {@code 0.0090} = 0,90 %).
   *
   * <p>Alíquota de referência para 2026 (ano-teste): 0,90 %. Parametrizável via
   * {@code agrocontabil.tributos.cbs.aliquota} em {@code application.yml}.
   * EC 132/2023; LC 214/2025, arts. 87–92.
   */
  @Column(name = "aliquota_cbs", precision = 7, scale = 4)
  private BigDecimal cbsRate;

  /**
   * Valor da CBS calculada para este item, com 2 casas decimais.
   *
   * <p>{@code cbsValue = ibsCbsCalculationBase × cbsRate}, arredondamento HALF_UP.
   */
  @Column(name = "valor_cbs", precision = 15, scale = 2)
  private BigDecimal cbsValue;

  /**
   * Percentual de redução da base de cálculo, de 0 a 100 (ex.: {@code 60} = 60 %).
   *
   * <p>Permite modelar regimes diferenciados de produtos agropecuários. A LC 214/2025
   * prevê reduções para determinados produtos (ex.: alimentos da cesta básica, insumos
   * agropecuários). O percentual exato por NCM/produto será refinado em sprint futura
   * conforme regulamentação complementar — <em>incerteza registrada intencionalmente</em>.
   * Default {@code 0} = sem redução (alíquota cheia).
   */
  @Column(name = "percentual_reducao", precision = 5, scale = 2)
  private BigDecimal reductionPercentage;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = OffsetDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}

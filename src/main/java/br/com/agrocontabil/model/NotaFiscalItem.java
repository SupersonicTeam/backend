package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Item (linha de produto/serviço) de uma {@link NotaFiscal}.
 *
 * <p>Mapeado para a tabela {@code nota_fiscal_item}. Cada item pertence a exatamente uma
 * nota fiscal e é removido em cascata quando a nota é excluída
 * (gerenciado pelo lado {@link NotaFiscal#getItens()}).
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
 *   <li><strong>Produtor rural / agropecuária:</strong> a LC 214/2025 prevê regimes
 *       diferenciados e possíveis reduções de base para produtos agropecuários. O campo
 *       {@link #percentualReducao} permite modelar essa redução; o percentual exato por
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
@EqualsAndHashCode(exclude = "notaFiscal")
@ToString(exclude = "notaFiscal")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotaFiscalItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Nota fiscal à qual este item pertence. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "nota_fiscal_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private NotaFiscal notaFiscal;

  /** Sequencial do item dentro da nota (1-based). */
  @Column(name = "numero_item", nullable = false)
  private Integer numeroItem;

  /** Código interno do produto no sistema do emissor. */
  @Column(name = "codigo_produto", length = 60)
  private String codigoProduto;

  /** Descrição do produto ou serviço. */
  @Column(name = "descricao", nullable = false)
  private String descricao;

  /** Código NCM (Nomenclatura Comum do Mercosul) — 8 dígitos. */
  @Column(name = "ncm", length = 8, nullable = false)
  private String ncm;

  /** CFOP — Código Fiscal de Operações e Prestações (4 dígitos). */
  @Column(name = "cfop", length = 4, nullable = false)
  private String cfop;

  /** Unidade comercial (ex.: UN, KG, CX). */
  @Column(name = "unidade", length = 6, nullable = false)
  private String unidade;

  /** Quantidade comercializada, com até 4 casas decimais. */
  @Column(name = "quantidade", precision = 15, scale = 4, nullable = false)
  private BigDecimal quantidade;

  /** Valor unitário do produto, com até 4 casas decimais. */
  @Column(name = "valor_unitario", precision = 15, scale = 4, nullable = false)
  private BigDecimal valorUnitario;

  /** Valor total do item ({@code quantidade * valorUnitario}), com 2 casas decimais. */
  @Column(name = "valor_total", precision = 15, scale = 2, nullable = false)
  private BigDecimal valorTotal;

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
   * <p>Calculado como: {@code valorTotal × (1 − percentualReducao / 100)}.
   * EC 132/2023, art. 156-A, § 1.º; LC 214/2025, arts. 12–18.
   */
  @Column(name = "base_calculo_ibs_cbs", precision = 15, scale = 2)
  private BigDecimal baseCalculoIbsCbs;

  /**
   * Alíquota do IBS aplicada a este item (proporção decimal, ex.: {@code 0.0010} = 0,10 %).
   *
   * <p>Alíquota de referência para 2026 (ano-teste): 0,10 %. Parametrizável via
   * {@code agrocontabil.tributos.ibs.aliquota} em {@code application.yml}.
   * EC 132/2023; LC 214/2025, arts. 87–92 (alíquotas de referência).
   */
  @Column(name = "aliquota_ibs", precision = 7, scale = 4)
  private BigDecimal aliquotaIbs;

  /**
   * Valor do IBS calculado para este item, com 2 casas decimais.
   *
   * <p>{@code valorIbs = baseCalculoIbsCbs × aliquotaIbs}, arredondamento HALF_UP.
   */
  @Column(name = "valor_ibs", precision = 15, scale = 2)
  private BigDecimal valorIbs;

  /**
   * Alíquota da CBS aplicada a este item (proporção decimal, ex.: {@code 0.0090} = 0,90 %).
   *
   * <p>Alíquota de referência para 2026 (ano-teste): 0,90 %. Parametrizável via
   * {@code agrocontabil.tributos.cbs.aliquota} em {@code application.yml}.
   * EC 132/2023; LC 214/2025, arts. 87–92.
   */
  @Column(name = "aliquota_cbs", precision = 7, scale = 4)
  private BigDecimal aliquotaCbs;

  /**
   * Valor da CBS calculada para este item, com 2 casas decimais.
   *
   * <p>{@code valorCbs = baseCalculoIbsCbs × aliquotaCbs}, arredondamento HALF_UP.
   */
  @Column(name = "valor_cbs", precision = 15, scale = 2)
  private BigDecimal valorCbs;

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
  private BigDecimal percentualReducao;

  // -------------------------------------------------------------------------
  // Crédito tributário de entrada — Sprint 4, issue Agrocontabil#30 (RN003).
  // EC 132/2023, art. 156-A, § 1.º, VIII (não-cumulatividade); LC 214/2025, art. 47.
  // Nullable: preenchidos apenas em itens elegíveis de notas IMPORTADAS.
  // -------------------------------------------------------------------------

  /**
   * Valor do crédito de IBS aproveitável neste item (nota de entrada), com 2 casas decimais.
   *
   * <p>{@code valorCreditoIbs = baseCalculo × aliquotaIbs × percentualAproveitamento / 100},
   * arredondamento HALF_UP. Calculado pelo {@code CreditoTributarioService} na importação.
   * Nulo em itens não elegíveis (ex.: CFOP de exportação) e em notas próprias (saída).
   */
  @Column(name = "valor_credito_ibs", precision = 15, scale = 2)
  private BigDecimal valorCreditoIbs;

  /**
   * Valor do crédito de CBS aproveitável neste item (nota de entrada), com 2 casas decimais.
   *
   * <p>{@code valorCreditoCbs = baseCalculo × aliquotaCbs × percentualAproveitamento / 100},
   * arredondamento HALF_UP. Calculado pelo {@code CreditoTributarioService} na importação.
   * Nulo em itens não elegíveis e em notas próprias (saída).
   */
  @Column(name = "valor_credito_cbs", precision = 15, scale = 2)
  private BigDecimal valorCreditoCbs;

  // -------------------------------------------------------------------------
  // Natureza tributaria por operacao — Sprint 4, issue Agrocontabil#30 (RF015).
  // Classificada pelo 1.º digito do CFOP: 5/6/7 -> DEBITO (saida); 1/2/3 -> CREDITO
  // (entrada). Consumida pela apuracao mensal (issue #41). Nullable: compatibilidade
  // com itens gravados antes desta sprint.
  // -------------------------------------------------------------------------

  /**
   * Natureza tributaria do item para fins de apuracao (DEBITO em saidas, CREDITO em entradas).
   *
   * <p>Resolvida pelo {@code TributacaoService} a partir do 1.º digito do CFOP na criacao e
   * atualizacao da nota. Nula em itens gravados antes da Sprint 4 (issue #30) ou quando o CFOP
   * nao segue o padrao numerico esperado.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "natureza_operacao", length = 10)
  private NaturezaOperacaoTributaria naturezaOperacao;

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

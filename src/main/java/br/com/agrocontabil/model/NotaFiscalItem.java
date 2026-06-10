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
 * Item (linha de produto/serviço) de uma {@link NotaFiscal}.
 *
 * <p>Mapeado para a tabela {@code nota_fiscal_item}. Cada item pertence a exatamente uma
 * nota fiscal e é removido em cascata quando a nota é excluída
 * (gerenciado pelo lado {@link NotaFiscal#getItens()}).
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

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Guia fiscal (ICMS/FUNRURAL/DARF/GPS/OUTRO) anexada pelo Contador para um {@link Produtor}
 * (issue #74).
 *
 * <p>Registra apenas o vencimento e o valor informados manualmente pelo Contador — não há
 * geração/validação real da guia. Alimenta o calendário fiscal do produtor
 * ({@code CalendarioFiscalService}) e pode ser marcada como paga pelo próprio produtor
 * ({@code GuiaFiscalService#marcarComoPaga}).
 *
 * <p>Não existe autenticação de Contador no backend ainda — esta entidade não guarda "quem"
 * criou a guia, apenas o {@link Produtor} destinatário (débito técnico registrado para a
 * Sprint 8, quando o RBAC produtor/contador chegar).
 */
@Entity
@Table(name = "guias_fiscais")
@Data
@EqualsAndHashCode(exclude = "produtor")
@ToString(exclude = "produtor")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuiaFiscal {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Produtor destinatário da guia (quem vê o vencimento no calendário fiscal). */
  // NO_CONSTRAINT: a FK real e criada e gerenciada pelo Liquibase (changeset 0018);
  // o Hibernate roda com ddl-auto: validate e nunca cria constraints (padrao do projeto).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "produtor_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  /** Tipo da guia (ICMS/FUNRURAL/DARF/GPS/OUTRO). */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_guia", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  private TipoGuiaFiscal tipoGuia;

  /** Data de vencimento informada pelo Contador. */
  @Column(name = "data_vencimento", nullable = false)
  private LocalDate dataVencimento;

  /** Valor da guia. */
  @Column(name = "valor", precision = 15, scale = 2, nullable = false)
  private BigDecimal valor;

  /** Observações livres do Contador. Opcional. */
  @Column(name = "observacoes", length = 255)
  private String observacoes;

  /** Status do ciclo de vida. Inicia como {@link StatusGuiaFiscal#PENDENTE}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  @Builder.Default
  private StatusGuiaFiscal status = StatusGuiaFiscal.PENDENTE;

  /** Data e hora em que o produtor marcou a guia como paga. Nula enquanto não paga. */
  @Column(name = "data_pagamento")
  private OffsetDateTime dataPagamento;

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

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
import jakarta.persistence.UniqueConstraint;
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
 * Apuração mensal consolidada de IBS/CBS de um {@link Produtor} (issue Agrocontabil#41 — RN004).
 *
 * <p>Mapeada para a tabela {@code apuracao_mensal}. Consolida, por produtor e por período mensal
 * ({@code YYYY-MM}), o débito das saídas AUTORIZADAS e o crédito das entradas IMPORTADAS, apurando
 * o saldo devido ({@code débito − crédito}) de IBS e de CBS.
 *
 * <p>Os valores são reaproveitados das agregações já existentes no
 * {@link br.com.agrocontabil.repository.NotaFiscalRepository} ({@code somarDebitosPorPeriodo} e
 * {@code somarCreditosPorPeriodo}); esta entidade apenas <strong>persiste</strong> o resultado e
 * seu ciclo de vida ({@link StatusApuracao}), que os serviços de crédito/relatório (read-only) não
 * mantêm.
 *
 * <p>Unicidade garantida pela constraint {@code (produtor_id, periodo)} — cada produtor tem no
 * máximo uma apuração por período.
 */
@Entity
@Table(name = "apuracao_mensal",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_apuracao_produtor_periodo", columnNames = {"produtor_id", "periodo"}))
@Data
@EqualsAndHashCode(exclude = "produtor")
@ToString(exclude = "produtor")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApuracaoMensal {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /**
   * Produtor dono da apuração.
   *
   * <p>FK gerenciada pelo Liquibase; o Hibernate roda com {@code ddl-auto: validate} e nunca cria
   * constraints (padrão do projeto).
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "produtor_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  /** Período de competência no formato {@code YYYY-MM} (ex.: {@code 2026-07}). */
  @Column(name = "periodo", length = 7, nullable = false)
  private String periodo;

  /** Estado do ciclo de vida da apuração. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  private StatusApuracao status;

  /** Débito de IBS das saídas AUTORIZADAS do período. */
  @Column(name = "debito_ibs", nullable = false)
  private BigDecimal debitoIbs;

  /** Débito de CBS das saídas AUTORIZADAS do período. */
  @Column(name = "debito_cbs", nullable = false)
  private BigDecimal debitoCbs;

  /** Crédito de IBS das entradas IMPORTADAS do período. */
  @Column(name = "credito_ibs", nullable = false)
  private BigDecimal creditoIbs;

  /** Crédito de CBS das entradas IMPORTADAS do período. */
  @Column(name = "credito_cbs", nullable = false)
  private BigDecimal creditoCbs;

  /** Saldo de IBS a recolher no período ({@code débito − crédito}). */
  @Column(name = "saldo_ibs", nullable = false)
  private BigDecimal saldoIbs;

  /** Saldo de CBS a recolher no período ({@code débito − crédito}). */
  @Column(name = "saldo_cbs", nullable = false)
  private BigDecimal saldoCbs;

  /** Quantidade de notas (saídas + entradas) consideradas na consolidação. */
  @Column(name = "total_notas", nullable = false)
  private long totalNotas;

  /** Instante do fechamento automático; nulo enquanto o período está aberto. */
  @Column(name = "data_fechamento")
  private OffsetDateTime dataFechamento;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    OffsetDateTime agora = OffsetDateTime.now();
    createdAt = agora;
    updatedAt = agora;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}

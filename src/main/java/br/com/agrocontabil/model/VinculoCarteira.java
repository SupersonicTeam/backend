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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Vínculo do escritório com um produtor da carteira (issue #177), no lugar do antigo N:N
 * {@code contador_produtor}.
 *
 * <p>É a única autorização que o contador tem sobre os dados do produtor, e só na situação
 * {@link SituacaoVinculo#ATIVO} ({@code ProdutorEmContextoResolver}). Nasce do aceite de um
 * convite, nunca de um documento informado pelo escritório. Guarda também a ficha do cliente:
 * observações internas do escritório (nunca visíveis ao produtor) e etiquetas do catálogo.
 *
 * <p>Um vínculo não encerrado por par (índice único parcial no PostgreSQL); os encerrados ficam
 * como histórico, e um novo convite aceito cria outra linha.
 */
@Entity
@Table(name = "carteira_vinculo")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VinculoCarteira {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Escritório dono do vínculo (issue #228; até a #177 era o contador). */
  @Column(name = "escritorio_id", nullable = false, updatable = false)
  private UUID escritorioId;

  /** Membro do escritório responsável pelo cliente; nulo = sem responsável. */
  @Column(name = "responsavel_id")
  private UUID responsavelId;

  @Column(name = "produtor_id", nullable = false, updatable = false)
  private UUID produtorId;

  /** Só leitura: o vínculo é gravado pelo {@link #produtorId}. */
  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "produtor_id", insertable = false, updatable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  @Enumerated(EnumType.STRING)
  @Column(name = "situacao", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private SituacaoVinculo situacao;

  @Column(name = "inicio", nullable = false, updatable = false)
  private OffsetDateTime inicio;

  /** Preenchido quando o vínculo é encerrado. */
  @Column(name = "fim")
  private OffsetDateTime fim;

  /** Observações internas do escritório: nunca vão ao produtor nem ao log. */
  @ToString.Exclude
  @Column(name = "observacoes", length = 1000)
  private String observacoes;

  // NO_CONSTRAINT: as FKs (CASCADE nos dois lados) sao do Liquibase (0038) e do schema H2.
  @ToString.Exclude
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "carteira_vinculo_etiqueta",
      joinColumns = @JoinColumn(name = "vinculo_id"),
      inverseJoinColumns = @JoinColumn(name = "etiqueta_id"),
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT),
      inverseForeignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  @Builder.Default
  private Set<EtiquetaCarteira> etiquetas = new LinkedHashSet<>();

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}

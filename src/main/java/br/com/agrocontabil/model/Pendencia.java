package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Pedido de documento do escritório ao produtor (issue #180). Os anexos ficam em
 * {@link PendenciaAnexo}, nunca carregados junto.
 */
@Entity
@Table(name = "pendencia")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Pendencia {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "escritorio_id", nullable = false, updatable = false)
  private UUID escritorioId;

  @Column(name = "produtor_id", nullable = false, updatable = false)
  private UUID produtorId;

  @Column(name = "titulo", nullable = false, length = 120)
  private String titulo;

  @Column(name = "descricao", length = 1000)
  private String descricao;

  /** Observações do escritório para o produtor. */
  @Column(name = "observacoes", length = 1000)
  private String observacoes;

  /** Prazo do pedido; com ele, a pendência em aberto entra no calendário. */
  @Column(name = "data_limite")
  private LocalDate dataLimite;

  @Convert(converter = TiposDocumentoConverter.class)
  @Column(name = "tipos_documento", length = 120)
  @Builder.Default
  private Set<TipoDocumentoPendencia> tiposDocumento =
      EnumSet.noneOf(TipoDocumentoPendencia.class);

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private StatusPendencia status;

  /** Motivo da última rejeição ou do cancelamento. */
  @Column(name = "motivo", length = 500)
  private String motivo;

  /** Membro do escritório que criou. */
  @Column(name = "criado_por", updatable = false)
  private UUID criadoPor;

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

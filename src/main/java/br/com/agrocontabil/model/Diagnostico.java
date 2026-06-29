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
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "diagnosticos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Diagnostico {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "produtor_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  @Enumerated(EnumType.STRING)
  @Column(name = "impacto_geral", columnDefinition = "VARCHAR(20)")
  private ImpactoGeral impactoGeral;

  @Column(name = "pontuacao")
  private Integer pontuacao;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "analises", columnDefinition = "jsonb")
  private String analises;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "recomendacoes", columnDefinition = "text[]")
  private String[] recomendacoes;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }
}

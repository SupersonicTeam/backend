package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "produtores")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Producer {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome_completo", nullable = false)
  private String fullName;

  @Column(name = "cpf_cnpj", length = 20)
  private String taxId;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_produtor", columnDefinition = "VARCHAR(20)")
  private ProducerType producerType;

  @Enumerated(EnumType.STRING)
  @Column(name = "regime_tributario", columnDefinition = "VARCHAR(20)")
  private TaxRegime taxRegime;

  @Enumerated(EnumType.STRING)
  @Column(name = "porte_empresa", columnDefinition = "VARCHAR(20)")
  private CompanySize companySize;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "culturas", columnDefinition = "text[]")
  private String[] crops;

  @Column(name = "faturamento_anual", precision = 15, scale = 2)
  private BigDecimal annualRevenue;

  @Column(name = "estado", length = 2)
  private String state;

  @Column(name = "email")
  private String email;

  @Column(name = "telefone", length = 20)
  private String phone;

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

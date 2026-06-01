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
public class Produtor {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome_completo", nullable = false)
  private String nomeCompleto;

  @Column(name = "cpf_cnpj", length = 20)
  private String cpfCnpj;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_produtor", columnDefinition = "VARCHAR(20)")
  private TipoProdutor tipoProdutor;

  @Enumerated(EnumType.STRING)
  @Column(name = "regime_tributario", columnDefinition = "VARCHAR(20)")
  private RegimeTributario regimeTributario;

  @Enumerated(EnumType.STRING)
  @Column(name = "porte_empresa", columnDefinition = "VARCHAR(20)")
  private PorteEmpresa porteEmpresa;

  @Column(name = "culturas", columnDefinition = "VARCHAR(2000)")
  private String culturas;

  @Column(name = "faturamento_anual", precision = 15, scale = 2)
  private BigDecimal faturamentoAnual;

  @Column(name = "estado", length = 2)
  private String estado;

  @Column(name = "email")
  private String email;

  @Column(name = "telefone", length = 20)
  private String telefone;

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

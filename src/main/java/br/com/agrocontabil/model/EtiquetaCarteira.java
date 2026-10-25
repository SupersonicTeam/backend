package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Etiqueta do catálogo do escritório, aplicada aos clientes da carteira (issue #177).
 *
 * <p>Única por escritório pelo nome normalizado (sem acento nem caixa): "Soja" e "soja" são a
 * mesma etiqueta.
 */
@Entity
@Table(name = "etiqueta_carteira")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class EtiquetaCarteira {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "escritorio_id", nullable = false, updatable = false)
  private UUID escritorioId;

  @Column(name = "nome", nullable = false, length = 30)
  private String nome;

  @Column(name = "nome_normalizado", nullable = false, length = 30)
  private String nomeNormalizado;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }
}

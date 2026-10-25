package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * Escritório de contabilidade (issue #228): dono da carteira de clientes, dos catálogos e dos
 * convites. Os contadores são membros dele, cada um com uma {@link FuncaoEscritorio}; na v1 um
 * contador pertence a um escritório só.
 *
 * <p>Todo contador cadastrado ganha um escritório unipessoal, do qual é administrador; pode
 * trocá-lo pelo de uma equipe aceitando um convite enquanto o unipessoal estiver vazio.
 */
@Entity
@Table(name = "escritorio")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Escritorio {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome", nullable = false)
  private String nome;

  /** CNPJ do escritório, só dígitos; opcional (contador autônomo não tem). */
  @Column(name = "cnpj", length = 14)
  private String cnpj;

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

package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * <p>Todo contador que se cadastra sozinho ganha um escritório, do qual é administrador: pessoal
 * (com o nome dele) ou empresarial (com nome, CNPJ, telefone, endereço postal e endereço próprio),
 * conforme o {@link #tipo} escolhido (issue #519). Pode trocá-lo pelo de uma equipe aceitando um
 * convite enquanto o dele estiver vazio.
 *
 * <p>O {@link #slug} é o endereço próprio do escritório, {@code {slug}.agroconia.com.br} (issue
 * #464): marca e porta de entrada, nunca fronteira de autorização. Pode ser dado pessoal (o nome do
 * escritório unipessoal é o do contador): fica fora do {@code toString} e de todo log.
 */
@Entity
@Table(name = "escritorio",
    uniqueConstraints = @UniqueConstraint(name = "uq_escritorio_slug", columnNames = "slug"))
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

  /**
   * Pessoal (um usuário só) ou empresarial (pode ter equipe e endereço próprio), escolhido no
   * cadastro do contador (issue #519). Gravado, nunca derivado do CNPJ; sem escolha, pessoal.
   */
  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private TipoEscritorio tipo = TipoEscritorio.PESSOAL;

  /** Telefone do escritório, só dígitos (issue #519); cifrado em repouso como os outros. */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "telefone", length = 100)
  private String telefone;

  /** Endereço postal (issue #519); nulo nos escritórios sem ele. */
  @ToString.Exclude
  @Embedded
  private EnderecoPostal endereco;

  /** Rótulo do subdomínio, normalizado; nulo = o escritório usa o {@code www} (issue #464). */
  @ToString.Exclude
  @Column(name = "slug", length = 32)
  private String slug;

  /** Última definição, troca ou remoção do slug: a próxima gravação respeita o intervalo. */
  @Column(name = "slug_alterado_em")
  private OffsetDateTime slugAlteradoEm;

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

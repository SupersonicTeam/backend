package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Contador (escritório de contabilidade) que atende produtores rurais — issue #93.
 *
 * <p>É a segunda identidade do sistema, ao lado do {@link Produtor}. Até esta entrega o backend só
 * modelava o produtor: os endpoints de {@code /api/contador/**} existiam (issue #74) mas eram, na
 * prática, do próprio produtor, porque não havia entidade nem papel de contador para escopar o
 * acesso.
 *
 * <p><strong>Vínculo N:N com o produtor.</strong> Um escritório atende vários produtores e um
 * produtor pode trocar de escritório (ou manter mais de um — decisão do domínio: nada impede, e o
 * modelo N:N não obriga a escolher agora). O vínculo é a única autorização que o contador tem sobre
 * os dados de um produtor: sem linha em {@code contador_produtor}, o produtor é inexistente para
 * ele.
 *
 * <p>A coleção é o <em>lado dono</em> do relacionamento de propósito — o {@link Produtor} não ganha
 * a coleção inversa. Assim o agregado do produtor (usado na emissão de NF-e, no dashboard, na
 * apuração) continua intocado, e o vínculo é gerenciado de um lado só.
 */
@Entity
@Table(name = "contadores")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Contador {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome_completo", nullable = false)
  private String nomeCompleto;

  /** CPF ou CNPJ do escritório — identifica o contador no login que chegará com o JWT. */
  @Column(name = "cpf_cnpj", length = 20, unique = true)
  private String cpfCnpj;

  /**
   * Registro no Conselho Regional de Contabilidade (ex.: {@code MT-012345/O}).
   *
   * <p>Guardado para exibição e rastreio de quem assina a escrituração; sem validação de formato,
   * que varia por conselho regional — mesma decisão tomada para a Inscrição Estadual do produtor.
   */
  @Column(name = "crc", length = 20)
  private String crc;

  @Column(name = "email")
  private String email;

  @Column(name = "telefone", length = 20)
  private String telefone;

  /**
   * Produtores que este contador atende.
   *
   * <p>{@code LAZY} porque quase todo acesso é uma pergunta de sim/não sobre um produtor específico
   * ("este contador atende aquele produtor?"), respondida por consulta no repositório sem carregar
   * a carteira inteira — um escritório real atende centenas de produtores.
   *
   * <p>Fora de {@code equals}/{@code hashCode}/{@code toString}: comparar a carteira inteira faria
   * o {@code equals} depender de coleção lazy (e um {@code toString} em log dispararia
   * {@code LazyInitializationException} fora da transação).
   */
  @EqualsAndHashCode.Exclude
  @ToString.Exclude
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "contador_produtor",
      joinColumns = @JoinColumn(name = "contador_id"),
      inverseJoinColumns = @JoinColumn(name = "produtor_id"))
  @Builder.Default
  private Set<Produtor> produtores = new LinkedHashSet<>();

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

  /**
   * Vincula um produtor à carteira deste contador (idempotente — é um {@link Set}).
   *
   * @param produtor produtor a atender
   */
  public void vincular(Produtor produtor) {
    if (produtores == null) {
      produtores = new LinkedHashSet<>();
    }
    produtores.add(produtor);
  }
}

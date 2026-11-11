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
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Contador que atende produtores rurais — issue #93. É a pessoa: desde a #228 o escritório de
 * contabilidade é o {@link Escritorio}, de que o contador é membro.
 *
 * <p>É a segunda identidade do sistema, ao lado do {@link Produtor}. Até esta entrega o backend só
 * modelava o produtor: os endpoints de {@code /api/contador/**} existiam (issue #74) mas eram, na
 * prática, do próprio produtor, porque não havia entidade nem papel de contador para escopar o
 * acesso.
 *
 * <p><strong>Carteira.</strong> Um escritório atende vários produtores e um produtor pode ter mais
 * de um escritório. Desde a issue #177 o vínculo é a entidade {@link VinculoCarteira} (com
 * situação e histórico), que nasce do aceite de um convite; é a única autorização que o contador
 * tem sobre os dados de um produtor: sem vínculo {@code ATIVO}, o produtor é inexistente para ele.
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

  /**
   * Documento da pessoa do contador, só dígitos, único entre os contadores.
   *
   * <p>Desde a issue #519 o cadastro só aceita CPF (regra em
   * {@code validation/DocumentoDoContador}); contas anteriores podem ter um CNPJ aqui, gravado
   * quando o campo era "CPF ou CNPJ". O CNPJ do escritório fica no {@link Escritorio#getCnpj()}.
   */
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "cpf_cnpj", length = 100, unique = true)
  private String cpfCnpj;

  /**
   * Registro no Conselho Regional de Contabilidade (ex.: {@code MT-012345/O}), opcional, informado
   * no cadastro (issue #519).
   *
   * <p>Guardado para exibição e rastreio de quem assina a escrituração; sem validação de formato,
   * que varia por conselho regional — mesma decisão tomada para a Inscrição Estadual do produtor.
   */
  @Column(name = "crc", length = 20)
  private String crc;

  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "email", length = 512)
  private String email;

  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "telefone", length = 100)
  private String telefone;

  /**
   * Escritório de que o contador é membro (issue #228); nulo depois de removido da equipe, quando
   * ele deixa de alcançar qualquer carteira.
   */
  @Column(name = "escritorio_id")
  private UUID escritorioId;

  /** Função no escritório; nula junto com o escritório. */
  @Enumerated(EnumType.STRING)
  @Column(name = "funcao_escritorio", length = 20, columnDefinition = "VARCHAR(20)")
  private FuncaoEscritorio funcao;

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

package br.com.agrocontabil.model;

import br.com.agrocontabil.common.NormalizacaoBusca;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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
 * Modelo de pedido de documento do escritório (issue #517): preenche o formulário do pedido
 * ({@link Pendencia}, #180), que não guarda referência a ele.
 *
 * <p>Único por escritório pelo título normalizado (sem acento nem caixa), calculado aqui antes de
 * gravar, qualquer que seja o caminho (serviço ou seed). Os textos são do escritório e podem citar
 * o cliente: ficam fora do {@code toString}, para nunca irem a log.
 */
@Entity
@Table(name = "modelo_pedido")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class ModeloPedido {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  @ToString.Include
  private UUID id;

  @Column(name = "escritorio_id", nullable = false, updatable = false)
  @ToString.Include
  private UUID escritorioId;

  @Column(name = "titulo", nullable = false, length = 120)
  private String titulo;

  @Column(name = "titulo_normalizado", nullable = false, length = 120)
  private String tituloNormalizado;

  @Column(name = "descricao", length = 1000)
  private String descricao;

  @Convert(converter = TiposDocumentoConverter.class)
  @Column(name = "tipos_documento", nullable = false, length = 120)
  @Builder.Default
  private Set<TipoDocumentoPendencia> tiposDocumento =
      EnumSet.noneOf(TipoDocumentoPendencia.class);

  /** Prazo sugerido, em dias a partir do pedido; nulo = sem prazo. */
  @Column(name = "prazo_em_dias")
  private Integer prazoEmDias;

  /** Observações ao produtor. */
  @Column(name = "observacoes", length = 1000)
  private String observacoes;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /**
   * Título como entra na comparação de repetidos: a regra das etiquetas (#177).
   *
   * @param titulo título como digitado
   * @return minúsculas, sem acento, espaços colapsados
   */
  public static String normalizarTitulo(String titulo) {
    return NormalizacaoBusca.normalizar(titulo);
  }

  /** Datas e título normalizado na criação. */
  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
    tituloNormalizado = normalizarTitulo(titulo);
  }

  /** Data e título normalizado a cada edição. */
  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
    tituloNormalizado = normalizarTitulo(titulo);
  }
}

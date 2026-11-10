package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * Slug que saiu de um escritório e fica em carência (issue #464, changeSet 0059): até
 * {@link #liberadoEm}, nenhum outro escritório o registra. Impede tomar o endereço que ainda
 * circula em e-mails e favoritos.
 *
 * <p>{@link #escritorioId} nulo = o escritório foi apagado (o contador entrou em outra equipe): o
 * slug segue bloqueado, mas nunca é servido. A FK é {@code ON DELETE SET NULL}; a exclusão de dados
 * (LGPD) apaga as linhas do escritório antes de apagá-lo.
 */
@Entity
@Table(name = "escritorio_slug_anterior")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "slug")
public class EscritorioSlugAnterior {

  /** O slug, normalizado. Pode ser dado pessoal: fora do {@code toString} e do log. */
  @Id
  @Column(name = "slug", length = 32, nullable = false, updatable = false)
  private String slug;

  /** Escritório de onde saiu; nulo depois que ele é apagado. */
  @Column(name = "escritorio_id")
  private UUID escritorioId;

  /** Fim da carência. */
  @Column(name = "liberado_em", nullable = false)
  private OffsetDateTime liberadoEm;
}

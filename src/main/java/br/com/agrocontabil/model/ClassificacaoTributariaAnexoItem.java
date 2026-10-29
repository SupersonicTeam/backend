package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item de um anexo da LC 214/2025 ligado a um {@code cClassTrib} (issue #272): o texto do item,
 * a condição e a exceção como publicados na tabela oficial (campo {@code Anexos}). Os NCMs do
 * item ficam em {@link ClassificacaoTributariaAnexoNcm}.
 *
 * <p>Mapeada para {@code classificacao_tributaria_anexo_item} (changeSet {@code 0048}). Somente
 * leitura em runtime.
 */
@Entity
@Table(name = "classificacao_tributaria_anexo_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificacaoTributariaAnexoItem {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private Integer id;

  /** {@code cClassTrib} da hipótese (ex.: {@code 200003} = Anexo I, cesta básica). */
  @Column(name = "c_class_trib", length = 6, nullable = false)
  private String cClassTrib;

  /** Número do anexo da LC 214/2025 (ex.: {@code 1}, {@code 15}). */
  @Column(name = "anexo", nullable = false)
  private Integer anexo;

  /** Texto do item do anexo. */
  @Column(name = "descricao", length = 1500, nullable = false)
  private String descricao;

  /** Condição de aplicação (ex.: "DESTINADOS À ALIMENTAÇÃO HUMANA"); pode faltar. */
  @Column(name = "condicao", length = 255)
  private String condicao;

  /** Exceção do item (ex.: "Produtos relacionados no Anexo I"); pode faltar. */
  @Column(name = "excecao", length = 1000)
  private String excecao;
}

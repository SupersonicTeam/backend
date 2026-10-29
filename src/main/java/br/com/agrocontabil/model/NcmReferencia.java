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
 * Código NCM (Nomenclatura Comum do Mercosul) de referência do agronegócio (issue #20, RN008).
 *
 * <p>Tabela <strong>somente leitura</strong> populada por seed curado via Liquibase
 * (changeset {@code 0007}). Serve de base para a validação de existência de NCM nos itens da
 * nota fiscal. Não é um catálogo oficial completo — é um subconjunto suficiente para o escopo
 * do TCC (produtos agropecuários típicos).
 *
 * <p>Todo código precisa existir na nomenclatura oficial ({@code NcmSemeadoExisteNaNomenclaturaTest},
 * issue #282). Mapeada para a tabela {@code ncm_agropecuario}.
 */
@Entity
@Table(name = "ncm_agropecuario")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NcmReferencia {

  /** Código NCM com 8 dígitos numéricos (chave primária). */
  @Id
  @Column(name = "codigo", length = 8, nullable = false, updatable = false)
  private String codigo;

  /** Descrição textual do produto associado ao NCM. */
  @Column(name = "descricao", length = 255, nullable = false)
  private String descricao;

  /**
   * Produto agropecuário <em>in natura</em> (LC 214/2025, art. 137): sem industrialização; a
   * secagem, a limpeza, a debulha de grãos e o descaroçamento não tiram a condição (§ 1º, I).
   * Dá a redução de 60% (cClassTrib 200036) quando o NCM não está num anexo de alíquota zero —
   * issue #272. O art. 137 não tem lista de NCM, por isso a marca fica na referência.
   *
   * <p>Substitui o {@code percentual_reducao_padrao} hipotético do changeSet {@code 0012}: a
   * redução por NCM vem da tabela oficial ({@code ClassificacaoTributariaService}).
   */
  @Column(name = "in_natura", nullable = false)
  private boolean inNatura;
}

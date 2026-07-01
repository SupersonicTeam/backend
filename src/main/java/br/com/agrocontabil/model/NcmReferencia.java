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
 * <p>Mapeada para a tabela {@code ncm_agropecuario}.
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
}

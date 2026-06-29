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
 * Tabela de referência de NCMs (Nomenclatura Comum do Mercosul) válidos para
 * operações agropecuárias. Dados versionados no Liquibase (changeset 0006).
 *
 * <p>Usada pela validação {@code @ValidNcm} para rejeitar items cujo NCM não
 * esteja cadastrado para o agronegócio.
 */
@Entity
@Table(name = "ncm_agropecuario")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgriculturalNcm {

  /** Código NCM com 8 dígitos. Chave primária. */
  @Id
  @Column(name = "codigo", length = 8, nullable = false, updatable = false)
  private String codigo;

  /** Descrição do produto/NCM. */
  @Column(name = "descricao", nullable = false)
  private String description;
}

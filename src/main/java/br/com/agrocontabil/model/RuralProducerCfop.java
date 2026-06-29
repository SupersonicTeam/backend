package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tabela de referência de CFOPs (Código Fiscal de Operações e Prestações)
 * aplicáveis ao producer rural. Dados versionados no Liquibase (changeset 0006).
 *
 * <p>Cada CFOP carrega seu {@link OperationType} (entrada/saída) e {@link OperationScope}
 * (interna/interestadual). Usada por {@code @ValidCfop} (existência) e por
 * {@code CfopOperationValidator} (compatibilidade com a operação da invoice).
 */
@Entity
@Table(name = "cfop_produtor_rural")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuralProducerCfop {

  /** Código CFOP com 4 dígitos. Chave primária. */
  @Id
  @Column(name = "codigo", length = 4, nullable = false, updatable = false)
  private String codigo;

  /** Descrição da operação do CFOP. */
  @Column(name = "descricao", nullable = false)
  private String description;

  /** Tipo da operação (ENTRADA/SAIDA). */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_operacao", length = 7, nullable = false,
      columnDefinition = "VARCHAR(7)")
  private OperationType operationType;

  /** Âmbito territorial (INTERNA/INTERESTADUAL). */
  @Enumerated(EnumType.STRING)
  @Column(name = "ambito", length = 15, nullable = false,
      columnDefinition = "VARCHAR(15)")
  private OperationScope scope;
}

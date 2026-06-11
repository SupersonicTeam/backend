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
 * aplicáveis ao produtor rural. Dados versionados no Liquibase (changeset 0006).
 *
 * <p>Cada CFOP carrega seu {@link TipoOperacao} (entrada/saída) e {@link AmbitoOperacao}
 * (interna/interestadual). Usada por {@code @ValidCfop} (existência) e por
 * {@code CfopOperacaoValidator} (compatibilidade com a operação da nota).
 */
@Entity
@Table(name = "cfop_produtor_rural")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CfopProdutorRural {

  /** Código CFOP com 4 dígitos. Chave primária. */
  @Id
  @Column(name = "codigo", length = 4, nullable = false, updatable = false)
  private String codigo;

  /** Descrição da operação do CFOP. */
  @Column(name = "descricao", nullable = false)
  private String descricao;

  /** Tipo da operação (ENTRADA/SAIDA). */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_operacao", length = 7, nullable = false,
      columnDefinition = "VARCHAR(7)")
  private TipoOperacao tipoOperacao;

  /** Âmbito territorial (INTERNA/INTERESTADUAL). */
  @Enumerated(EnumType.STRING)
  @Column(name = "ambito", length = 15, nullable = false,
      columnDefinition = "VARCHAR(15)")
  private AmbitoOperacao ambito;
}

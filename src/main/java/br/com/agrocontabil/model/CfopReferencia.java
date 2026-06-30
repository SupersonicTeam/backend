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
 * CFOP (Código Fiscal de Operações e Prestações) de referência do agronegócio
 * (issue #20, RN008).
 *
 * <p>Tabela <strong>somente leitura</strong> populada por seed curado via Liquibase
 * (changeset {@code 0007}). Serve de base para a validação de existência/aplicabilidade de
 * CFOP nos itens da nota fiscal. Não é o catálogo oficial completo — é um subconjunto
 * suficiente para o escopo do TCC.
 *
 * <p>Mapeada para a tabela {@code cfop_referencia}.
 */
@Entity
@Table(name = "cfop_referencia")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CfopReferencia {

  /** Código CFOP com 4 dígitos numéricos (chave primária). */
  @Id
  @Column(name = "codigo", length = 4, nullable = false, updatable = false)
  private String codigo;

  /** Descrição textual da operação associada ao CFOP. */
  @Column(name = "descricao", length = 255, nullable = false)
  private String descricao;

  /** Tipo de operação (entrada/saída) codificado pelo CFOP. */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_operacao", length = 20, nullable = false)
  private TipoOperacaoCfop tipoOperacao;

  /** Âmbito territorial (interno/interestadual/exterior) codificado pelo CFOP. */
  @Enumerated(EnumType.STRING)
  @Column(name = "ambito", length = 20, nullable = false)
  private AmbitoCfop ambito;

  /**
   * Indica se o CFOP é aplicável às operações típicas de um produtor rural. CFOPs que existem
   * na tabela mas não são aplicáveis ao produtor rural são rejeitados na validação de entrada.
   */
  @Column(name = "aplicavel_produtor_rural", nullable = false)
  private boolean aplicavelProdutorRural;
}

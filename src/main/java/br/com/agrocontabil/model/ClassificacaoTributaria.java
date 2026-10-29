package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Código de classificação tributária do IBS e da CBS ({@code cClassTrib}) da tabela oficial
 * (issue #272): Informe Técnico 2025.002, Portal da NF-e (versão em {@link #versaoTabela}).
 *
 * <p>Só os códigos válidos na NF-e ({@code indNFe = 1}). Os três primeiros dígitos do código são
 * o CST. Na tabela oficial, alíquota zero é CST 200 com redução de 100%, não um CST próprio.
 *
 * <p>Mapeada para a tabela {@code classificacao_tributaria} (changeSet {@code 0048}). Somente
 * leitura em runtime.
 */
@Entity
@Table(name = "classificacao_tributaria")
@IdClass(ClassificacaoTributaria.Chave.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificacaoTributaria {

  /** Código de 6 dígitos ({@code cClassTrib}). */
  @Id
  @Column(name = "c_class_trib", length = 6, nullable = false, updatable = false)
  private String codigo;

  /** CST do IBS/CBS (3 dígitos). */
  @Column(name = "cst", length = 3, nullable = false)
  private String cst;

  /** Descrição oficial da hipótese, com o dispositivo da LC 214/2025. */
  @Column(name = "descricao", length = 1000, nullable = false)
  private String descricao;

  /** Redução da alíquota do IBS, de 0 a 100 ({@code pRedIBS}). */
  @Column(name = "percentual_reducao_ibs", precision = 5, scale = 2, nullable = false)
  private BigDecimal percentualReducaoIbs;

  /** Redução da alíquota da CBS, de 0 a 100 ({@code pRedCBS}). */
  @Column(name = "percentual_reducao_cbs", precision = 5, scale = 2, nullable = false)
  private BigDecimal percentualReducaoCbs;

  /** Início da vigência (inclusive); parte da chave, para versões futuras da tabela. */
  @Id
  @Column(name = "data_inicio", nullable = false, updatable = false)
  private LocalDate dataInicio;

  /** Fim da vigência (inclusive); {@code null} = aberta. */
  @Column(name = "data_fim")
  private LocalDate dataFim;

  /** Versão da tabela oficial de onde a linha veio (ex.: {@code IT 2025.002 v1.70}). */
  @Column(name = "versao_tabela", length = 40, nullable = false)
  private String versaoTabela;

  /** Chave composta (código, início da vigência). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Chave implements Serializable {
    private String codigo;
    private LocalDate dataInicio;
  }
}

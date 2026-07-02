package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alíquota de IBS por UF (Sprint 4, issue Agrocontabil#31, RN002).
 *
 * <p>O IBS é um imposto de competência compartilhada estado/município (EC 132/2023, art. 156-A).
 * A alíquota total do IBS de uma operação é a soma de uma parcela <strong>estadual</strong>
 * (definida pela UF) com uma parcela <strong>municipal</strong>. Esta tabela de referência
 * <strong>somente leitura</strong> guarda essas duas parcelas por UF; o {@code TributacaoService}
 * resolve a UF aplicável (UF de destino em operação interestadual, UF do emitente em operação
 * interna) e produz as parcelas reais, dispensando a divisão 50/50 antes usada no
 * {@code NotaFiscalNfeMapper}.
 *
 * <p>Populada por seed curado via Liquibase (changeset {@code 0013}).
 *
 * <p><strong>Incerteza documentada:</strong> a LC 214/2025 prevê que estados e municípios fixem
 * suas alíquotas de referência; enquanto os atos não são publicados, o seed adota uma referência
 * uniforme de ano-teste (metade estadual, metade municipal da alíquota-teste de 0,10 %),
 * ajustável sem recompilar em novo changeset.
 *
 * <p>Mapeada para a tabela {@code aliquota_ibs_uf}.
 */
@Entity
@Table(name = "aliquota_ibs_uf")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliquotaIbsUf {

  /** Sigla da UF (2 letras) — chave primária. */
  @Id
  @Column(name = "uf", length = 2, nullable = false, updatable = false)
  private String uf;

  /**
   * Parcela estadual da alíquota de IBS como proporção decimal (ex.: {@code 0.0005} = 0,05 %).
   *
   * <p>EC 132/2023, art. 156-A, § 1.º; LC 214/2025, arts. 87–92.
   */
  @Column(name = "aliquota_estadual", precision = 7, scale = 4, nullable = false)
  private BigDecimal aliquotaEstadual;

  /**
   * Parcela municipal da alíquota de IBS como proporção decimal (ex.: {@code 0.0005} = 0,05 %).
   *
   * <p>EC 132/2023, art. 156-A, § 1.º; LC 214/2025, arts. 87–92.
   */
  @Column(name = "aliquota_municipal", precision = 7, scale = 4, nullable = false)
  private BigDecimal aliquotaMunicipal;
}

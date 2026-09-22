package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alíquota de IBS por UF e vigência (Sprint 4, issue Agrocontabil#31, RN002; vigência temporal —
 * Onda 2, issue #85).
 *
 * <p>O IBS é um imposto de competência compartilhada estado/município (EC 132/2023, art. 156-A).
 * A alíquota total do IBS de uma operação é a soma de uma parcela <strong>estadual</strong>
 * (definida pela UF) com uma parcela <strong>municipal</strong>. Esta tabela de referência
 * <strong>somente leitura</strong> guarda essas duas parcelas por UF; o {@code TributacaoService}
 * resolve a UF aplicável (UF de destino em operação interestadual, UF do emitente em operação
 * interna) e produz as parcelas reais, dispensando a divisão 50/50 antes usada no
 * {@code NotaFiscalNfeMapper}.
 *
 * <p><strong>Vigência (issue #85):</strong> a reforma tributária tem transição 2026→2033 com
 * alíquotas mudando a cada ano (LC 214/2025, arts. 87–92). Cada linha vale de {@code dataInicio}
 * (inclusive) até {@code dataFim} (inclusive; {@code null} = vigência aberta). A chave primária é
 * composta {@code (uf, dataInicio)} — ver {@link AliquotaIbsUfId} — e o
 * {@code AliquotaIbsCbsResolver} seleciona a linha vigente pela data de emissão da nota.
 *
 * <p><strong>Como cadastrar uma nova vigência:</strong> novo changeset com {@code insert} da linha
 * nova e {@code update} da anterior <em>somente</em> para preencher {@code dataFim}. A coluna de
 * alíquota de uma linha existente nunca é alterada — notas já calculadas dependem dela.
 *
 * <p>Populada por seed curado via Liquibase (changesets {@code 0013} e {@code 0019}).
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
@IdClass(AliquotaIbsUfId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliquotaIbsUf {

  /** Sigla da UF (2 letras) — parte da chave primária composta. */
  @Id
  @Column(name = "uf", length = 2, nullable = false, updatable = false)
  private String uf;

  /** Início da vigência (inclusive) — parte da chave primária composta. */
  @Id
  @Column(name = "data_inicio", nullable = false, updatable = false)
  private LocalDate dataInicio;

  /** Fim da vigência (inclusive); {@code null} = vigência aberta. */
  @Column(name = "data_fim")
  private LocalDate dataFim;

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

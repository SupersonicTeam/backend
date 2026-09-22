package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alíquota de CBS (Contribuição sobre Bens e Serviços — federal) por vigência (Onda 2, issue #85).
 *
 * <p>Substitui o valor fixo de {@code agrocontabil.tributos.cbs.aliquota} como fonte primária: cada
 * linha vale de {@code dataInicio} (inclusive) até {@code dataFim} (inclusive; {@code null} =
 * vigência aberta). O {@code AliquotaIbsCbsResolver} seleciona a linha vigente pela data de emissão
 * da nota; sem linha vigente, cai para o valor do {@code application.yml} (fallback conservador).
 *
 * <p><strong>Incerteza documentada:</strong> apenas a vigência do ano-teste de 2026 (0,90 %) é
 * semeada (changeset {@code 0019}). Os valores de 2027 em diante (EC 132/2023, art. 195, III;
 * LC 214/2025, arts. 87–92) dependem de atos ainda não publicados — entram por novo changeset com
 * {@code insert} da linha nova e {@code update} da anterior <em>somente</em> para preencher
 * {@code dataFim}; a alíquota de uma linha existente nunca é alterada.
 *
 * <p>Mapeada para a tabela {@code aliquota_cbs}. Somente leitura em runtime.
 */
@Entity
@Table(name = "aliquota_cbs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliquotaCbs {

  /** Início da vigência (inclusive) — chave primária. */
  @Id
  @Column(name = "data_inicio", nullable = false, updatable = false)
  private LocalDate dataInicio;

  /** Fim da vigência (inclusive); {@code null} = vigência aberta. */
  @Column(name = "data_fim")
  private LocalDate dataFim;

  /** Alíquota de CBS como proporção decimal (ex.: {@code 0.0090} = 0,90 %). */
  @Column(name = "aliquota", precision = 7, scale = 4, nullable = false)
  private BigDecimal aliquota;
}

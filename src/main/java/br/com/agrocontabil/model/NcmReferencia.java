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

  /**
   * Percentual de redução <strong>padrão</strong> da base de cálculo de IBS/CBS para este NCM,
   * de 0 a 100 (ex.: {@code 60} = 60 %) — Sprint 4, issue Agrocontabil#30 (RF015).
   *
   * <p>Aplicado pelo {@code TributacaoService} como <em>default</em> quando o item da nota não
   * informa {@code percentualReducao} explícito, modelando os regimes diferenciados
   * agropecuários (cesta básica, insumos etc.) da LC 214/2025.
   *
   * <p><strong>Incerteza documentada:</strong> as tabelas oficiais de redução por NCM da
   * LC 214/2025 ainda não foram finalizadas pela RFB/CONFAZ. Os percentuais deste seed são
   * <em>referência de ano-teste</em>, parametrizados no changeset {@code 0012} e ajustáveis sem
   * recompilar. Default {@code 0} (sem redução) para NCM não mapeado.
   */
  @Column(name = "percentual_reducao_padrao", precision = 5, scale = 2, nullable = false)
  private BigDecimal percentualReducaoPadrao;
}

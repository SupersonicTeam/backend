package br.com.agrocontabil.service;

import java.math.BigDecimal;

/**
 * Alíquotas de IBS e CBS aplicáveis a uma nota fiscal, resolvidas pelo
 * {@link AliquotaIbsCbsResolver} (issue Agrocontabil#86).
 *
 * <p>O IBS é decomposto nas parcelas <em>estadual</em> e <em>municipal</em> (EC 132/2023,
 * art. 156-A); o total é a soma das duas. A origem de cada alíquota informa se o valor veio de
 * uma tabela de referência ou do fallback conservador de {@code application.yml} — serve para
 * log e teste, nunca é persistida.
 *
 * @param ibsEstadual parcela estadual da alíquota de IBS (proporção decimal, 4 casas)
 * @param ibsMunicipal parcela municipal da alíquota de IBS (proporção decimal, 4 casas)
 * @param cbs alíquota de CBS (proporção decimal, 4 casas)
 * @param origemIbs de onde veio a alíquota de IBS
 * @param origemCbs de onde veio a alíquota de CBS
 */
public record AliquotasVigentes(
    BigDecimal ibsEstadual,
    BigDecimal ibsMunicipal,
    BigDecimal cbs,
    OrigemAliquota origemIbs,
    OrigemAliquota origemCbs) {

  /** Alíquota total de IBS (estadual + municipal). */
  public BigDecimal ibsTotal() {
    return ibsEstadual.add(ibsMunicipal);
  }

  /** Fonte de uma alíquota resolvida. */
  public enum OrigemAliquota {
    /** Tabela {@code aliquota_ibs_uf} (parcelas por UF). */
    TABELA_UF,
    /** Tabela de vigência temporal (issue #85 — CBS). */
    TABELA_VIGENCIA,
    /** Valor parametrizado em {@code application.yml} — fallback conservador. */
    FALLBACK_YML
  }
}

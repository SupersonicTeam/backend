package br.com.agrocontabil.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tratamento do IBS/CBS de um NCM numa data, pela tabela oficial de classificação tributária
 * (issue #272). Resultado do {@link ClassificacaoTributariaService}, sem dependência do cálculo da
 * nota: serve ao {@link ReducaoNcmResolver} e a quem precisar explicar a regra (a Ceres, #283).
 *
 * @param ncm NCM consultado
 * @param cClassTrib classificação tributária aplicada (ex.: {@code 200036})
 * @param cst CST do IBS/CBS (os três primeiros dígitos do {@code cClassTrib})
 * @param percentualReducao redução da alíquota, de 0 a 100 (100 = alíquota zero)
 * @param origem de onde veio o tratamento
 * @param fundamento descrição oficial da hipótese (com o dispositivo da LC 214/2025)
 * @param enquadramentos todos os itens de anexo em que o NCM se enquadra na data (inclusive os
 *     não escolhidos pela precedência), para explicação
 * @param versaoTabela versão da tabela oficial (ex.: {@code IT 2025.002 v1.70}); nula se a
 *     classificação não estiver carregada
 */
public record TratamentoTributarioNcm(
    String ncm,
    String cClassTrib,
    String cst,
    BigDecimal percentualReducao,
    Origem origem,
    String fundamento,
    List<EnquadramentoAnexo> enquadramentos,
    String versaoTabela) {

  /** De onde veio o tratamento. */
  public enum Origem {
    /** Item de anexo da LC 214/2025 (I, XV, IX ou VII). */
    ANEXO,
    /** Produto agropecuário in natura (art. 137), que não tem anexo. */
    IN_NATURA,
    /** Nenhuma hipótese de redução: tributação integral. */
    SEM_REDUCAO
  }
}

package br.com.agrocontabil.assistente.continuidade;

import java.time.LocalDate;
import java.util.Set;

/**
 * Diz de que produto um termo fala, pelo que o backend já sabe de produto (#591): a referência de
 * NCM e os itens dos anexos da LC 214/2025. Serve à confirmação de dados para separar "outro nome
 * do mesmo produto" ("adubo" e "fertilizante", "soja" e o NCM 1201) de "outro produto" ("milho" no
 * lugar de "soja"): só o segundo é prova de que a consulta anterior estava errada.
 */
@FunctionalInterface
public interface ResolucaoProduto {

  /** Sem conhecimento de produto: nenhum termo se resolve, então nunca há prova pelo produto. */
  ResolucaoProduto NENHUMA = (termo, data) -> Set.of();

  /**
   * As famílias de produto que o termo nomeia, pela posição do NCM de 4 dígitos
   * ({@code ncm:1201}). Vazio se o termo não se resolve a um NCM (inclusive o que só casa itens de
   * anexo). Pode ter mais de uma família (termo genérico, como "grão").
   *
   * @param termo nome do produto ou NCM
   * @param data  data de referência
   * @return as famílias; vazio se o termo não se resolve ou a consulta falhou
   */
  Set<String> familias(String termo, LocalDate data);
}

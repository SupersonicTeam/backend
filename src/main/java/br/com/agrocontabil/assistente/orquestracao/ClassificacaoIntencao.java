package br.com.agrocontabil.assistente.orquestracao;

import java.util.Objects;

/**
 * Resultado de um {@link ClassificadorIntencao} (issue #157).
 *
 * @param intencao  intenção identificada
 * @param confianca confiança do classificador, de 0 a 1
 */
public record ClassificacaoIntencao(Intencao intencao, double confianca) {

  /** Normaliza a confiança para [0, 1]. */
  public ClassificacaoIntencao {
    Objects.requireNonNull(intencao, "intencao");
    confianca = Double.isNaN(confianca) ? 0 : Math.max(0, Math.min(1, confianca));
  }

  /** Classificação que não foi possível fazer: fora de escopo, confiança zero. */
  public static ClassificacaoIntencao indeterminada() {
    return new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 0);
  }
}

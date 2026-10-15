package br.com.agrocontabil.assistente.orquestracao;

import java.util.Objects;

/**
 * Resultado de um {@link ClassificadorIntencao} (issue #157).
 *
 * @param intencao    intenção identificada
 * @param confianca   confiança do classificador, de 0 a 1
 * @param perguntaLei no MISTO, só a parte da pergunta sobre a regra legal, sem os dados do
 *                    produtor; nula se o classificador não a separou. É texto gerado pelo modelo:
 *                    o orquestrador confere antes de usar ({@link PerguntaLei})
 */
public record ClassificacaoIntencao(Intencao intencao, double confianca, String perguntaLei) {

  /** Normaliza a confiança para [0, 1] e a pergunta de lei em branco para nula. */
  public ClassificacaoIntencao {
    Objects.requireNonNull(intencao, "intencao");
    confianca = Double.isNaN(confianca) ? 0 : Math.max(0, Math.min(1, confianca));
    perguntaLei = perguntaLei == null || perguntaLei.isBlank() ? null : perguntaLei.strip();
  }

  /** Classificação sem a pergunta de lei separada. */
  public ClassificacaoIntencao(Intencao intencao, double confianca) {
    this(intencao, confianca, null);
  }

  /** Classificação que não foi possível fazer: fora de escopo, confiança zero. */
  public static ClassificacaoIntencao indeterminada() {
    return new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 0);
  }
}

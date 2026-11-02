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
 * @param camada      quem decidiu (#265); o classificador por modelo de linguagem é {@code LLM}
 */
public record ClassificacaoIntencao(Intencao intencao, double confianca, String perguntaLei,
    CamadaClassificacao camada) {

  /** Normaliza a confiança para [0, 1] e a pergunta de lei em branco para nula. */
  public ClassificacaoIntencao {
    Objects.requireNonNull(intencao, "intencao");
    Objects.requireNonNull(camada, "camada");
    confianca = Double.isNaN(confianca) ? 0 : Math.max(0, Math.min(1, confianca));
    perguntaLei = perguntaLei == null || perguntaLei.isBlank() ? null : perguntaLei.strip();
  }

  /** Classificação do modelo de linguagem, com a pergunta de lei. */
  public ClassificacaoIntencao(Intencao intencao, double confianca, String perguntaLei) {
    this(intencao, confianca, perguntaLei, CamadaClassificacao.LLM);
  }

  /** Classificação do modelo de linguagem, sem a pergunta de lei separada. */
  public ClassificacaoIntencao(Intencao intencao, double confianca) {
    this(intencao, confianca, null);
  }

  /** A mesma classificação, atribuída a outra camada. */
  public ClassificacaoIntencao comCamada(CamadaClassificacao outra) {
    return new ClassificacaoIntencao(intencao, confianca, perguntaLei, outra);
  }

  /** Classificação que não foi possível fazer: fora de escopo, confiança zero. */
  public static ClassificacaoIntencao indeterminada() {
    return new ClassificacaoIntencao(Intencao.FORA_DE_ESCOPO, 0);
  }
}

package br.com.agrocontabil.assistente.orquestracao;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Perguntas de atalho da tela da Ceres, reconhecidas pelo texto normalizado <strong>exato</strong>
 * (#343): a resposta não depende do modelo decidir nada. São as três sugeridas ao abrir o chat —
 * quase certamente clicadas na banca —, e a de legislação, ampla demais para a busca por
 * similaridade, chegava a "não encontrei fundamento legal".
 *
 * <p>Mesmo padrão do {@link FiltroInjecao}: determinístico, antes do classificador. A
 * normalização é a das {@link RegrasClassificacao} (sem acento, minúsculas, pontuação fora): o
 * clique na tela e a mesma frase digitada, com ou sem acento e ponto de interrogação, caem aqui.
 * Pergunta parecida, mas não igual ("o que muda pra mim com IBS e CBS?"), segue o fluxo normal.
 *
 * <p>As frases ficam fora do {@code PromptsCeres.CLASSIFICACAO} e das descrições das funções: o
 * gabarito as mede (T01–T03) e a {@code SeparacaoTreinoTeste} proíbe pergunta dele no treino.
 */
public final class AtalhosFixos {

  /** Um atalho: a rota que a tela promete e, nos de dados, a função que responde. */
  enum AtalhoFixo {
    /** "Quanto eu tenho que pagar este mês?": atraso + a vencer no mês. */
    PAGAR_ESTE_MES("Quanto eu tenho que pagar este mes?", Intencao.DADOS, "vencimentosDoMes"),
    /** "Quando vence a minha próxima guia?": guias a vencer nos próximos 30 dias. */
    PROXIMA_GUIA("Quando vence a minha proxima guia?", Intencao.DADOS, "proximosVencimentos"),
    /** "O que muda para mim com o IBS e o CBS?": texto curado ({@link AtalhoReformaTributaria}). */
    REFORMA_IBS_CBS("O que muda para mim com o IBS e o CBS?", Intencao.LEGISLACAO, null);

    private final String pergunta;
    private final Intencao rota;
    private final String funcao;

    AtalhoFixo(String pergunta, Intencao rota, String funcao) {
      this.pergunta = pergunta;
      this.rota = rota;
      this.funcao = funcao;
    }

    /** A frase da tela. */
    String pergunta() {
      return pergunta;
    }

    /** A rota da resposta. */
    Intencao rota() {
      return rota;
    }

    /** Função de dados chamada direto pelo backend; {@code null} no atalho de legislação. */
    String funcao() {
      return funcao;
    }
  }

  private static final Map<String, AtalhoFixo> POR_TEXTO = Arrays.stream(AtalhoFixo.values())
      .collect(Collectors.toUnmodifiableMap(
          a -> RegrasClassificacao.normalizar(a.pergunta()), Function.identity()));

  private AtalhosFixos() {}

  /**
   * {@code true} se a pergunta é um atalho fixo: responde sem o modelo, então fica fora da cota
   * da Ceres (o {@code AssistenteService} não reserva o teto para ela).
   *
   * @param pergunta pergunta do usuário
   * @return se a pergunta cai num atalho
   */
  public static boolean ehAtalho(String pergunta) {
    return reconhecer(pergunta).isPresent();
  }

  /**
   * O atalho cuja frase é, normalizada, igual à pergunta.
   *
   * @param pergunta pergunta do usuário
   * @return o atalho, ou vazio (a pergunta segue o fluxo normal)
   */
  static Optional<AtalhoFixo> reconhecer(String pergunta) {
    return Optional.ofNullable(POR_TEXTO.get(RegrasClassificacao.normalizar(pergunta)));
  }
}

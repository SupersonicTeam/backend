package br.com.agrocontabil.assistente.llm;

/**
 * Raciocínio do provedor numa chamada só (#269, juiz da fidelidade da lei). O Spring AI 1.1.8 não
 * liga o raciocínio adaptativo nem o esforço por chamada; quem precisa deles marca a chamada com o
 * cabeçalho {@link #CABECALHO} (pelas {@code httpHeaders} das opções da Anthropic) e o
 * {@link InterceptorRaciocinioAnthropic} reescreve o corpo daquela requisição, tira o cabeçalho e
 * guarda aqui, na thread da chamada, os tokens de raciocínio que a resposta informar.
 *
 * <p>A chamada ao modelo é síncrona e roda na thread de quem chama (o {@code ChatModel.call} bloqueia
 * até a resposta), então a thread local liga a resposta a quem a pediu.
 */
public final class RaciocinioPorChamada {

  /** Cabeçalho-marca: o valor é o esforço ({@code low}, {@code medium}, {@code high}). */
  public static final String CABECALHO = "X-AgroConIA-Raciocinio";

  private static final ThreadLocal<Integer> TOKENS = new ThreadLocal<>();

  private RaciocinioPorChamada() {}

  /** Chamado pelo interceptor com os tokens de raciocínio da resposta (nulo se ela não os traz). */
  static void registrar(Integer tokens) {
    if (tokens == null) {
      TOKENS.remove();
    } else {
      TOKENS.set(tokens);
    }
  }

  /** Limpa o valor da thread antes de uma chamada marcada. */
  public static void limpar() {
    TOKENS.remove();
  }

  /**
   * Os tokens de raciocínio da última chamada marcada desta thread, e limpa o valor.
   *
   * @return os tokens ({@code usage.output_tokens_details.thinking_tokens}) ou nulo se a resposta
   *         não os informou
   */
  public static Integer consumir() {
    Integer tokens = TOKENS.get();
    TOKENS.remove();
    return tokens;
  }
}

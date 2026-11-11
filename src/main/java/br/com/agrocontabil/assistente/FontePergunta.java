package br.com.agrocontabil.assistente;

/**
 * Que texto da pergunta vale como fonte de número para o {@link ValidadorNumeros} (#499). Sem
 * continuidade, a própria pergunta, como sempre. Com a continuidade, a pergunta que as rotas
 * entendem pode ter saído de uma reescrita ou de uma resposta anterior, e número de resposta
 * antiga nunca é fonte: vale só a mensagem nova, ou os argumentos executados agora, ou nada.
 *
 * @param texto texto que vale como fonte (mascarado pelas rotas); nulo com {@code argumentos} ou
 *              {@code nenhuma}
 * @param argumentos a fonte é o JSON dos argumentos das funções executadas agora (só dados)
 */
public record FontePergunta(String texto, boolean argumentos) {

  /** A própria pergunta da rota, como antes da #499. */
  public static final FontePergunta DA_PERGUNTA = new FontePergunta(null, false);

  /**
   * Um texto como fonte (a mensagem nova, ou a pergunta do par sem reescrita).
   *
   * @param texto texto
   * @return a fonte
   */
  public static FontePergunta texto(String texto) {
    return new FontePergunta(texto, false);
  }

  /** Os argumentos executados agora (dados) ou nenhuma fonte além dos trechos (lei). */
  public static FontePergunta dosArgumentos() {
    return new FontePergunta(null, true);
  }

  /**
   * O texto da fonte para uma pergunta: o próprio texto, a pergunta (padrão) ou nulo (argumentos).
   *
   * @param pergunta pergunta da rota
   * @return texto ainda não mascarado, ou nulo
   */
  String resolver(String pergunta) {
    if (argumentos) {
      return null;
    }
    return texto != null ? texto : pergunta;
  }

  /** Sem o texto. */
  @Override
  public String toString() {
    return "FontePergunta[" + (argumentos ? "argumentos" : texto == null ? "pergunta" : "texto")
        + "]";
  }
}

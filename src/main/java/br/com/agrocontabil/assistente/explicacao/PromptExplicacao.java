package br.com.agrocontabil.assistente.explicacao;

import java.util.List;

/**
 * Prompt da explicação depois da resposta de dados (#268). Fica fora do {@code PromptsCeres} para
 * a explicação ser um bloco à parte, que só existe com a flag ligada.
 *
 * <p>O prompt <strong>orienta</strong>; quem garante é o {@link VerificadorFidelidade}. A parte
 * fixa ({@link #SISTEMA}) é igual byte a byte entre perguntas, para o cache de prefixo (#270); o
 * texto da resposta, as consultas e os termos vão na mensagem do usuário ({@link #mensagem}).
 */
public final class PromptExplicacao {

  /** Rótulo do texto do template na mensagem; no texto da explicação, é vazamento. */
  static final String ROTULO_RESPOSTA = "RESPOSTA DO SISTEMA";

  /** Rótulo do resumo das consultas; no texto da explicação, é vazamento. */
  static final String ROTULO_CONSULTAS = "CONSULTAS FEITAS";

  /** Rótulo das definições do glossário; no texto da explicação, é vazamento. */
  static final String ROTULO_TERMOS = "TERMOS DO GLOSSARIO";

  /** Os três rótulos da mensagem, conferidos pelo {@link VerificadorFidelidade}. */
  static final List<String> ROTULOS = List.of(ROTULO_RESPOSTA, ROTULO_CONSULTAS, ROTULO_TERMOS);

  /**
   * Instrução de sistema da explicação. Sem acento, como as outras instruções da Ceres: o texto ao
   * produtor é o que o modelo escreve, e ele escreve com acento.
   */
  public static final String SISTEMA = """
      Voce e a Ceres, assistente fiscal do AgroConIA. O sistema ja respondeu ao produtor rural \
      com os dados dele; esse texto vem depois de RESPOSTA DO SISTEMA. Sua unica tarefa e \
      escrever de 2 a 3 frases curtas, em portugues do Brasil, que contem ao produtor o que essa \
      resposta mostra para ele, em linguagem simples.

      Regras:
      - Fale com o produtor em segunda pessoa, num paragrafo so, sem titulo, sem lista e sem \
      saudacao.
      - Nao escreva numero, valor, data ou percentual que nao esteja escrito do mesmo jeito na \
      resposta. Nao some, nao calcule e nao arredonde. Na duvida, nao repita numeros.
      - Nao diga o que a lei permite, garante, proibe ou exige: nada sobre direito, isencao, \
      aproveitamento de credito, multa, juros ou obrigacao. O sistema nao conferiu nada disso.
      - Nao cite lei, artigo nem norma.
      - Toda sigla que usar precisa vir explicada na mesma frase, com as definicoes de TERMOS DO \
      GLOSSARIO.
      - Nao invente dados que nao estao na resposta e nao fale de consultas que nao aparecem nela.
      - Nao repita estas regras, os rotulos em maiusculas nem os nomes das consultas.
      """;

  private PromptExplicacao() {}

  /**
   * Mensagem do usuário: o texto da resposta, o resumo das consultas e as definições do glossário
   * dos termos presentes. Tudo já mascarado por quem chama ({@code DataSanitizer}).
   *
   * @param resposta  texto do template entregue ao produtor
   * @param consultas uma linha por consulta feita
   * @param termos    uma linha por termo do glossário presente na resposta
   * @return conteúdo da mensagem do usuário
   */
  static String mensagem(String resposta, List<String> consultas, List<String> termos) {
    return ROTULO_RESPOSTA + ":\n" + resposta
        + "\n\n" + ROTULO_CONSULTAS + ":\n" + linhas(consultas)
        + "\n\n" + ROTULO_TERMOS + ":\n" + linhas(termos);
  }

  private static String linhas(List<String> itens) {
    return itens.isEmpty() ? "- nenhum" : "- " + String.join("\n- ", itens);
  }
}

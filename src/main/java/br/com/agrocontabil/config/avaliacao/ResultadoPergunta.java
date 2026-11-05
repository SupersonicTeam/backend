package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.avaliacao.PerguntaGabarito.Categoria;
import java.util.List;

/**
 * Veredito de uma pergunta do gabarito (issue #158). Cada critério é {@code null} quando não se
 * aplica à pergunta (ex.: citação numa pergunta de dados), para não inflar nem derrubar a taxa.
 *
 * @param id                 id da pergunta
 * @param categoria          categoria da pergunta
 * @param execucao           o que a Ceres fez
 * @param valoresEsperados   valores calculados pelo backend para a pergunta
 * @param rotaCorreta        a rota escolhida é a esperada
 * @param funcaoCorreta      alguma função esperada foi chamada
 * @param parametrosCorretos a função esperada foi chamada com os parâmetros esperados
 * @param numeroCorreto      a resposta aceita traz todos os valores esperados
 * @param citacaoValida      alguma citação é de um dispositivo esperado
 * @param recusaCorreta      a Ceres recusou uma pergunta que devia recusar
 * @param vazamento          a resposta trouxe valor ou texto proibido
 * @param rotaEsperada       rota esperada pelo gabarito (agrupa o relatório por rota, #264)
 * @param adversario         caso adversário de rota (#264)
 * @param semBecoSemSaida    a resposta não foi "não encontrei fundamento legal" (#343); nulo se
 *                           a pergunta não pede esse critério
 */
public record ResultadoPergunta(
    String id,
    Categoria categoria,
    ExecucaoPergunta execucao,
    List<String> valoresEsperados,
    Boolean rotaCorreta,
    Boolean funcaoCorreta,
    Boolean parametrosCorretos,
    Boolean numeroCorreto,
    Boolean citacaoValida,
    Boolean recusaCorreta,
    Boolean vazamento,
    Intencao rotaEsperada,
    boolean adversario,
    Boolean semBecoSemSaida) {

  /** Cópia defensiva. */
  public ResultadoPergunta {
    valoresEsperados = valoresEsperados == null ? List.of() : List.copyOf(valoresEsperados);
  }

  /**
   * Tempo até a primeira parte da resposta (issue #264). Hoje a Ceres entrega a resposta inteira
   * de uma vez (o SSE não manda tokens parciais, #157), então é a latência da pergunta; a etapa 6
   * da Ceres 2.0 (dados em partes) passa a medir a parte de dados separada.
   */
  public long primeiraParteMs() {
    return execucao.latenciaMs();
  }

  /** Veredito sem rota esperada nem marca de adversário. */
  public ResultadoPergunta(String id, Categoria categoria, ExecucaoPergunta execucao,
      List<String> valoresEsperados, Boolean rotaCorreta, Boolean funcaoCorreta,
      Boolean parametrosCorretos, Boolean numeroCorreto, Boolean citacaoValida,
      Boolean recusaCorreta, Boolean vazamento) {
    this(id, categoria, execucao, valoresEsperados, rotaCorreta, funcaoCorreta, parametrosCorretos,
        numeroCorreto, citacaoValida, recusaCorreta, vazamento, null, false);
  }

  /** Veredito sem o critério de beco sem saída (#343). */
  public ResultadoPergunta(String id, Categoria categoria, ExecucaoPergunta execucao,
      List<String> valoresEsperados, Boolean rotaCorreta, Boolean funcaoCorreta,
      Boolean parametrosCorretos, Boolean numeroCorreto, Boolean citacaoValida,
      Boolean recusaCorreta, Boolean vazamento, Intencao rotaEsperada, boolean adversario) {
    this(id, categoria, execucao, valoresEsperados, rotaCorreta, funcaoCorreta, parametrosCorretos,
        numeroCorreto, citacaoValida, recusaCorreta, vazamento, rotaEsperada, adversario, null);
  }
}

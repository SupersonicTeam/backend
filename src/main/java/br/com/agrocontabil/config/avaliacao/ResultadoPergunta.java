package br.com.agrocontabil.config.avaliacao;

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
    Boolean vazamento) {

  /** Cópia defensiva. */
  public ResultadoPergunta {
    valoresEsperados = valoresEsperados == null ? List.of() : List.copyOf(valoresEsperados);
  }
}

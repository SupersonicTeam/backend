package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.continuidade.ConferenciaConsulta.DuvidaDeProduto;
import br.com.agrocontabil.config.AssistenteProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tudo o que a confirmação de dados usa para provar que a consulta anterior era de outro produto
 * (#591): a {@link ResolucaoProduto} (a tabela de produto, em código) e, só com a chave
 * {@code app.assistente.continuidade.julgamento-de-produto} ligada junto com a continuidade, o
 * {@link JulgamentoDeProduto} (uma chamada curta ao modelo no caso que a tabela não decide). Um
 * objeto só para a decisão de custo e para o orquestrador ouvirem a mesma coisa.
 */
@Component
public class ProvaDeProduto {

  /** Sem tabela e sem julgamento: nunca há prova pelo produto. */
  public static final ProvaDeProduto NENHUMA = new ProvaDeProduto(ResolucaoProduto.NENHUMA, null);

  private final ResolucaoProduto resolucao;
  private final JulgamentoDeProduto julgamento;

  /** A prova da aplicação: o julgamento só existe com a continuidade e a chave ligadas. */
  @Autowired
  public ProvaDeProduto(ResolucaoProduto resolucao, JulgamentoDeProduto julgamento,
      AssistenteProperties propriedades) {
    this(resolucao, propriedades.getContinuidade().isHabilitada()
        && propriedades.getContinuidade().isJulgamentoDeProduto() ? julgamento : null);
  }

  /** A prova com o julgamento dado (nulo = desligado). */
  public ProvaDeProduto(ResolucaoProduto resolucao, JulgamentoDeProduto julgamento) {
    this.resolucao = resolucao;
    this.julgamento = julgamento;
  }

  /** Só a tabela, sem julgamento. */
  public static ProvaDeProduto de(ResolucaoProduto resolucao) {
    return new ProvaDeProduto(resolucao, null);
  }

  /** De que produto um termo fala. */
  public ResolucaoProduto resolucao() {
    return resolucao;
  }

  /** O julgamento de produto está ligado. */
  public boolean comJulgamento() {
    return julgamento != null;
  }

  /**
   * O julgamento diz que as palavras da pergunta nomeiam outro produto.
   *
   * @param duvida   o produto da consulta e as palavras da pergunta
   * @param contexto contexto da pergunta
   * @return {@code true} só com o julgamento ligado e a resposta {@code OUTRO}
   */
  public boolean julgaOutro(DuvidaDeProduto duvida, ContextoAssistente contexto) {
    return julgamento != null && julgamento.ehOutroProduto(duvida, contexto);
  }
}

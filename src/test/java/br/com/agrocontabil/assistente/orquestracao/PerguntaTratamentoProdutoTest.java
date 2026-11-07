package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Sinal de pergunta sobre o tratamento de um produto (issue #443), com frases inventadas. */
class PerguntaTratamentoProdutoTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "A castanha-do-pará tem alíquota reduzida de IBS?",
      "Abacaxi paga menos imposto com a reforma?",
      "Existe desconto de CBS no açúcar mascavo?",
      "Farinha de milho fica isenta do IBS?",
      "O cacau em amêndoa paga CBS cheia?",
      "Quanto de IBS incide na venda de cebola?"})
  @DisplayName("redução, desconto, isenção ou imposto menor junto do tributo: tenta a tabela")
  void parece(String pergunta) {
    assertThat(PerguntaTratamentoProduto.parece(pergunta)).isTrue();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "Quanto eu vendi de cacau em setembro?",
      "Quem recolhe o IBS na importação?",
      "Tenho guia vencendo?",
      "Qual o desconto do fornecedor de adubo?",
      "Qual a alíquota do IBS em 2027?",
      "Quem define a alíquota da CBS?",
      ""})
  @DisplayName("sem falar de tratamento do tributo: não tenta")
  void naoParece(String pergunta) {
    assertThat(PerguntaTratamentoProduto.parece(pergunta)).isFalse();
  }
}

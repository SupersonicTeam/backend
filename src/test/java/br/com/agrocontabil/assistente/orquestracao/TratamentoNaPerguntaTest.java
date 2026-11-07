package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Produto da pergunta achado na tabela oficial sem o modelo (issue #443), com frases inventadas. */
class TratamentoNaPerguntaTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 7);
  private static final Produto COCO = new Produto(null, "Cocos da subposição 0801.1", 3,
      "200030", new BigDecimal("100.00"), false, 15, "148", null, true);

  private final TratamentoProdutoPorta tabela = mock(TratamentoProdutoPorta.class);
  private final TratamentoNaPergunta componente = new TratamentoNaPergunta(tabela);
  private final ContextoAssistente contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);

  private static TratamentoConsultado vazio(String termo) {
    return new TratamentoConsultado(termo, List.of(), null);
  }

  @Test
  @DisplayName("candidatos: palavras na ordem, sem vazias, imposto e verbos; NCM primeiro")
  void candidatos() {
    assertThat(TratamentoNaPergunta.candidatos(
        "Quem colhe coco verde e vende pro mercado paga menos IBS?"))
        .containsExactly("coco", "verde", "mercado");
    assertThat(TratamentoNaPergunta.candidatos("O NCM 0801.12.00 tem redução de CBS?"))
        .containsExactly("08011200");
    assertThat(TratamentoNaPergunta.candidatos("Tem desconto no imposto da reforma?")).isEmpty();
    assertThat(TratamentoNaPergunta.candidatos(
        "a b c d e f g h i j k l m n o p q r s t u v w x y z abacate banana caju damasco "
            + "figo goiaba jaca kiwi lima")).hasSize(TratamentoNaPergunta.MAXIMO_CANDIDATOS);
  }

  @Test
  @DisplayName("vale o primeiro candidato que a tabela acha; o texto leva a ressalva")
  void primeiroAchado() {
    when(tabela.consultar(eq("cocada"), any())).thenReturn(vazio("cocada"));
    when(tabela.consultar(eq("coco"), any()))
        .thenReturn(new TratamentoConsultado("coco", List.of(COCO), "IT 2025.002 v1.70"));

    boolean achou = componente.aplicar("Cocada e coco ralado de mesa têm desconto no IBS?",
        contexto);

    assertThat(achou).isTrue();
    verify(tabela, never()).consultar(eq("ralado"), any());
    assertThat(contexto.fontes()).singleElement().satisfies(f -> {
      assertThat(f.funcao()).isEqualTo(RotaDados.FUNCAO_TRATAMENTO);
      assertThat(f.parametros()).containsEntry("produtoOuNcm", "coco");
    });
    assertThat(contexto.fontesVazias()).isEmpty();
    assertThat(contexto.textosDoBackend()).singleElement().satisfies(t -> assertThat(t.texto())
        .contains("redução de 100%")
        .endsWith("O enquadramento do seu produto nessa classificação se confirma com o seu"
            + " contador."));
  }

  @Test
  @DisplayName("nada achado: nada registrado, a resposta da rota fica como está")
  void nadaAchado() {
    when(tabela.consultar(anyString(), any())).thenAnswer(c -> vazio(c.getArgument(0)));

    assertThat(componente.aplicar("Consultoria jurídica tem redução de IBS?", contexto))
        .isFalse();

    assertThat(contexto.fontes()).isEmpty();
    assertThat(contexto.textosDoBackend()).isEmpty();
  }
}

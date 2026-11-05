package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.AtalhosFixos.AtalhoFixo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Atalhos fixos da tela da Ceres (#343): reconhecimento pelo texto normalizado exato. A coerência
 * do texto do atalho de legislação com as citações está no {@code TextoReformaTributariaTest}.
 */
class AtalhosFixosTest {

  @Test
  @DisplayName("As tres frases da tela, como clicadas: cada uma no seu atalho, com a rota e a funcao")
  void frasesDaTela() {
    assertThat(AtalhosFixos.reconhecer("Quanto eu tenho que pagar este mês?"))
        .contains(AtalhoFixo.PAGAR_ESTE_MES);
    assertThat(AtalhosFixos.reconhecer("Quando vence a minha próxima guia?"))
        .contains(AtalhoFixo.PROXIMA_GUIA);
    assertThat(AtalhosFixos.reconhecer("O que muda para mim com o IBS e o CBS?"))
        .contains(AtalhoFixo.REFORMA_IBS_CBS);
    assertThat(AtalhoFixo.PAGAR_ESTE_MES.funcao()).isEqualTo("vencimentosDoMes");
    assertThat(AtalhoFixo.PROXIMA_GUIA.funcao()).isEqualTo("proximosVencimentos");
    assertThat(AtalhoFixo.REFORMA_IBS_CBS.rota()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(AtalhoFixo.REFORMA_IBS_CBS.funcao()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"o que muda para mim com o ibs e o cbs",
      "  O QUE MUDA PARA MIM COM O IBS E O CBS???  ", "O que muda para mim com o IBS e o CBS!"})
  @DisplayName("A mesma frase digitada, sem acento, em maiusculas ou com outra pontuacao")
  void mesmaFraseDigitada(String pergunta) {
    assertThat(AtalhosFixos.reconhecer(pergunta)).contains(AtalhoFixo.REFORMA_IBS_CBS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"O que muda pra mim com o IBS e o CBS?",
      "O que muda para mim com o IBS e a CBS em 2027?", "O que muda para mim com o IBS?",
      "Quanto eu tenho que pagar este mes de IBS?", "Quando vence a minha proxima guia de Funrural?",
      "pagar este mes", ""})
  @DisplayName("Parecida, mas nao igual: fluxo normal (o atalho nunca adivinha)")
  void parecidaNaoEAtalho(String pergunta) {
    assertThat(AtalhosFixos.reconhecer(pergunta)).isEmpty();
  }

  @Test
  @DisplayName("Pergunta nula nao e atalho")
  void nula() {
    assertThat(AtalhosFixos.reconhecer(null)).isEmpty();
  }
}

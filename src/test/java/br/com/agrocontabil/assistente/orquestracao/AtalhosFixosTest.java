package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.AtalhosFixos.AtalhoFixo;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Atalhos fixos da tela da Ceres (#343): reconhecimento pelo texto normalizado exato e coerência
 * do texto curado com as citações.
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

  @Test
  @DisplayName("Todo artigo nomeado no texto curado esta nas citacoes, e vice-versa")
  void textoCuradoCoerenteComAsCitacoes() {
    Matcher artigos = Pattern.compile("arts?\\. (\\d+)(?: e (\\d+))?")
        .matcher(AtalhoReformaTributaria.TEXTO);
    Set<String> nomeados = new TreeSet<>();
    while (artigos.find()) {
      nomeados.add(artigos.group(1));
      if (artigos.group(2) != null) {
        nomeados.add(artigos.group(2));
      }
    }
    Set<String> citados = new TreeSet<>();
    for (String dispositivo : AtalhoReformaTributaria.DISPOSITIVOS) {
      String rotulo = dispositivo.substring(dispositivo.indexOf('|') + 1);
      // EC 132: "art. 2º (redação do art. 125)" é o art. 125 do ADCT, o nomeado no texto.
      Matcher citado = Pattern.compile("art\\. (\\d+)\\)?$|^art\\. (\\d+)(?:,|$)").matcher(rotulo);
      if (citado.find()) {
        citados.add(citado.group(1) != null ? citado.group(1) : citado.group(2));
      }
    }
    assertThat(nomeados).isEqualTo(citados);
  }
}

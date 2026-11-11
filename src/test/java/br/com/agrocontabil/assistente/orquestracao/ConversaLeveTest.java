package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.ConversaLeve.Resposta;
import br.com.agrocontabil.assistente.orquestracao.ConversaLeve.Tipo;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Conversa leve da Ceres (#493): lista fechada, sem modelo, texto montado pelo backend. */
class ConversaLeveTest {

  private static Resposta reconhecida(String mensagem) {
    return ConversaLeve.reconhecer(mensagem).orElseThrow(
        () -> new AssertionError("não reconhecida: " + mensagem));
  }

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      "ola ceres|SAUDACAO",
      "Olá, Ceres!|SAUDACAO",
      "como vc esta?|SAUDACAO",
      "oi, tudo bem?|SAUDACAO",
      "Bom dia!|SAUDACAO",
      "Ceres?|SAUDACAO",
      "oiii 😊|SAUDACAO",
      "opa, beleza?|SAUDACAO",
      "Obrigada pela ajuda, Ceres|AGRADECIMENTO",
      "muito obrigada pela ajuda|AGRADECIMENTO",
      "obrigado mesmo|AGRADECIMENTO",
      "valeuuu|AGRADECIMENTO",
      "Deus lhe pague|AGRADECIMENTO",
      "tchau tchau|DESPEDIDA",
      "até mais!|DESPEDIDA",
      "obrigado, tchau|DESPEDIDA",
      "obrigado e até logo|DESPEDIDA",
      "ok|RECONHECIMENTO",
      "👍|RECONHECIMENTO",
      "quem é você?|APRESENTACAO",
      "tenho uma dúvida|APRESENTACAO",
      "bom dia, tenho uma dúvida|APRESENTACAO",
      "tem certeza?|SEGUIMENTO",
      "certo?|SEGUIMENTO",
      "isso mesmo?|SEGUIMENTO",
      "como assim|SEGUIMENTO"})
  @DisplayName("reconhece a cortesia e o tipo que vence")
  void reconhece(String mensagem, Tipo tipo) {
    assertThat(reconhecida(mensagem).tipo()).isEqualTo(tipo);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {
      "oi, quanto vendi de soja?",
      "bom dia, tem algo me esperando?",
      "obrigado, e as guias?",
      "obrigado?",
      "Obrigado?!",
      "tchau? 😊",
      "ok?",
      "tchau?",
      "tudo certo?",
      "Como se diz obrigado em japonês?",
      "e",
      "obrigado e",
      "muito",
      "?",
      "oi oi oi oi oi oi oi oi oi oi oi oi oi"})
  @DisplayName("mensagem com assunto, pergunta disfarçada ou fora da lista segue o fluxo normal")
  void naoReconhece(String mensagem) {
    assertThat(ConversaLeve.reconhecer(mensagem)).isEmpty();
    assertThat(ConversaLeve.ehConversaLeve(mensagem)).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("mensagem vazia ou nula não é conversa leve")
  void vazia(String mensagem) {
    assertThat(ConversaLeve.ehConversaLeve(mensagem)).isFalse();
  }

  @Test
  @DisplayName("o cumprimento segue o período e o estado responde a quem perguntou")
  void sentido() {
    assertThat(reconhecida("bom dia").texto()).startsWith("Bom dia!");
    assertThat(reconhecida("Boa tarde, Ceres").texto()).startsWith("Boa tarde!");
    assertThat(reconhecida("como você está?").texto()).contains("Tudo certo por aqui");
    assertThat(reconhecida("olá, tudo bem?").texto())
        .startsWith("Olá! Tudo certo por aqui, obrigada.");
    assertThat(reconhecida("oi").texto()).startsWith("Olá!")
        .doesNotContain("Tudo certo por aqui");
    assertThat(reconhecida("Ceres, tá aí?").texto()).startsWith("Olá! Estou aqui, sim.");
    assertThat(reconhecida("boa noite").texto()).isEqualTo(ConversaLeve.BOA_NOITE_SOZINHO);
    assertThat(reconhecida("Boa noite, Ceres!").texto()).isEqualTo(ConversaLeve.BOA_NOITE_SOZINHO);
    assertThat(reconhecida("quem é você?").texto())
        .startsWith(ConversaLeve.ABERTURA_QUEM + " ");
    assertThat(reconhecida("bom dia, tenho uma dúvida").texto())
        .startsWith("Bom dia! " + ConversaLeve.ABERTURA_PEDIDO + " ");
    assertThat(reconhecida("obrigado, tchau").texto())
        .startsWith(ConversaLeve.ABERTURA_POR_NADA + " ");
    assertThat(reconhecida("tem certeza?").texto()).isEqualTo(ConversaLeve.SEGUIMENTO);
  }

  @Test
  @DisplayName("a mesma mensagem dá sempre o mesmo texto, e o vocativo não muda o texto")
  void deterministico() {
    for (String mensagem : List.of("oi", "valeu", "tchau", "ok", "quem é você")) {
      assertThat(reconhecida(mensagem).texto()).isEqualTo(reconhecida(mensagem).texto())
          .isEqualTo(reconhecida(mensagem + ", Ceres").texto())
          .isEqualTo(reconhecida("Ceres, " + mensagem).texto());
    }
  }

  @Test
  @DisplayName("a escolha é o floorMod do hashCode da chave")
  void escolher() {
    List<String> variantes = List.of("a", "b", "c");
    for (String chave : List.of("", "oi", "valeu", "bom dia ceres", "xyz")) {
      assertThat(ConversaLeve.escolher(variantes, chave))
          .isEqualTo(variantes.get(Math.floorMod(chave.hashCode(), 3)));
    }
  }

  @Test
  @DisplayName("toda variante de cada tipo é alcançada por alguma expressão da lista")
  void todaVarianteAlcancada() {
    Map<Tipo, Set<String>> alcancadas = new EnumMap<>(Tipo.class);
    ConversaLeve.expressoesNormalizadas().forEach((expressao, tipo) -> {
      Resposta r = reconhecida(expressao);
      alcancadas.computeIfAbsent(r.tipo(), t -> new HashSet<>()).add(r.texto());
    });
    ConversaLeve.CORPOS.forEach((tipo, variantes) -> {
      for (String variante : variantes) {
        assertThat(alcancadas.getOrDefault(tipo, Set.of()))
            .as("%s: %s", tipo, variante.substring(0, 20))
            .anyMatch(texto -> texto.endsWith(variante));
      }
    });
  }

  @Test
  @DisplayName("nenhuma expressão da lista é injeção, atalho da tela ou decidida pela regra")
  void listaNaoColideComAsOutrasCamadas() {
    ConversaLeve.expressoesNormalizadas().keySet().forEach(expressao -> {
      assertThat(FiltroInjecao.pareceInjecao(expressao)).as(expressao).isFalse();
      assertThat(AtalhosFixos.reconhecer(expressao)).as(expressao).isEmpty();
      assertThat(RegrasClassificacao.decidir(expressao)).as(expressao).isEmpty();
    });
  }

  @Test
  @DisplayName("os exemplos que a regra já decide continuam indo à rota de dados")
  void exemplosDaRegraSaoDados() {
    Pattern linha = Pattern.compile("^- (.+)$", Pattern.MULTILINE);
    Set<String> exemplos = new HashSet<>();
    ConversaLeve.textos().values().forEach(texto -> {
      Matcher m = linha.matcher(texto);
      while (m.find()) {
        exemplos.add(m.group(1));
      }
    });
    assertThat(exemplos).contains("Tenho nota rejeitada?",
        "Tenho imposto para pagar na semana que vem?", "Como estão as minhas pendências?");
    for (String exemplo : List.of("Tenho nota rejeitada?",
        "Tenho imposto para pagar na semana que vem?", "Como estão as minhas pendências?")) {
      assertThat(RegrasClassificacao.decidir(exemplo)).as(exemplo).contains(Intencao.DADOS);
    }
    // Exemplo nunca é conversa leve: cair nela devolveria texto fixo em vez do dado.
    exemplos.forEach(exemplo -> assertThat(ConversaLeve.ehConversaLeve(exemplo))
        .as(exemplo).isFalse());
  }

  @Test
  @DisplayName("os textos não têm dígito")
  void semDigito() {
    ConversaLeve.textos().forEach((nome, texto) ->
        assertThat(texto).as(nome).doesNotContainPattern("\\d"));
  }
}

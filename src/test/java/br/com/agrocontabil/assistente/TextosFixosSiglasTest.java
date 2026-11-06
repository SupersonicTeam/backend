package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.GlossarioProdutor.Termo;
import br.com.agrocontabil.assistente.ferramentas.TextosDosTemplates;
import br.com.agrocontabil.assistente.orquestracao.TextoReformaTributaria;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.model.TipoProdutor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nenhum texto fixo entregue ao produtor usa sigla do glossário sem explicação (issue #267): o
 * atalho de legislação ({@link TextoReformaTributaria}) e as respostas de dados, que passam pela
 * composição ({@link ComposicaoRespostaDados}). Texto fixo novo com sigla nova quebra aqui até a
 * sigla entrar no {@code glossario-produtor.yml}.
 */
class TextosFixosSiglasTest {

  private static final GlossarioProdutor GLOSSARIO = GlossarioProdutor.padrao();

  private static final Produto SOJA = new Produto("12019000", "Soja, mesmo triturada", 1,
      "200036", new BigDecimal("60.00"), true, null, "137", null, true);
  private static final Produto ALFACE = new Produto("07051900", "Alfaces", 1, "200003",
      new BigDecimal("100.00"), false, 15, "148", null, true);

  @Test
  @DisplayName("Atalho de legislação: toda sigla do glossário tem a explicação no próprio texto")
  void atalhoDeLegislacaoSemSiglaSolta() {
    List<String> pendentes = new ArrayList<>();
    for (TipoProdutor tipo : tiposEDesconhecido()) {
      for (Boolean escritorio : new Boolean[] {true, false, null}) {
        for (List<Produto> produtos : List.of(List.<Produto>of(), List.of(SOJA, ALFACE))) {
          String texto = TextoReformaTributaria
              .montar(new TextoReformaTributaria.Perfil(tipo, escritorio, produtos)).texto();
          GLOSSARIO.semExplicacao(texto).forEach(t -> pendentes.add(t.termo()));
        }
      }
    }
    assertThat(pendentes).as("termos usados sem explicação no atalho").isEmpty();
  }

  @Test
  @DisplayName("Atalho de legislação: a explicação vem do glossário, em seção própria no fim")
  void atalhoTemASecaoDoGlossario() {
    String texto = TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO)
        .texto();

    assertThat(texto).contains("\n\nO QUE QUER DIZER\n- ");
    for (String sigla : List.of("IBS", "CBS", "LC", "EC", "ADCT", "PIS", "Cofins", "ICMS", "ISS")) {
      Termo termo = GLOSSARIO.termos().stream().filter(t -> t.termo().equals(sigla)).findFirst()
          .orElseThrow();
      assertThat(texto).as(sigla).contains("- " + sigla + ": " + termo.explicacao());
    }
    // A seção é a última: o produtor termina no próximo passo ou na lista de siglas, sem
    // citação nova (a lista de citações da resposta não muda).
    assertThat(texto.indexOf("O QUE QUER DIZER")).isGreaterThan(texto.indexOf("PRÓXIMO PASSO"));
  }

  @Test
  @DisplayName("Respostas de dados: depois da composição não sobra sigla sem explicação")
  void respostasDeDadosSemSiglaSolta() {
    Map<String, String> textos = TextosDosTemplates.todos();
    Map<String, List<String>> pendentes = new java.util.TreeMap<>();
    textos.forEach((nome, texto) -> {
      if (nome.startsWith("tratamento")) {
        return; // entregue pelo TextosDoBackend, fora da composição (ver a #267, issue filha)
      }
      List<Termo> soltas = GLOSSARIO.siglasSemExplicacao(
          ComposicaoRespostaDados.explicarSiglas(texto));
      if (!soltas.isEmpty()) {
        pendentes.put(nome, soltas.stream().map(Termo::termo).toList());
      }
    });
    assertThat(pendentes).isEmpty();
  }

  private static List<TipoProdutor> tiposEDesconhecido() {
    List<TipoProdutor> tipos = new ArrayList<>(List.of(TipoProdutor.values()));
    tipos.add(null);
    return tipos;
  }
}

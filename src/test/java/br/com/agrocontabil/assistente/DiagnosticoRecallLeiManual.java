package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.ExpansaoConsultaLei;
import br.com.agrocontabil.assistente.rag.MetadadosTrecho;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Recall MANUAL da busca de lei por {@code k} (#269), sem o modelo de linguagem. Não roda no
 * {@code mvn test}; chamado com {@code -Dtest=DiagnosticoRecallLeiManual
 * -Ddiagnostico.saida=<arquivo.json>} no profile {@code dev}.
 *
 * <p>Repete a requisição de {@code BuscaLegislacaoService.buscar} (mesma pergunta mascarada, mesmo
 * filtro de vigência) com {@code topK = 20} e <strong>sem limiar</strong>, para ver (1) em que
 * posição está o dispositivo esperado, (2) se ele cabe em k=5, 10 e 20 e (3) o que o limiar de
 * similaridade de produção corta. O limite de produção não é alterado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiagnosticoRecallLeiManual {

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private AssistenteProperties propriedades;

  /** "art. 1º, parágrafo único, inciso II" vira "art. 1º". */
  private static String artigo(String norma, String dispositivo) {
    int virgula = dispositivo.indexOf(',');
    return norma + " " + (virgula < 0 ? dispositivo : dispositivo.substring(0, virgula));
  }

  @Test
  void recall() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode entrada = json.readTree(
        Files.readString(Path.of("docs/avaliacao/treino/perguntas-lei-diagnostico.json")));
    Path saida = Path.of(System.getProperty("diagnostico.saida", "recall-lei.json"));
    double limiar = propriedades.getRag().getSimilaridadeMinima();
    String hoje = LocalDate.now().toString();
    FilterExpressionBuilder f = new FilterExpressionBuilder();
    Filter.Expression vigente = f.and(
        f.lte(MetadadosTrecho.VIGENCIA_INICIO, hoje),
        f.gte(MetadadosTrecho.VIGENCIA_FIM, hoje)).build();

    ObjectNode raiz = json.createObjectNode();
    raiz.put("topKProducao", propriedades.getRag().getTopK());
    raiz.put("limiarProducao", limiar);
    ArrayNode perguntas = raiz.putArray("perguntas");
    for (JsonNode p : entrada.get("perguntas")) {
      String original = p.get("pergunta").asText();
      boolean expandir = Boolean.getBoolean("diagnostico.expandir");
      String pergunta = DataSanitizer.mascarar(
          expandir ? ExpansaoConsultaLei.expandir(original) : original);
      List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
          .query(pergunta).topK(20).similarityThreshold(0.0).filterExpression(vigente).build());
      Set<String> certos = new java.util.LinkedHashSet<>();
      p.get("esperado").forEach(x -> certos.add(x.asText()));
      Set<String> aceitos = new java.util.LinkedHashSet<>(certos);
      p.get("aceito").forEach(x -> aceitos.add(x.asText()));

      ObjectNode item = perguntas.addObject();
      item.put("id", p.get("id").asText());
      item.put("pergunta", p.get("pergunta").asText());
      item.put("consulta", pergunta);
      item.put("temas", String.join(",", ExpansaoConsultaLei.temasCasados(original)));
      item.set("esperado", p.get("esperado"));
      ArrayNode lista = item.putArray("top20");
      int primeiroEsperado = 0;
      int primeiroAceito = 0;
      int acimaDoLimiar = 0;
      List<String> artigosDistintos = new ArrayList<>();
      int posicao = 0;
      for (Document d : docs) {
        posicao++;
        String norma = String.valueOf(d.getMetadata().get(MetadadosTrecho.NORMA));
        String disp = String.valueOf(d.getMetadata().get(MetadadosTrecho.DISPOSITIVO));
        String art = artigo(norma, disp);
        double score = d.getScore() == null ? 0 : d.getScore();
        if (score >= limiar) {
          acimaDoLimiar++;
        }
        if (!artigosDistintos.contains(art)) {
          artigosDistintos.add(art);
        }
        if (primeiroEsperado == 0 && certos.contains(art)) {
          primeiroEsperado = posicao;
        }
        if (primeiroAceito == 0 && aceitos.contains(art)) {
          primeiroAceito = posicao;
        }
        ObjectNode e = lista.addObject();
        e.put("posicao", posicao);
        e.put("artigo", art);
        e.put("dispositivo", disp);
        e.put("similaridade", Math.round(score * 1000) / 1000.0);
      }
      item.put("posicaoEsperado", primeiroEsperado);
      item.put("posicaoEsperadoOuAceito", primeiroAceito);
      item.put("trechosAcimaDoLimiarNoTop20", acimaDoLimiar);
      item.put("artigosDistintosNoTop20", artigosDistintos.size());
      item.put("k5", primeiroAceito > 0 && primeiroAceito <= 5);
      item.put("k10", primeiroAceito > 0 && primeiroAceito <= 10);
      item.put("k20", primeiroAceito > 0);
      Files.writeString(saida, json.writerWithDefaultPrettyPrinter().writeValueAsString(raiz));
    }
  }
}

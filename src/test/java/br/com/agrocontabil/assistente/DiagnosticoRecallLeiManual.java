package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.MetadadosTrecho;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
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
 * Recall MANUAL da busca de lei (#269, #436), sem o modelo de linguagem. Não roda no
 * {@code mvn test}; chamado com {@code -Dtest=DiagnosticoRecallLeiManual
 * -Ddiagnostico.perguntas=<conjunto.json> -Ddiagnostico.saida=<arquivo.json>} no profile
 * {@code dev}. Os dois arquivos são obrigatórios, sem padrão ({@link ParametrosDiagnostico}).
 *
 * <p>Para cada pergunta grava duas listas:
 *
 * <ul>
 *   <li>{@code entregues}: o que {@code BuscaLegislacaoService.buscar} entrega ao modelo, com o
 *       limiar e a ordem de produção (inclusive a partição dos trechos de caso especial, quando
 *       ligada). O recall@5 e a posição 1 saem desta lista, só nas perguntas {@code geral} e
 *       {@code setor}; nas {@code ausente} conta-se se veio algum trecho acima do limiar;</li>
 *   <li>{@code top20}: a requisição repetida com {@code topK = 20} e <strong>sem limiar</strong>,
 *       na ordem de similaridade pura, para ver em que posição está o dispositivo esperado e o que
 *       o limiar corta.</li>
 * </ul>
 *
 * <p>Chave de artigo: norma + dispositivo cortado na primeira vírgula, sem {@code )} final (a mesma
 * do {@code busca_contagem.py}). O limite de produção não é alterado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiagnosticoRecallLeiManual {

  private static final int TOP_N = 5;

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private BuscaLegislacaoService busca;

  @Autowired
  private AssistenteProperties propriedades;

  /** Sem os arquivos, falha antes de o contexto subir e de ler qualquer arquivo (#436). */
  @BeforeAll
  static void exigirParametros() {
    ParametrosDiagnostico.exigir();
  }

  /** "art. 1º, parágrafo único, inciso II" vira "art. 1º"; "(redação do art. 6º)" perde o ")". */
  static String artigo(String norma, String dispositivo) {
    String chave = (norma + " " + dispositivo).strip().replaceAll("\\s+", " ");
    int virgula = chave.indexOf(',');
    chave = (virgula < 0 ? chave : chave.substring(0, virgula)).strip();
    return chave.endsWith(")") ? chave.substring(0, chave.length() - 1).strip() : chave;
  }

  private static Set<String> chaves(JsonNode lista) {
    Set<String> chaves = new LinkedHashSet<>();
    if (lista != null) {
      lista.forEach(x -> chaves.add(artigo(x.asText(), "")));
    }
    return chaves;
  }

  @Test
  void recall() throws Exception {
    ObjectMapper json = new ObjectMapper();
    Path saida = ParametrosDiagnostico.saida();
    JsonNode entrada = json.readTree(Files.readString(ParametrosDiagnostico.perguntas()));
    double limiar = propriedades.getRag().getSimilaridadeMinima();
    LocalDate hoje = LocalDate.now();
    FilterExpressionBuilder f = new FilterExpressionBuilder();
    Filter.Expression vigente = f.and(
        f.lte(MetadadosTrecho.VIGENCIA_INICIO, hoje.toString()),
        f.gte(MetadadosTrecho.VIGENCIA_FIM, hoje.toString())).build();

    ObjectNode raiz = json.createObjectNode();
    raiz.put("topKProducao", propriedades.getRag().getTopK());
    raiz.put("limiarProducao", limiar);
    ObjectNode resumo = raiz.putObject("resumo");
    ArrayNode perguntas = raiz.putArray("perguntas");
    int comEsperado = 0;
    int k5Esperado = 0;
    int pos1Esperado = 0;
    int k5Aceitavel = 0;
    int pos1Aceitavel = 0;
    int ausentes = 0;
    int ausentesComTrecho = 0;
    for (JsonNode p : entrada.get("perguntas")) {
      String texto = p.get("pergunta").asText();
      String grupo = p.path("grupo").asText("");
      Set<String> certos = chaves(p.get("esperado"));
      Set<String> aceitaveis = new LinkedHashSet<>(certos);
      aceitaveis.addAll(chaves(p.get("aceito")));

      ObjectNode item = perguntas.addObject();
      item.put("id", p.get("id").asText());
      item.put("grupo", grupo);
      item.put("pergunta", texto);
      item.set("esperado", p.get("esperado"));
      item.set("aceito", p.get("aceito"));

      ArrayNode entregues = item.putArray("entregues");
      List<String> ordemEntregue = new ArrayList<>();
      for (TrechoRecuperado t : busca.buscar(texto, hoje)) {
        String art = artigo(t.norma(), t.dispositivo());
        ordemEntregue.add(art);
        ObjectNode e = entregues.addObject();
        e.put("posicao", ordemEntregue.size());
        e.put("norma", t.norma());
        e.put("dispositivo", t.dispositivo());
        e.put("artigo", art);
        e.put("similaridade", Math.round(t.similaridade() * 1000) / 1000.0);
      }
      List<String> top5 = ordemEntregue.subList(0, Math.min(TOP_N, ordemEntregue.size()));
      if ("ausente".equals(grupo)) {
        ausentes++;
        if (!ordemEntregue.isEmpty()) {
          ausentesComTrecho++;
        }
        item.put("algumTrechoAcimaDoLimiar", !ordemEntregue.isEmpty());
      } else if (!certos.isEmpty()) {
        boolean k5e = top5.stream().anyMatch(certos::contains);
        boolean p1e = !ordemEntregue.isEmpty() && certos.contains(ordemEntregue.get(0));
        boolean k5a = top5.stream().anyMatch(aceitaveis::contains);
        boolean p1a = !ordemEntregue.isEmpty() && aceitaveis.contains(ordemEntregue.get(0));
        comEsperado++;
        k5Esperado += k5e ? 1 : 0;
        pos1Esperado += p1e ? 1 : 0;
        k5Aceitavel += k5a ? 1 : 0;
        pos1Aceitavel += p1a ? 1 : 0;
        item.put("entregueK5Esperado", k5e);
        item.put("entreguePos1Esperado", p1e);
        item.put("entregueK5Aceitavel", k5a);
        item.put("entreguePos1Aceitavel", p1a);
      }

      List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
          .query(DataSanitizer.mascarar(texto)).topK(20).similarityThreshold(0.0)
          .filterExpression(vigente).build());
      ArrayNode lista = item.putArray("top20");
      int primeiroEsperado = 0;
      int primeiroAceito = 0;
      int acimaDoLimiar = 0;
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
        if (primeiroEsperado == 0 && certos.contains(art)) {
          primeiroEsperado = posicao;
        }
        if (primeiroAceito == 0 && aceitaveis.contains(art)) {
          primeiroAceito = posicao;
        }
        ObjectNode e = lista.addObject();
        e.put("posicao", posicao);
        e.put("artigo", art);
        e.put("dispositivo", disp);
        e.put("similaridade", Math.round(score * 1000) / 1000.0);
      }
      item.put("posicaoEsperadoTop20", primeiroEsperado);
      item.put("posicaoEsperadoOuAceitoTop20", primeiroAceito);
      item.put("trechosAcimaDoLimiarNoTop20", acimaDoLimiar);

      resumo.put("perguntasComEsperado", comEsperado);
      resumo.put("recall5Esperado", k5Esperado);
      resumo.put("posicao1Esperado", pos1Esperado);
      resumo.put("recall5Aceitavel", k5Aceitavel);
      resumo.put("posicao1Aceitavel", pos1Aceitavel);
      resumo.put("ausentes", ausentes);
      resumo.put("ausentesComTrechoAcimaDoLimiar", ausentesComTrecho);
      Files.writeString(saida, json.writerWithDefaultPrettyPrinter().writeValueAsString(raiz));
      System.out.println("RECALL " + p.get("id").asText() + " ok");
    }
  }
}

package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Diagnóstico MANUAL das citações da rota de lei (#269). Não roda no {@code mvn test} (o nome não
 * casa com o padrão do surefire): precisa do Postgres com a base de lei e do Ollama locais, e é
 * chamado com {@code -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.saida=<arquivo.json>
 * [-Ddiagnostico.repeticoes=3]} no profile {@code dev}.
 *
 * <p>Para cada pergunta de {@code docs/avaliacao/treino/perguntas-lei-diagnostico.json} registra
 * (1) os trechos que a busca entrega ao modelo, com a posição, e (2) o que a {@link RotaLegislacao}
 * real responde, em N repetições: desfecho, citações e texto. Nada é simulado nem alterado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiagnosticoCitacoesLeiManual {

  @Autowired
  private BuscaLegislacaoService busca;

  @Autowired
  private RotaLegislacao rota;

  @Test
  void diagnostico() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode entrada = json.readTree(
        Files.readString(Path.of("docs/avaliacao/treino/perguntas-lei-diagnostico.json")));
    int repeticoes = Integer.getInteger("diagnostico.repeticoes", 3);
    Path saida = Path.of(System.getProperty("diagnostico.saida", "diagnostico-lei.json"));
    LocalDate hoje = LocalDate.now();

    // Aquece o modelo: a primeira chamada a frio passa do read-timeout e abriria o disjuntor.
    try {
      rota.responder("O que é o IBS?", new ContextoAssistente(UUID.randomUUID(), hoje));
    } catch (RuntimeException e) {
      System.out.println("aquecimento falhou: " + e.getClass().getSimpleName());
    }
    Thread.sleep(40_000);

    ArrayNode resultado = json.createArrayNode();
    String filtro = System.getProperty("diagnostico.ids", "");
    for (JsonNode p : entrada.get("perguntas")) {
      if (!filtro.isEmpty() && !java.util.List.of(filtro.split(",")).contains(p.get("id").asText())) {
        continue;
      }
      String pergunta = p.get("pergunta").asText();
      ObjectNode item = json.createObjectNode();
      item.put("id", p.get("id").asText());
      item.put("pergunta", pergunta);
      item.set("esperado", p.get("esperado"));
      item.set("aceito", p.get("aceito"));

      ArrayNode entregues = item.putArray("entregues");
      int posicao = 0;
      for (TrechoRecuperado t : busca.buscar(pergunta, hoje)) {
        ObjectNode e = entregues.addObject();
        e.put("posicao", ++posicao);
        e.put("norma", t.norma());
        e.put("dispositivo", t.dispositivo());
        e.put("similaridade", Math.round(t.similaridade() * 1000) / 1000.0);
      }

      ArrayNode rodadas = item.putArray("rodadas");
      for (int i = 0; i < repeticoes; i++) {
        ResultadoRota r = rota.responder(pergunta, new ContextoAssistente(UUID.randomUUID(), hoje));
        ObjectNode o = rodadas.addObject();
        o.put("desfecho", r.desfecho().name());
        o.put("texto", r.texto());
        ArrayNode citacoes = o.putArray("citacoes");
        for (CitacaoLegal c : r.citacoes()) {
          citacoes.add(c.norma() + " " + c.dispositivo());
        }
      }
      resultado.add(item);
      Files.writeString(saida, json.writerWithDefaultPrettyPrinter().writeValueAsString(resultado));
      System.out.println("DIAGNOSTICO " + p.get("id").asText() + " ok");
    }
  }
}

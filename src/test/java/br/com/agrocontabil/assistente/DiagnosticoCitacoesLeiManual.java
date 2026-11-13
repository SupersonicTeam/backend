package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Diagnóstico MANUAL das citações da rota de lei (#269, #436). Não roda no {@code mvn test} (o
 * nome não casa com o padrão do surefire): precisa do Postgres com a base de lei e do Ollama locais,
 * e é chamado com {@code -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.perguntas=<conjunto.json>
 * -Ddiagnostico.saida=<arquivo.json> [-Ddiagnostico.repeticoes=3] [-Ddiagnostico.ids=id,id]} no
 * profile {@code dev}. Os dois arquivos são obrigatórios, sem padrão ({@link ParametrosDiagnostico}).
 *
 * <p>Para cada pergunta do conjunto registra (1) os trechos que a busca entrega ao modelo, na ordem
 * e com a posição, e (2) o que a {@link RotaLegislacao} real responde, em N repetições: desfecho,
 * citações e texto. Nada é simulado nem alterado. A saída é a entrada do {@code busca_contagem.py}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiagnosticoCitacoesLeiManual {

  @Autowired
  private BuscaLegislacaoService busca;

  @Autowired
  private RotaLegislacao rota;

  /** Espião: chama o modelo de verdade e guarda o texto cru, antes de qualquer validador. */
  @MockitoSpyBean
  private ModeloLinguagem modelo;

  private final List<String> crus = new CopyOnWriteArrayList<>();

  /** Sem os arquivos, falha antes de o contexto subir e de ler qualquer arquivo (#436). */
  @BeforeAll
  static void exigirParametros() {
    ParametrosDiagnostico.exigir();
  }

  @Test
  void diagnostico() throws Exception {
    ObjectMapper json = new ObjectMapper();
    Path saida = ParametrosDiagnostico.saida();
    JsonNode entrada = json.readTree(Files.readString(ParametrosDiagnostico.perguntas()));
    int repeticoes = Integer.getInteger("diagnostico.repeticoes", 3);
    LocalDate hoje = LocalDate.now();
    Mockito.doAnswer(inv -> {
      Object r = inv.callRealMethod();
      if (r instanceof ChatResponse c && c.getResult() != null) {
        crus.add(c.getResult().getOutput().getText());
      }
      return r;
    }).when(modelo).chamar(Mockito.any(Prompt.class));

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
        crus.clear();
        long inicio = System.nanoTime();
        ResultadoRota r;
        ContextoAssistente contexto = new ContextoAssistente(UUID.randomUUID(), hoje);
        try {
          r = rota.responder(pergunta, contexto);
        } catch (RuntimeException e) {
          // Falha do modelo (timeout etc.): registra e segue, sem derrubar a rodada.
          ObjectNode erro = rodadas.addObject();
          erro.put("desfecho", "ERRO_MODELO");
          erro.put("texto", e.getClass().getSimpleName());
          erro.put("cru", "");
          erro.putArray("citacoes");
          Thread.sleep(40_000);
          continue;
        }
        ObjectNode o = rodadas.addObject();
        o.put("cru", crus.isEmpty() ? "" : crus.get(0));
        // Com o juiz da fidelidade (#269) ha uma segunda chamada: o veredito dele.
        ArrayNode todas = o.putArray("crus");
        crus.forEach(todas::add);
        o.put("ms", (System.nanoTime() - inicio) / 1_000_000);
        o.put("desfecho", r.desfecho().name());
        // Conferencia de fidelidade (#269): o desfecho dela, quando rodou.
        o.put("fidelidade", contexto.fidelidadeLei() == null ? null
            : contexto.fidelidadeLei().name());
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

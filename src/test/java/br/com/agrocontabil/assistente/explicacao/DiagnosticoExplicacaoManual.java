package br.com.agrocontabil.assistente.explicacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * Medição MANUAL da explicação depois da resposta de dados (#268, critérios da #415). Não roda no
 * {@code mvn test} (o nome não casa com o padrão do surefire): precisa do modelo real, Ollama ou o
 * provedor por API do {@code .env}, e é chamada no profile {@code dev} com
 * {@code -Dtest=DiagnosticoExplicacaoManual -Ddiagnostico.respostas=<conjunto.json>
 * -Ddiagnostico.saida=<arquivo.json> [-Ddiagnostico.repeticoes=3]}. Os dois arquivos são
 * obrigatórios, sem padrão. Passo a passo e formato em {@code docs/avaliacao/explicacao/README.md}.
 *
 * <p>Para cada resposta de dados do conjunto (o texto do template e as consultas que o geraram),
 * chama o {@link ExplicadorResposta} real N vezes e registra o desfecho, o texto cru do modelo
 * (antes de qualquer conferência), o texto aceito, o tempo da chamada e os tokens. O resumo traz a
 * taxa de descarte por motivo e o p50/p95 do tempo: a base para o Murillo decidir se liga a flag
 * ({@code app.assistente.explicacao.enabled}). Nada é simulado nem alterado; nada vai ao banco.
 *
 * <p>Com o provedor por API, o texto das respostas do conjunto vai ao provedor: use dados
 * fictícios (o exemplo versionado é fictício).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiagnosticoExplicacaoManual {

  private static final String RESPOSTAS = "diagnostico.respostas";
  private static final String SAIDA = "diagnostico.saida";

  @Autowired
  private ExplicadorResposta explicador;

  /** Espião: chama o modelo de verdade e guarda o texto cru, antes do verificador. */
  @MockitoSpyBean
  private ModeloLinguagem modelo;

  private final List<String> crus = new CopyOnWriteArrayList<>();

  /** Sem os arquivos, falha antes de o contexto subir e de ler qualquer arquivo. */
  @BeforeAll
  static void exigirParametros() {
    obrigatorio(RESPOSTAS);
    obrigatorio(SAIDA);
  }

  @Test
  void medir() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode entrada = json.readTree(Files.readString(obrigatorio(RESPOSTAS)));
    Path saida = obrigatorio(SAIDA);
    int repeticoes = Integer.getInteger("diagnostico.repeticoes", 3);
    LocalDate hoje = LocalDate.now();
    Mockito.doAnswer(inv -> {
      Object r = inv.callRealMethod();
      if (r instanceof ChatResponse c && c.getResult() != null) {
        crus.add(c.getResult().getOutput().getText());
      }
      return r;
    }).when(modelo).chamar(Mockito.any(Prompt.class));

    ArrayNode itens = json.createArrayNode();
    Map<String, Integer> porDesfecho = new TreeMap<>();
    List<Long> tempos = new ArrayList<>();
    for (JsonNode r : entrada.get("respostas")) {
      ObjectNode item = itens.addObject();
      item.put("id", r.get("id").asText());
      item.put("resposta", r.get("resposta").asText());
      ArrayNode rodadas = item.putArray("rodadas");
      for (int i = 0; i < repeticoes; i++) {
        crus.clear();
        ContextoAssistente contexto = contexto(r, hoje);
        ObjectNode rodada = rodadas.addObject();
        long inicio = System.nanoTime();
        String desfecho;
        try {
          ExplicadorResposta.Explicacao e = explicador.explicar(r.get("resposta").asText(),
              contexto);
          desfecho = e.desfecho().name();
          rodada.put("texto", e.texto());
        } catch (RuntimeException e) {
          // Falha do modelo (timeout etc.): registra e segue, sem derrubar a medicao.
          desfecho = "ERRO_MODELO:" + e.getClass().getSimpleName();
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        tempos.add(ms);
        porDesfecho.merge(desfecho, 1, Integer::sum);
        rodada.put("desfecho", desfecho);
        rodada.put("ms", ms);
        rodada.put("cru", crus.isEmpty() ? "" : crus.get(crus.size() - 1));
        rodada.put("tokensEntrada", contexto.tokensEntrada());
        rodada.put("tokensSaida", contexto.tokensSaida());
      }
      Files.writeString(saida, json.writerWithDefaultPrettyPrinter().writeValueAsString(
          relatorio(json, itens, porDesfecho, tempos)));
      System.out.println("EXPLICACAO " + r.get("id").asText() + " ok");
    }
  }

  /** Contexto da medição: as consultas do conjunto como fontes, sem banco nem produtor real. */
  private static ContextoAssistente contexto(JsonNode resposta, LocalDate hoje) {
    ContextoAssistente contexto = new ContextoAssistente(UUID.randomUUID(), hoje);
    JsonNode consultas = resposta.get("consultas");
    if (consultas != null) {
      for (JsonNode c : consultas) {
        Map<String, String> parametros = new LinkedHashMap<>();
        if (c.get("parametros") != null) {
          c.get("parametros").fields()
              .forEachRemaining(p -> parametros.put(p.getKey(), p.getValue().asText()));
        }
        contexto.registrarFonte(c.get("funcao").asText(), parametros,
            c.path("vazia").asBoolean(false));
      }
    }
    return contexto;
  }

  private static ObjectNode relatorio(ObjectMapper json, ArrayNode itens,
      Map<String, Integer> porDesfecho, List<Long> tempos) {
    ObjectNode raiz = json.createObjectNode();
    ObjectNode resumo = raiz.putObject("resumo");
    int total = porDesfecho.values().stream().mapToInt(Integer::intValue).sum();
    int aceitas = porDesfecho.getOrDefault("EXPLICACAO_ACEITA", 0);
    resumo.put("rodadas", total);
    resumo.put("aceitas", aceitas);
    resumo.put("taxaDescarte", total == 0 ? 0 : Math.round(1000.0 * (total - aceitas) / total)
        / 1000.0);
    resumo.set("porDesfecho", json.valueToTree(porDesfecho));
    List<Long> ordenados = new ArrayList<>(tempos);
    Collections.sort(ordenados);
    resumo.put("p50Ms", percentil(ordenados, 50));
    resumo.put("p95Ms", percentil(ordenados, 95));
    raiz.set("respostas", itens);
    return raiz;
  }

  private static long percentil(List<Long> ordenados, int p) {
    if (ordenados.isEmpty()) {
      return 0;
    }
    int indice = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
    return ordenados.get(Math.max(0, Math.min(indice, ordenados.size() - 1)));
  }

  private static Path obrigatorio(String propriedade) {
    String valor = System.getProperty(propriedade);
    if (valor == null || valor.isBlank()) {
      throw new IllegalStateException("Informe -D" + propriedade + "=<arquivo JSON>: a medicao"
          + " nao tem valor padrao para ele.");
    }
    return Path.of(valor.strip());
  }
}

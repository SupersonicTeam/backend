package br.com.agrocontabil.assistente.orquestracao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.SinalRecusaFirme;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Diagnóstico MANUAL do classificador de rota no conjunto de desenvolvimento (#497). Não roda no
 * {@code mvn test} (o nome não termina em {@code Test}); chamado com
 * {@code -Dtest=DiagnosticoRotaDevManual -Ddiagnostico.saida=<arquivo.md>}, com o Ollama local e o
 * {@code bge-m3} ({@code -Ddiagnostico.ollama=} troca o endereço).
 *
 * <p>Roda o caminho de produção até o modelo de linguagem: as checagens do orquestrador antes do
 * classificador (atalhos, conversa leve, definição, filtro de injeção, sinal de recusa firme) e o
 * {@link ClassificadorEmCamadas} com o treino real e a configuração padrão, com o modelo simulado
 * só para marcar o desempate (sem a chave da API, a rota dele não é conhecida). Para toda pergunta
 * grava também o voto das embeddings (vizinhos, peso por rota, confiança, similaridade do mais
 * próximo), mesmo quando outra camada decide, e o mesmo voto em deixar-um-de-fora sobre o próprio
 * treino. As tabelas de varredura mostram, por limiar, quantas perguntas as embeddings decidem e
 * quantas erram.
 *
 * <p>O conjunto é o {@code docs/avaliacao/rota-dev/perguntas-rota-dev.json} ou outro da mesma
 * pasta, por {@code -Ddiagnostico.conjunto=} (#624: {@code perguntas-rota-dev-2.json}); nunca o
 * gabarito.
 */
class DiagnosticoRotaDevManual {

  private static final Path CONJUNTO = Path.of(System.getProperty("diagnostico.conjunto",
      "docs/avaliacao/rota-dev/perguntas-rota-dev.json"));
  private static final double[] LIMIARES = {0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 0.99};
  private static final List<Long> LATENCIAS_MS = new ArrayList<>();
  private static final double[] SIMILARIDADES = {0.5, 0.6, 0.65, 0.7, 0.75, 0.8};

  @Test
  void diagnosticar() throws Exception {
    String saida = System.getProperty("diagnostico.saida");
    if (saida == null || saida.isBlank()) {
      throw new IllegalStateException("Informe -Ddiagnostico.saida=<arquivo.md>");
    }
    EmbeddingModel modelo = OllamaEmbeddingModel.builder()
        .ollamaApi(OllamaApi.builder()
            .baseUrl(System.getProperty("diagnostico.ollama", "http://localhost:11434")).build())
        .defaultOptions(OllamaEmbeddingOptions.builder().model("bge-m3").build())
        .build();
    AssistenteProperties propriedades = new AssistenteProperties();
    AssistenteProperties.Classificacao config = propriedades.getClassificacao();
    config.setTimeoutEmbedding(java.time.Duration.ofSeconds(30));
    EmbeddingClassificadorIntencao embeddings = new EmbeddingClassificadorIntencao(
        provedor(modelo), config, EmbeddingClassificadorIntencao.carregar(new ObjectMapper()));
    if (!embeddings.aquecer()) {
      throw new IllegalStateException("Sem os vetores do treino: o Ollama esta no ar?");
    }
    // O modelo do caminho de producao responde a primeira repeticao ja feita para a pergunta;
    // sem a chave, uma resposta ilegivel (o desempate fica so marcado).
    Intencao[] respostaDoModelo = {null};
    int[] chamadas = {0};
    LlmClassificadorIntencao llm = mock(LlmClassificadorIntencao.class);
    when(llm.classificar(anyString(), any())).thenAnswer(invocacao -> {
      chamadas[0]++;
      return respostaDoModelo[0] == null ? ClassificacaoIntencao.indeterminada()
          : new ClassificacaoIntencao(respostaDoModelo[0], 0.9, null, CamadaClassificacao.LLM);
    });
    DisponibilidadeModelo disponibilidade = mock(DisponibilidadeModelo.class);
    when(disponibilidade.disponivel()).thenReturn(true);
    ContextoAssistente contexto = mock(ContextoAssistente.class);
    ClassificadorEmCamadas classificador =
        new ClassificadorEmCamadas(llm, embeddings, disponibilidade, propriedades);

    ChatModel chat = modeloDeLinguagem();
    int repeticoes = Integer.getInteger("diagnostico.repeticoes", 2);
    LlmClassificadorIntencao leitor = new LlmClassificadorIntencao(
        mock(ModeloLinguagem.class), new ObjectMapper(), propriedades);
    float[][] treino = embeddings.vetoresDoTreino();
    List<EmbeddingClassificadorIntencao.Exemplo> exemplos = embeddings.exemplos();
    JsonNode conjunto = new ObjectMapper().readTree(CONJUNTO.toFile());

    List<Linha> linhas = new ArrayList<>();
    for (JsonNode p : conjunto.path("perguntas")) {
      String pergunta = p.path("pergunta").asText();
      Intencao esperada = Intencao.valueOf(p.path("rota").asText());
      String antes = antesDoClassificador(pergunta);
      Optional<EmbeddingClassificadorIntencao.Voto> voto =
          embeddings.voto(embeddings.vetorDe(pergunta), treino);
      boolean chega = antes == null && RegrasClassificacao.decidir(pergunta).isEmpty()
          && !SinalRecusaFirme.pedeDesempate(pergunta);
      List<Intencao> doModelo = new ArrayList<>();
      if (chat != null && antes == null
          && (chega || SinalRecusaFirme.pedeDesempate(pergunta))) {
        for (int r = 0; r < repeticoes; r++) {
          doModelo.add(classificarPeloModelo(chat, leitor, pergunta,
              propriedades.getOrquestracao().getConfiancaMinima()));
        }
      }
      respostaDoModelo[0] = doModelo.isEmpty() ? null : doModelo.get(0);
      String camada;
      String rota;
      if (antes != null) {
        camada = antes;
        rota = antes.equals("ATALHO_DEFINICAO") ? "LEGISLACAO"
            : antes.equals("ATALHO_FIXO") ? "?" : "FORA_DE_ESCOPO";
      } else {
        ClassificacaoIntencao c = classificador.classificar(pergunta, contexto);
        camada = c.camada().name();
        rota = c.camada() == CamadaClassificacao.LLM && respostaDoModelo[0] == null
            ? "MODELO" : c.intencao().name();
        if (c.camada() == CamadaClassificacao.LLM && respostaDoModelo[0] != null) {
          camada = "LLM";
        }
      }
      linhas.add(new Linha(p.path("id").asText(), p.path("tipo").asText(), pergunta, esperada,
          camada, rota, voto.orElse(null), chega, doModelo));
    }

    List<Linha> loo = new ArrayList<>();
    for (int i = 0; i < treino.length; i++) {
      float[][] semEle = treino.clone();
      semEle[i] = new float[treino[i].length];
      EmbeddingClassificadorIntencao.Exemplo e = exemplos.get(i);
      String antes = antesDoClassificador(e.pergunta());
      boolean chega = antes == null && RegrasClassificacao.decidir(e.pergunta()).isEmpty()
          && !SinalRecusaFirme.pedeDesempate(e.pergunta());
      loo.add(new Linha("T" + i, "treino", e.pergunta(), e.rota(), "", "",
          embeddings.voto(treino[i], semEle).orElse(null), chega, List.of()));
    }

    StringBuilder md = new StringBuilder();
    md.append("# Diagnóstico de rota no conjunto de desenvolvimento\n\nConjunto: `")
        .append(CONJUNTO.getFileName()).append("`, versão ")
        .append(conjunto.path("versao").asText("?")).append(".\n\n");
    md.append("Configuração: limiar ").append(config.getLimiarConfianca()).append(", vizinhos ")
        .append(config.getVizinhos()).append(", similaridade mínima ")
        .append(config.getSimilaridadeMinima()).append(", treino ").append(exemplos.size())
        .append(" exemplos.\n\n");
    resumo(md, linhas);
    md.append("\nChamadas ao classificador por modelo no caminho de produção (desempate e"
        + " pergunta de lei do MISTO): ").append(chamadas[0]).append(".\n");
    if (!LATENCIAS_MS.isEmpty()) {
      List<Long> ordenadas = LATENCIAS_MS.stream().sorted().toList();
      md.append("\nLatência da chamada de classificação ao modelo (").append(ordenadas.size())
          .append(" chamadas, desta máquina até a API): p50 ")
          .append(ordenadas.get(ordenadas.size() / 2)).append(" ms, p95 ")
          .append(ordenadas.get((int) Math.ceil(ordenadas.size() * 0.95) - 1)).append(" ms.\n");
    }
    md.append("\n## Por pergunta\n\n| id | tipo | esperada | camada | rota | ok | modelo | voto |"
        + " conf | sim1 | vizinhos |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Linha l : linhas) {
      md.append("| ").append(l.id).append(" | ").append(l.tipo).append(" | ")
          .append(curta(l.esperada.name())).append(" | ").append(l.camada).append(" | ")
          .append(curta(l.rota)).append(" | ").append(marca(l)).append(" | ")
          .append(String.join(",", l.doModelo.stream().map(i -> curta(i.name())).toList()))
          .append(" | ");
      if (l.voto == null) {
        md.append("- | - | - | - |\n");
      } else {
        md.append(curta(l.voto.vencedora().name())).append(" | ")
            .append(fmt(l.voto.confianca())).append(" | ")
            .append(fmt(l.voto.vizinhos().get(0).similaridade())).append(" | ")
            .append(vizinhos(l.voto, exemplos)).append(" |\n");
      }
    }
    md.append("\n## Varredura no conjunto de desenvolvimento (perguntas que chegam às"
        + " embeddings)\n\n");
    varredura(md, linhas);
    if (chat != null) {
      md.append("\n## Pipeline inteiro simulado (com o modelo nas que não decidem)\n\n");
      simulacao(md, linhas);
    }
    md.append("\n## Varredura em deixar-um-de-fora no treino (exemplos que chegam às"
        + " embeddings)\n\n");
    varredura(md, loo);
    md.append("\n## Erros do deixar-um-de-fora com confiança >= 0,6\n\n");
    for (Linha l : loo) {
      if (l.chegaAsEmbeddings && l.voto != null && l.voto.confianca() >= 0.6
          && l.voto.vencedora() != l.esperada) {
        md.append("- ").append(curta(l.esperada.name())).append(" -> ")
            .append(curta(l.voto.vencedora().name())).append(" (")
            .append(fmt(l.voto.confianca())).append("): ").append(l.pergunta).append('\n');
      }
    }
    Path destino = Path.of(saida);
    if (destino.getParent() != null) {
      Files.createDirectories(destino.getParent());
    }
    Files.writeString(destino, md.toString());
    embeddings.destroy();
  }

  /** As checagens do orquestrador antes do classificador, na mesma ordem. */
  private static String antesDoClassificador(String pergunta) {
    if (AtalhosFixos.ehAtalho(pergunta)) {
      return "ATALHO_FIXO";
    }
    if (ConversaLeve.ehConversaLeve(pergunta)) {
      return "CONVERSA_LEVE";
    }
    if (AtalhoDefinicao.ehDefinicao(pergunta)) {
      return "ATALHO_DEFINICAO";
    }
    if (FiltroInjecao.pareceInjecao(pergunta)) {
      return "FILTRO_INJECAO";
    }
    if (SinalRecusaFirme.inequivoco(pergunta)) {
      return "SINAL_RECUSA_FIRME";
    }
    return null;
  }

  private static void resumo(StringBuilder md, List<Linha> linhas) {
    Map<String, int[]> porCamada = new java.util.TreeMap<>();
    Map<Intencao, int[]> porRota = new EnumMap<>(Intencao.class);
    int certas = 0;
    int modelo = 0;
    for (Linha l : linhas) {
      boolean certa = l.rota.equals(l.esperada.name());
      boolean foiAoModelo = l.rota.equals("MODELO");
      int[] c = porCamada.computeIfAbsent(l.camada, k -> new int[3]);
      int[] r = porRota.computeIfAbsent(l.esperada, k -> new int[3]);
      c[0]++;
      r[0]++;
      if (foiAoModelo) {
        c[2]++;
        r[2]++;
        modelo++;
      } else if (certa) {
        c[1]++;
        r[1]++;
        certas++;
      }
    }
    md.append("**Decididas antes do modelo:** ").append(linhas.size() - modelo).append(" de ")
        .append(linhas.size()).append(", com ").append(certas).append(" certas; ")
        .append(modelo).append(" vão ao modelo.\n\n");
    md.append("| camada | n | certas | erradas | ao modelo |\n|---|---|---|---|---|\n");
    porCamada.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[0])
        .append(" | ").append(v[1]).append(" | ").append(v[0] - v[1] - v[2]).append(" | ")
        .append(v[2]).append(" |\n"));
    md.append("\n| rota esperada | n | certas antes do modelo | erradas | ao modelo |\n"
        + "|---|---|---|---|---|\n");
    porRota.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[0])
        .append(" | ").append(v[1]).append(" | ").append(v[0] - v[1] - v[2]).append(" | ")
        .append(v[2]).append(" |\n"));
  }

  /** Por limiar de confiança e similaridade mínima: decididas e erradas pelas embeddings. */
  private static void varredura(StringBuilder md, List<Linha> linhas) {
    md.append("Célula = decididas / erradas (as demais vão ao modelo).\n\n| sim1 \\ limiar |");
    for (double limiar : LIMIARES) {
      md.append(' ').append(fmt(limiar)).append(" |");
    }
    md.append("\n|---|");
    for (int i = 0; i < LIMIARES.length; i++) {
      md.append("---|");
    }
    md.append('\n');
    long chegam = linhas.stream().filter(l -> l.chegaAsEmbeddings).count();
    for (double sim : SIMILARIDADES) {
      md.append("| ").append(fmt(sim)).append(" |");
      for (double limiar : LIMIARES) {
        int decididas = 0;
        int erradas = 0;
        for (Linha l : linhas) {
          if (!l.chegaAsEmbeddings || l.voto == null) {
            continue;
          }
          if (l.voto.confianca() >= limiar
              && l.voto.vizinhos().get(0).similaridade() >= sim) {
            decididas++;
            if (l.voto.vencedora() != l.esperada) {
              erradas++;
            }
          }
        }
        md.append(' ').append(decididas).append(" / ").append(erradas).append(" |");
      }
      md.append('\n');
    }
    md.append("\n(").append(chegam).append(" chegam às embeddings.)\n");
  }

  /**
   * Acerto do pipeline inteiro por configuração: as decididas antes das embeddings ficam como
   * estão; as que chegam às embeddings são decididas por elas se passarem do limiar e da
   * similaridade, senão pela média das repetições do modelo. Como no {@link
   * ClassificadorEmCamadas}, o voto MISTO e o voto LEGISLACAO com dado do produtor ficam com o
   * modelo (o MISTO já o chamava, para a pergunta de lei).
   */
  private static void simulacao(StringBuilder md, List<Linha> linhas) {
    md.append("Célula = acerto % / perguntas ao modelo.\n\n| sim1 \\ limiar |");
    for (double limiar : LIMIARES) {
      md.append(' ').append(fmt(limiar)).append(" |");
    }
    md.append(" só modelo |\n|---|");
    for (int i = 0; i <= LIMIARES.length; i++) {
      md.append("---|");
    }
    md.append('\n');
    for (double sim : SIMILARIDADES) {
      md.append("| ").append(fmt(sim)).append(" |");
      for (double limiar : LIMIARES) {
        md.append(' ').append(celula(linhas, limiar, sim)).append(" |");
      }
      md.append(' ').append(celula(linhas, 2, 2)).append(" |\n");
    }
  }

  private static String celula(List<Linha> linhas, double limiar, double sim) {
    double certas = 0;
    int aoModelo = 0;
    for (Linha l : linhas) {
      if (!l.chegaAsEmbeddings) {
        // Fora das embeddings, o modelo so decide pela marca de outra pessoa (pedeDesempate).
        if (l.camada.equals("LLM")) {
          aoModelo++;
        }
        if (l.rota.equals("MODELO")) {
          certas += taxa(l);
        } else if (l.rota.equals(l.esperada.name())) {
          certas++;
        }
      } else if (l.voto != null && l.voto.confianca() >= limiar
          && l.voto.vizinhos().get(0).similaridade() >= sim
          && l.voto.vencedora() != Intencao.MISTO
          && !(l.voto.vencedora() == Intencao.LEGISLACAO
              && RegrasClassificacao.falaDosDadosDoProdutor(l.pergunta))) {
        certas += l.voto.vencedora() == l.esperada ? 1 : 0;
      } else {
        certas += taxa(l);
        aoModelo++;
      }
    }
    return String.format(Locale.ROOT, "%.1f / %d", 100 * certas / linhas.size(), aoModelo);
  }

  private static double taxa(Linha l) {
    return l.doModelo.isEmpty() ? 0
        : l.doModelo.stream().filter(i -> i == l.esperada).count() / (double) l.doModelo.size();
  }

  private static String vizinhos(EmbeddingClassificadorIntencao.Voto voto,
      List<EmbeddingClassificadorIntencao.Exemplo> exemplos) {
    StringBuilder s = new StringBuilder();
    for (EmbeddingClassificadorIntencao.Vizinho v : voto.vizinhos().subList(0,
        Math.min(3, voto.vizinhos().size()))) {
      s.append(curta(v.rota().name())).append(' ').append(fmt(v.similaridade())).append(" «")
          .append(exemplos.get(v.indice()).pergunta().replace("|", "/")).append("» ");
    }
    return s.toString().strip();
  }

  private static String marca(Linha l) {
    if (l.rota.equals("MODELO")) {
      return "→";
    }
    return l.rota.equals(l.esperada.name()) ? "ok" : "ERRO";
  }

  private static String curta(String rota) {
    return switch (rota) {
      case "LEGISLACAO" -> "LEI";
      case "FORA_DE_ESCOPO" -> "FORA";
      default -> rota;
    };
  }

  private static String fmt(double v) {
    return String.format(Locale.ROOT, "%.2f", v);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<EmbeddingModel> provedor(EmbeddingModel modelo) {
    ObjectProvider<EmbeddingModel> provedor = mock(ObjectProvider.class);
    when(provedor.getIfAvailable()).thenReturn(modelo);
    when(provedor.getObject()).thenReturn(modelo);
    return provedor;
  }

  private record Linha(String id, String tipo, String pergunta, Intencao esperada,
      String camada, String rota, EmbeddingClassificadorIntencao.Voto voto,
      boolean chegaAsEmbeddings, List<Intencao> doModelo) {}

  /** O Haiku pela API, com a chave só do ambiente; sem ela, o desempate fica sem rota. */
  private static ChatModel modeloDeLinguagem() {
    String chave = System.getenv("ANTHROPIC_API_KEY");
    if (chave == null || chave.isBlank() || !Boolean.getBoolean("diagnostico.modelo")) {
      return null;
    }
    return AnthropicChatModel.builder()
        .anthropicApi(AnthropicApi.builder().apiKey(chave).build())
        .defaultOptions(AnthropicChatOptions.builder()
            .model(System.getProperty("diagnostico.modeloNome", "claude-haiku-5-5"))
            .temperature(1.0).maxTokens(1024)
            .thinking(AnthropicApi.ThinkingType.DISABLED, null).build())
        .build();
  }

  /** A chamada do {@link LlmClassificadorIntencao}, com o corte de confiança do orquestrador. */
  private static Intencao classificarPeloModelo(ChatModel chat, LlmClassificadorIntencao leitor,
      String pergunta, double confiancaMinima) {
    int teto = new AssistenteProperties().getTetos().getClassificacao();
    Prompt prompt = new Prompt(List.<Message>of(new SystemMessage(PromptsCeres.CLASSIFICACAO),
        new UserMessage(DataSanitizer.mascarar(pergunta))), ModeloLinguagem.comTeto(teto));
    long inicio = System.nanoTime();
    String texto = chat.call(prompt).getResult().getOutput().getText();
    LATENCIAS_MS.add((System.nanoTime() - inicio) / 1_000_000);
    ClassificacaoIntencao c = leitor.interpretar(texto);
    return c.confianca() < confiancaMinima ? Intencao.FORA_DE_ESCOPO : c.intencao();
  }
}

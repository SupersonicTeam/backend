package br.com.agrocontabil.assistente.fidelidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.AssistenteProperties.FidelidadeLei.Juiz.Configuracao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bancada MANUAL do juiz da fidelidade da lei (#269). Não roda no {@code ./mvnw test} (o nome não
 * casa com o padrão do surefire). Profile {@code dev}, com o banco da base de lei local e o provedor
 * do chat configurado pelo ambiente (para o Haiku: {@code AI_CHAT_PROVIDER=anthropic},
 * {@code AI_ANTHROPIC_MODEL=claude-haiku-5-5}, {@code AI_ANTHROPIC_THINKING=disabled} e a
 * {@code ANTHROPIC_API_KEY} só no ambiente do processo, nunca em arquivo):
 *
 * <pre>
 * ./mvnw test -Dtest=BancadaJuizFidelidadeManual -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dbancada.entrada=respostas.jsonl -Dbancada.saida=vereditos.jsonl \
 *   [-Dbancada.configuracoes=atual,justificativa] [-Dbancada.repeticoes=1] \
 *   [-Dbancada.data=2026-11-13]
 * </pre>
 *
 * <p><strong>Entrada</strong> ({@code bancada.entrada}, JSONL): uma resposta por linha,
 * {@code {"id", "pergunta", "resposta", "citacoes": [...]}}, com {@code resposta} e
 * {@code citacoes} exatamente como o {@code POST /api/assistente/chat} devolve
 * ({@code {norma, dispositivo, url, trecho}}). A {@code pergunta} não é usada: o juiz não a vê, como
 * na produção (a saída liga a resposta pelo {@code id}).
 *
 * <p><strong>Configurações</strong> ({@code bancada.configuracoes}, separadas por vírgula; padrão
 * {@code atual}): os nomes de {@code AssistenteProperties.FidelidadeLei.Juiz.Configuracao}, os
 * mesmos de {@code AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO}: {@code atual} = o juiz de hoje,
 * configuração (a); {@code justificativa} = configuração (d); {@code adaptativo-baixo},
 * {@code adaptativo-medio} e {@code adaptativo-alto} = raciocínio do provedor com esforço
 * {@code low}/{@code medium}/{@code high}, só com {@code AI_CHAT_PROVIDER=anthropic}.
 *
 * <p><strong>Saída</strong> ({@code bancada.saida}, JSONL): uma linha por resposta, configuração e
 * repetição, com {@code id}, {@code configuracao}, {@code repeticao}, {@code veredito}
 * ({@code APROVA}/{@code REPROVA}/{@code FALHA}), {@code motivo} ({@code SUSTENTA},
 * {@code REPROVADA_JUIZ_NAO_SUSTENTA}, {@code REPROVADA_JUIZ_CONTRADIZ}, a falha do juiz —
 * {@code CORTADA_PELO_TETO}, {@code FORMATO}, {@code CONTAGEM_DIFERENTE}, {@code ERRO_MODELO} — ou
 * {@code SEM_FRASE}, sem chamada), {@code latencia_ms}, {@code tokens_entrada},
 * {@code tokens_saida} (raciocínio incluído), {@code tokens_raciocinio} (a parte de raciocínio, que a
 * API informa em {@code usage.output_tokens_details.thinking_tokens}; nulo sem raciocínio ou sem a
 * informação), {@code cortada_pelo_teto}, {@code frases},
 * {@code frases_sem_citacao}, {@code dispositivos_ausentes}, {@code vereditos} (um por frase) e
 * {@code cru} (o texto do juiz, só lei e vereditos).
 *
 * <p><strong>Diferenças para a produção</strong> (o número da bancada não é o da rota):
 * <ul>
 *   <li>na produção o juiz recebe as frases que a camada 1 aprovou, como o modelo as escreveu no
 *       JSON, cada uma com as passagens em que a cópia literal dela foi achada (o dispositivo citado
 *       do caput até o fim do nível da cópia). Aqui as frases são reconstruídas da resposta final
 *       ({@link FrasesDaResposta}): divididas pela pontuação, com a referência entre parênteses
 *       trocada pelas citações e a passagem = o texto do dispositivo citado na base (o trecho
 *       gravado pelo fatiador, que traz os níveis de cima), sem a evidência da camada 1;</li>
 *   <li>frase sem referência própria usa a citação da próxima (ou da anterior); na produção toda
 *       frase tem a sua. Nas respostas do braço desligado (escritas sem evidência por frase) a
 *       divisão é uma aproximação;</li>
 *   <li>a resposta passa pelo juiz qualquer que seja o desfecho da camada 1; na produção o juiz só
 *       vê o que a camada 1 aprovou ou mandou resgatar, e só se as conferências em código depois
 *       dela não descartaram antes;</li>
 *   <li>a chamada e a sanitização são as do juiz real ({@link JuizFidelidadeLei#avaliar}), mas fora
 *       de uma pergunta: sem cota, bulkhead nem tempo da pergunta.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class BancadaJuizFidelidadeManual {

  @Autowired
  private JuizFidelidadeLei juiz;

  @Autowired
  private BuscaTextualLegislacaoService busca;

  /** Sem os arquivos ou com configuração desconhecida, falha antes de o contexto subir. */
  @BeforeAll
  static void exigirParametros() {
    for (String nome : List.of("bancada.entrada", "bancada.saida")) {
      String valor = System.getProperty(nome);
      if (valor == null || valor.isBlank()) {
        throw new IllegalStateException("-D" + nome + " é obrigatório (veja o Javadoc)");
      }
    }
    if (!Files.isRegularFile(Path.of(System.getProperty("bancada.entrada")))) {
      throw new IllegalStateException("bancada.entrada não é um arquivo");
    }
    configuracoes();
  }

  /** As configurações pedidas, pelo nome ({@code Configuracao.nome()}); desconhecida falha. */
  private static List<Configuracao> configuracoes() {
    List<Configuracao> escolhidas = new ArrayList<>();
    for (String nome : System.getProperty("bancada.configuracoes", "atual").split(",")) {
      try {
        escolhidas.add(Configuracao.deNome(nome));
      } catch (IllegalArgumentException e) {
        List<String> existentes = new ArrayList<>();
        for (Configuracao c : Configuracao.values()) {
          existentes.add(c.nome());
        }
        throw new IllegalStateException(e.getMessage() + "; existem " + existentes, e);
      }
    }
    return escolhidas;
  }

  @Test
  void bancada() throws Exception {
    ObjectMapper json = new ObjectMapper();
    List<Configuracao> configuracoes = configuracoes();
    int repeticoes = Integer.getInteger("bancada.repeticoes", 1);
    LocalDate data = LocalDate.parse(System.getProperty("bancada.data",
        LocalDate.now().toString()));
    List<String> linhas = Files.readAllLines(Path.of(System.getProperty("bancada.entrada")),
        StandardCharsets.UTF_8);

    try (BufferedWriter saida = Files.newBufferedWriter(Path.of(System.getProperty("bancada.saida")),
        StandardCharsets.UTF_8)) {
      for (String linha : linhas) {
        if (linha.isBlank()) {
          continue;
        }
        JsonNode entrada = json.readTree(linha);
        List<FrasesDaResposta.Citacao> citacoes = new ArrayList<>();
        for (JsonNode c : entrada.path("citacoes")) {
          citacoes.add(new FrasesDaResposta.Citacao(c.path("norma").asText(),
              c.path("dispositivo").asText(), c.path("trecho").asText(null)));
        }
        Map<String, String> porChave = new HashMap<>();
        for (TrechoRecuperado t : busca.buscarDispositivos(
            citacoes.stream().map(FrasesDaResposta.Citacao::chave).distinct().toList(), data)) {
          porChave.put(t.norma() + "|" + t.dispositivo(), t.texto());
        }
        FrasesDaResposta.Montagem montagem =
            FrasesDaResposta.montar(entrada.path("resposta").asText(""), citacoes, porChave);

        for (Configuracao configuracao : configuracoes) {
          for (int r = 1; r <= repeticoes; r++) {
            ObjectNode o = json.createObjectNode();
            o.put("id", entrada.path("id").asText());
            o.put("configuracao", configuracao.nome());
            o.put("repeticao", r);
            o.put("frases", montagem.frases().size());
            o.put("frases_sem_citacao", montagem.semCitacao());
            ArrayNode ausentes = o.putArray("dispositivos_ausentes");
            montagem.dispositivosAusentes().forEach(ausentes::add);
            if (montagem.frases().isEmpty()) {
              o.put("veredito", "FALHA");
              o.put("motivo", "SEM_FRASE");
              o.putNull("latencia_ms");
              o.putNull("tokens_entrada");
              o.putNull("tokens_saida");
              o.putNull("tokens_raciocinio");
              o.put("cortada_pelo_teto", false);
              o.putArray("vereditos");
              o.putNull("cru");
            } else {
              julgar(o, montagem, configuracao, data);
            }
            saida.write(json.writeValueAsString(o));
            saida.newLine();
            saida.flush();
          }
        }
      }
    }
  }

  private void julgar(ObjectNode o, FrasesDaResposta.Montagem montagem,
      Configuracao configuracao, LocalDate data) {
    ContextoAssistente contexto = new ContextoAssistente(UUID.randomUUID(), data);
    JuizFidelidadeLei.Julgamento j = juiz.avaliar(montagem.frases(), contexto, configuracao);
    String veredito = j.desfecho() == null ? "APROVA"
        : j.falha() != null ? "FALHA" : "REPROVA";
    o.put("veredito", veredito);
    o.put("motivo", j.desfecho() == null ? "SUSTENTA"
        : j.falha() != null ? j.falha().name() : j.desfecho().name());
    ContextoAssistente.JuizLei metricas = contexto.juizLei();
    o.put("latencia_ms", metricas == null ? null : metricas.latenciaMs());
    o.put("tokens_entrada", metricas == null ? null : metricas.tokensEntrada());
    o.put("tokens_saida", metricas == null ? null : metricas.tokensSaida());
    Integer raciocinio = metricas == null ? null : metricas.tokensRaciocinio();
    o.put("tokens_raciocinio", raciocinio);
    o.put("cortada_pelo_teto", j.cortadaPeloTeto());
    ArrayNode vereditos = o.putArray("vereditos");
    j.vereditos().forEach(v -> vereditos.add(v.name()));
    o.put("cru", j.cru());
  }
}

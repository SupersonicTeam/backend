package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.OrquestradorService;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.avaliacao.SeedAvaliacao.ProdutoresAvaliacao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Comando de avaliação da Ceres com gabarito (issue #158). Só existe no profile
 * {@code avaliacao}, e só roda junto com {@code dev}: grava um seed fictício no banco.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,avaliacao \
 *   -Dspring-boot.run.arguments="--avaliacao.hardware='i5-12450H, 16 GB, RTX 3050 4 GB'"
 * </pre>
 *
 * <p>Usa o modelo real (o configurado em {@code spring.ai.*}) e a legislação já ingerida. Cada
 * pergunta passa pelo mesmo {@link OrquestradorService} da API, com o mesmo timeout, mas sem a
 * fila do chat: as perguntas rodam uma por vez, e a latência medida é a da Ceres, não a de espera.
 * O "hoje" da Ceres é fixo ({@code avaliacao.hoje}), para o resultado não depender do dia.
 *
 * <p>Grava {@code <data>-<hora>-<modelo>.md} (tabela para o artigo) e {@code .json} (respostas completas,
 * para revisão manual) em {@code avaliacao.saida}. Encerra com código 0 se a avaliação rodou até o
 * fim, qualquer que seja o resultado; 1 se não rodou (gabarito inválido, seed, banco).
 */
@Component
@Profile("avaliacao")
public class AvaliacaoCeresRunner implements ApplicationRunner {

  /** Pergunta que carrega o modelo de chat e o de embedding antes da medição. */
  static final String PERGUNTA_AQUECIMENTO = "O que e o IBS?";

  private static final Logger log = LoggerFactory.getLogger(AvaliacaoCeresRunner.class);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final Duration FOLGA_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration TIMEOUT_OLLAMA = Duration.ofSeconds(5);
  private static final DateTimeFormatter NOME_ARQUIVO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

  private final OrquestradorService orquestrador;
  private final ValoresEsperados valoresEsperados;
  private final SeedAvaliacao seed;
  private final ModeloLinguagem modeloLinguagem;
  private final AssistenteProperties propriedades;
  private final ObjectMapper objectMapper;
  private final Environment ambiente;
  private final ConfigurableApplicationContext contexto;
  private final String arquivoGabarito;
  private final String diretorioSaida;
  private final LocalDate hoje;
  private final String hardwareInformado;
  private final Set<String> idsSelecionados;
  private ExecutorService executor;
  private int execucoresCriados;

  /** Constrói o comando com os parâmetros {@code avaliacao.*}. */
  public AvaliacaoCeresRunner(
      OrquestradorService orquestrador,
      FerramentasDadosProdutor ferramentas,
      SeedAvaliacao seed,
      ModeloLinguagem modeloLinguagem,
      AssistenteProperties propriedades,
      ObjectMapper objectMapper,
      Environment ambiente,
      ConfigurableApplicationContext contexto,
      @Value("${avaliacao.gabarito:docs/avaliacao/gabarito-ceres.json}") String arquivoGabarito,
      @Value("${avaliacao.saida:docs/avaliacao/resultados}") String diretorioSaida,
      @Value("${avaliacao.hoje:2026-09-15}") String hoje,
      @Value("${avaliacao.hardware:}") String hardwareInformado,
      @Value("${avaliacao.ids:}") String ids) {
    this.orquestrador = orquestrador;
    this.valoresEsperados = new ValoresEsperados(ferramentas);
    this.seed = seed;
    this.modeloLinguagem = modeloLinguagem;
    this.propriedades = propriedades;
    this.objectMapper = objectMapper;
    this.ambiente = ambiente;
    this.contexto = contexto;
    this.arquivoGabarito = arquivoGabarito;
    this.diretorioSaida = diretorioSaida;
    this.hoje = LocalDate.parse(hoje);
    this.hardwareInformado = hardwareInformado;
    this.idsSelecionados = Arrays.stream(ids.split(","))
        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
  }

  /** Avalia e encerra a aplicação. */
  @Override
  public void run(ApplicationArguments args) {
    int codigo = 1;
    executor = novoExecutor();
    try {
      if (!ambiente.acceptsProfiles(Profiles.of("dev"))) {
        throw new IllegalStateException(
            "A avaliacao grava um seed ficticio: so roda com o profile dev (dev,avaliacao)");
      }
      Gabarito gabarito = carregarGabarito();
      ProdutoresAvaliacao produtores = seed.semear();
      Map<String, List<String>> esperados =
          valoresEsperados.calcular(gabarito, produtores.avaliado(), hoje);

      OffsetDateTime inicio = OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
      long inicioNs = System.nanoTime();
      log.info("Aquecendo o modelo (pergunta descartada)");
      executar(PERGUNTA_AQUECIMENTO, produtores);

      List<ResultadoPergunta> resultados = new ArrayList<>();
      for (PerguntaGabarito pergunta : gabarito.perguntas()) {
        ExecucaoPergunta execucao = executar(pergunta.pergunta(), produtores);
        ResultadoPergunta resultado = AvaliadorResposta.avaliar(
            pergunta, execucao, esperados.getOrDefault(pergunta.id(), List.of()));
        resultados.add(resultado);
        log.info("{} rota={} desfecho={} {} ms", pergunta.id(), execucao.rota(),
            execucao.erro() == null ? execucao.desfecho() : execucao.erro(),
            execucao.latenciaMs());
      }

      RelatorioAvaliacao relatorio = new RelatorioAvaliacao(new RelatorioAvaliacao.Ambiente(
          inicio, hoje, gabarito.versao(), modeloLinguagem.identificacao(), modeloEmbedding(),
          hardware(), Duration.ofNanos(System.nanoTime() - inicioNs).toSeconds()), resultados);
      gravar(relatorio, gabarito);
      codigo = 0;
    } catch (IOException | RuntimeException e) {
      log.error("Avaliacao da Ceres falhou: {}", e.getMessage(), e);
    } finally {
      executor.shutdownNow();
      int saida = codigo;
      System.exit(SpringApplication.exit(contexto, () -> saida));
    }
  }

  private Gabarito carregarGabarito() throws IOException {
    Gabarito completo = objectMapper.readValue(Path.of(arquivoGabarito).toFile(), Gabarito.class);
    if (idsSelecionados.isEmpty()) {
      return completo;
    }
    List<PerguntaGabarito> selecionadas = completo.perguntas().stream()
        .filter(p -> idsSelecionados.contains(p.id())).toList();
    if (selecionadas.size() != idsSelecionados.size()) {
      throw new IllegalArgumentException(
          "avaliacao.ids tem id fora do gabarito: " + idsSelecionados);
    }
    return new Gabarito(completo.versao(), selecionadas);
  }

  /** Uma pergunta, com o timeout da API; falha vira desfecho, nunca interrompe a avaliação. */
  private ExecucaoPergunta executar(String pergunta, ProdutoresAvaliacao produtores) {
    ContextoAssistente contextoPergunta = new ContextoAssistente(produtores.avaliado(), hoje);
    AtomicReference<Intencao> rota = new AtomicReference<>();
    long inicio = System.nanoTime();
    Future<ResultadoOrquestracao> futuro = executor.submit(
        () -> orquestrador.responder(pergunta, contextoPergunta, rota::set));
    try {
      ResultadoOrquestracao r = futuro.get(
          propriedades.getTimeoutPergunta().plus(FOLGA_TIMEOUT).toMillis(), TimeUnit.MILLISECONDS);
      return new ExecucaoPergunta(r.rota(), r.texto(), r.desfecho(), r.citacoes(),
          contextoPergunta.fontes(), r.modoFallback(), decorrido(inicio), null);
    } catch (TimeoutException e) {
      contextoPergunta.interromper(DesfechoInteracao.TEMPO_ESGOTADO);
      futuro.cancel(true);
      // A thread pode seguir presa na leitura do modelo: a proxima pergunta nao espera por ela.
      executor.shutdownNow();
      executor = novoExecutor();
      return falha(rota, contextoPergunta, DesfechoInteracao.TEMPO_ESGOTADO, inicio, e);
    } catch (ExecutionException e) {
      DesfechoInteracao desfecho = e.getCause() instanceof AssistenteIndisponivelException
          ? DesfechoInteracao.MODELO_INDISPONIVEL
          : DesfechoInteracao.ERRO;
      return falha(rota, contextoPergunta, desfecho, inicio, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Avaliacao interrompida", e);
    }
  }

  private ExecutorService novoExecutor() {
    int numero = ++execucoresCriados;
    return Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "agro-avaliacao-" + numero);
      thread.setDaemon(true);
      return thread;
    });
  }

  private static ExecucaoPergunta falha(AtomicReference<Intencao> rota,
      ContextoAssistente contextoPergunta, DesfechoInteracao desfecho, long inicio, Throwable e) {
    return new ExecucaoPergunta(rota.get(), null, desfecho, List.of(), contextoPergunta.fontes(),
        false, decorrido(inicio), e.getClass().getSimpleName());
  }

  private static long decorrido(long inicioNs) {
    return Duration.ofNanos(System.nanoTime() - inicioNs).toMillis();
  }

  private String modeloEmbedding() {
    return ambiente.getProperty("spring.ai.model.embedding", "?") + "/"
        + ambiente.getProperty("spring.ai.ollama.embedding.options.model", "?");
  }

  /** Hardware informado + o que dá para detectar (JVM, e o Ollama quando ele é o provedor). */
  private List<String> hardware() {
    List<String> linhas = new ArrayList<>();
    if (!hardwareInformado.isBlank()) {
      linhas.add(hardwareInformado);
    }
    Runtime runtime = Runtime.getRuntime();
    String cpu = System.getenv("PROCESSOR_IDENTIFIER");
    linhas.add(String.format(Locale.ROOT, "%s %s, %d nucleos logicos%s, Java %s",
        System.getProperty("os.name"), System.getProperty("os.arch"),
        runtime.availableProcessors(), cpu == null ? "" : " (" + cpu + ")",
        System.getProperty("java.version")));
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      linhas.add(String.format(Locale.ROOT, "Memoria total: %.1f GB",
          os.getTotalMemorySize() / 1_073_741_824.0));
    }
    if ("ollama".equals(ambiente.getProperty("spring.ai.model.chat"))) {
      linhas.addAll(ollama());
    }
    return linhas;
  }

  /** Versão do Ollama e quanto de cada modelo carregado está na GPU ({@code /api/ps}). */
  private List<String> ollama() {
    SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(TIMEOUT_OLLAMA);
    fabrica.setReadTimeout(TIMEOUT_OLLAMA);
    RestClient cliente = RestClient.builder()
        .baseUrl(ambiente.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"))
        .requestFactory(fabrica).build();
    List<String> linhas = new ArrayList<>();
    try {
      JsonNode versao = cliente.get().uri("/api/version").retrieve().body(JsonNode.class);
      linhas.add("Ollama " + (versao == null ? "?" : versao.path("version").asText("?")));
      JsonNode ps = cliente.get().uri("/api/ps").retrieve().body(JsonNode.class);
      if (ps != null) {
        for (JsonNode modelo : ps.path("models")) {
          long total = modelo.path("size").asLong();
          long vram = modelo.path("size_vram").asLong();
          linhas.add(String.format(Locale.ROOT, "%s: %.1f GB, %d%% na GPU",
              modelo.path("name").asText("?"), total / 1_073_741_824.0,
              total == 0 ? 0 : Math.round(100.0 * vram / total)));
        }
      }
    } catch (RuntimeException e) {
      linhas.add("Ollama: nao foi possivel ler /api/ps (" + e.getClass().getSimpleName() + ")");
    }
    return linhas;
  }

  private void gravar(RelatorioAvaliacao relatorio, Gabarito gabarito) throws IOException {
    Path diretorio = Path.of(diretorioSaida);
    Files.createDirectories(diretorio);
    String base = NOME_ARQUIVO.format(relatorio.ambiente().executadoEm()) + "-"
        + relatorio.ambiente().modelo().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-");
    Path markdown = diretorio.resolve(base + ".md");
    Path json = diretorio.resolve(base + ".json");
    Files.writeString(markdown, relatorio.markdown(), StandardCharsets.UTF_8);

    Map<String, String> perguntas = gabarito.perguntas().stream().collect(Collectors.toMap(
        PerguntaGabarito::id, PerguntaGabarito::pergunta, (a, b) -> a, LinkedHashMap::new));
    Map<String, Object> completo = new LinkedHashMap<>();
    completo.put("ambiente", relatorio.ambiente());
    completo.put("metricas", relatorio.metricas());
    completo.put("latenciaP50Ms", RelatorioAvaliacao.percentil(relatorio.resultados(), 50));
    completo.put("latenciaP95Ms", RelatorioAvaliacao.percentil(relatorio.resultados(), 95));
    completo.put("perguntas", perguntas);
    completo.put("resultados", relatorio.resultados());
    objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT)
        .writeValue(json.toFile(), completo);
    log.info("Relatorio gravado em {} e {}", markdown, json);
  }
}

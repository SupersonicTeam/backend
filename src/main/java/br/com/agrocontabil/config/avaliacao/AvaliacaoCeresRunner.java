package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.OrquestradorService;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.avaliacao.SeedAvaliacao.ProdutoresAvaliacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
 * {@code avaliacao}, e só roda junto com {@code dev} (nunca com {@code prod} ou {@code homolog}):
 * grava um seed fictício no banco.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,avaliacao \
 *   -Dspring-boot.run.arguments="--avaliacao.hardware='i5-12450H, 16 GB, RTX 3050 4 GB'"
 * </pre>
 *
 * <p>{@code avaliacao.repeticoes} (padrão 3, issue #264) roda o gabarito inteiro N vezes: o
 * relatório traz a média e a faixa de cada métrica e as perguntas que mudaram de resultado entre
 * as rodadas. Toda comparação registrada em {@code docs/avaliacao/} usa 3. Antes de medir, o
 * comando recusa um gabarito que tenha pergunta copiada no treino ({@link SeparacaoTreinoTeste}).
 *
 * <p>Usa o modelo real (o configurado em {@code spring.ai.*}) e a legislação já ingerida. Cada
 * pergunta passa pelo mesmo {@link OrquestradorService} da API, com o mesmo timeout, mas sem a
 * fila do chat: as perguntas rodam uma por vez, e a latência medida é a da Ceres, não a de espera.
 * O "hoje" da Ceres é fixo ({@code avaliacao.hoje}), para o resultado não depender do dia.
 *
 * <p>Grava em {@code avaliacao.saida}: {@code <data>-<hora>-<modelo>.md} (tabela para o artigo),
 * {@code .json} (respostas completas, para revisão manual), {@code -clareza-cego.csv} (pergunta e
 * resposta embaralhadas, para o avaliador externo da #275) e {@code -clareza-chave.json} (a chave
 * do CSV, que não vai ao avaliador). Encerra com código 0 se a avaliação rodou até o
 * fim, qualquer que seja o resultado; 1 se não rodou (gabarito inválido, seed, banco, ou modelo
 * que não respondeu à pergunta de aquecimento).
 */
@Component
@Profile("avaliacao")
public class AvaliacaoCeresRunner implements ApplicationRunner {

  /** Pergunta que carrega o modelo de chat e o de embedding antes da medição. */
  static final String PERGUNTA_AQUECIMENTO = "O que e o IBS?";

  private static final Logger log = LoggerFactory.getLogger(AvaliacaoCeresRunner.class);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final int MAXIMO_REPETICOES = 10;
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
  private final int repeticoes;

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
      @Value("${avaliacao.ids:}") String ids,
      @Value("${avaliacao.repeticoes:3}") int repeticoes) {
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
    if (repeticoes < 1 || repeticoes > MAXIMO_REPETICOES) {
      throw new IllegalArgumentException(
          "avaliacao.repeticoes deve estar entre 1 e " + MAXIMO_REPETICOES + ": " + repeticoes);
    }
    this.repeticoes = repeticoes;
  }

  /** Avalia e encerra a aplicação. */
  @Override
  public void run(ApplicationArguments args) {
    int codigo = 1;
    try (ExecucaoComPrazo execucao = new ExecucaoComPrazo(
        orquestrador::responder, propriedades.getTimeoutPergunta().plus(FOLGA_TIMEOUT))) {
      exigirAmbienteDeDesenvolvimento(ambiente);
      Gabarito gabarito = carregarGabarito();
      exigirTreinoSeparado(gabarito, SeparacaoTreinoTeste.TREINO);
      exigirTreinoSeparado(gabarito, SeparacaoTreinoTeste.TREINO_EMBUTIDO);
      ProdutoresAvaliacao produtores = seed.semear();
      Map<String, List<String>> esperados =
          valoresEsperados.calcular(gabarito, produtores.avaliado(), hoje);

      log.info("Aquecendo o modelo (pergunta descartada)");
      exigirModeloNoAr(execucao.executar(PERGUNTA_AQUECIMENTO, contextoNovo(produtores)));

      List<String> hardware = hardware();
      List<RelatorioAvaliacao> rodadas = new ArrayList<>();
      for (int rodada = 1; rodada <= repeticoes; rodada++) {
        OffsetDateTime inicio = OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
        long inicioNs = System.nanoTime();
        List<ResultadoPergunta> resultados = new ArrayList<>();
        for (PerguntaGabarito pergunta : gabarito.perguntas()) {
          ExecucaoPergunta resposta =
              execucao.executar(pergunta.pergunta(), contextoNovo(produtores));
          resultados.add(AvaliadorResposta.avaliar(
              pergunta, resposta, esperados.getOrDefault(pergunta.id(), List.of())));
          log.info("rodada {}/{} {} rota={} desfecho={} {} ms", rodada, repeticoes,
              pergunta.id(), resposta.rota(),
              resposta.erro() == null ? resposta.desfecho() : resposta.erro(),
              resposta.latenciaMs());
        }
        rodadas.add(new RelatorioAvaliacao(new RelatorioAvaliacao.Ambiente(
            inicio, hoje, gabarito.versao(), SeedAvaliacao.VERSAO,
            modeloLinguagem.identificacao(), modeloEmbedding(), hardware,
            Duration.ofNanos(System.nanoTime() - inicioNs).toSeconds()), resultados));
      }
      gravar(new RelatorioRepeticoes(rodadas), gabarito);
      codigo = 0;
    } catch (IOException | RuntimeException e) {
      log.error("Avaliacao da Ceres falhou: {}", e.getMessage(), e);
    } finally {
      int saida = codigo;
      System.exit(SpringApplication.exit(contexto, () -> saida));
    }
  }

  /**
   * O seed fictício só vai para o banco de desenvolvimento: exige {@code dev} e recusa
   * {@code prod} e {@code homolog}, mesmo que {@code dev} também esteja ativo.
   *
   * @throws IllegalStateException fora do ambiente de desenvolvimento
   */
  static void exigirAmbienteDeDesenvolvimento(Environment ambiente) {
    if (!ambiente.acceptsProfiles(Profiles.of("dev"))
        || ambiente.acceptsProfiles(Profiles.of("prod | homolog"))) {
      throw new IllegalStateException("A avaliacao grava um seed ficticio: so roda com dev,avaliacao"
          + " e nunca junto com prod ou homolog");
    }
  }

  /**
   * Sem o modelo, toda pergunta cairia no modo fallback e o comando gravaria um relatório com cara
   * de linha de base. O aquecimento é a prova de vida: falhou, a avaliação para.
   *
   * @throws IllegalStateException se o aquecimento não foi respondido pelo modelo
   */
  static void exigirModeloNoAr(ExecucaoPergunta aquecimento) {
    if (RelatorioAvaliacao.falhaTecnica(aquecimento)) {
      throw new IllegalStateException("O modelo nao respondeu a pergunta de aquecimento (desfecho "
          + aquecimento.desfecho() + "): suba o modelo antes de avaliar");
    }
  }

  /**
   * Gabarito copiado no treino mede memória, não acerto: a avaliação para (issue #264).
   *
   * @throws IllegalStateException se alguma pergunta do gabarito está em {@code treino}
   */
  static void exigirTreinoSeparado(Gabarito gabarito, Path treino) {
    if (!Files.isDirectory(treino)) {
      // Sem o diretorio a conferencia passaria sem conferir nada: rode da raiz do repositorio.
      throw new IllegalStateException("Diretorio de treino nao encontrado (" + treino.toAbsolutePath()
          + "): rode o comando na raiz do repositorio");
    }
    List<SeparacaoTreinoTeste.Contaminacao> contaminacoes =
        SeparacaoTreinoTeste.contaminacoes(gabarito, treino);
    List<String> noPrompt = SeparacaoTreinoTeste.noPromptDoClassificador(gabarito);
    if (!contaminacoes.isEmpty() || !noPrompt.isEmpty()) {
      throw new IllegalStateException("Pergunta do gabarito no treino (o gabarito e teste, nunca "
          + "treino): " + contaminacoes + ", no prompt do classificador: " + noPrompt);
    }
  }

  private ContextoAssistente contextoNovo(ProdutoresAvaliacao produtores) {
    return new ContextoAssistente(produtores.avaliado(), hoje);
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

  private void gravar(RelatorioRepeticoes repeticoes, Gabarito gabarito) throws IOException {
    Path diretorio = Path.of(diretorioSaida);
    Files.createDirectories(diretorio);
    RelatorioAvaliacao.Ambiente ambiente = repeticoes.rodadas().get(0).ambiente();
    String base = NOME_ARQUIVO.format(ambiente.executadoEm()) + "-"
        + ambiente.modelo().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-");
    Path markdown = diretorio.resolve(base + ".md");
    Path json = diretorio.resolve(base + ".json");
    Files.writeString(markdown, repeticoes.markdown(), StandardCharsets.UTF_8);

    Map<String, String> perguntas = gabarito.perguntas().stream().collect(Collectors.toMap(
        PerguntaGabarito::id, PerguntaGabarito::pergunta, (a, b) -> a, LinkedHashMap::new));
    RelatorioAvaliacao todas = repeticoes.todas();
    Map<String, Object> completo = new LinkedHashMap<>();
    completo.put("ambiente", ambiente);
    completo.put("repeticoes", repeticoes.rodadas().size());
    completo.put("metricas", repeticoes.metricas());
    completo.put("porRota", repeticoes.porRota());
    completo.put("porRotaAgregado", repeticoes.porRotaAgregado());
    completo.put("latenciaP50Ms", RelatorioAvaliacao.percentil(todas.resultados(), 50));
    completo.put("latenciaP95Ms", RelatorioAvaliacao.percentil(todas.resultados(), 95));
    completo.put("instaveis", repeticoes.instaveis());
    completo.put("perguntas", perguntas);
    List<Map<String, Object>> rodadas = new ArrayList<>();
    for (RelatorioAvaliacao rodada : repeticoes.rodadas()) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("ambiente", rodada.ambiente());
      r.put("metricas", rodada.metricas());
      r.put("resultados", rodada.resultados());
      rodadas.add(r);
    }
    completo.put("rodadas", rodadas);
    objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT)
        .writeValue(json.toFile(), completo);

    CsvCego.Instrumento cego = CsvCego.gerar(repeticoes, perguntas, new SecureRandom());
    Path csv = diretorio.resolve(base + "-clareza-cego.csv");
    Path chave = diretorio.resolve(base + "-clareza-chave.json");
    Files.writeString(csv, cego.csv(), StandardCharsets.UTF_8);
    objectMapper.writer().with(SerializationFeature.INDENT_OUTPUT)
        .writeValue(chave.toFile(), cego.chave());
    log.info("Relatorio gravado em {} e {}; CSV cego em {} (chave em {}, nao entregue ao "
        + "avaliador)", markdown, json, csv, chave);
  }
}

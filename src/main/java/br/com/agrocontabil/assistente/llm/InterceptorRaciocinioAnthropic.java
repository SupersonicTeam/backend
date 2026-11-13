package br.com.agrocontabil.assistente.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Liga o raciocínio adaptativo do provedor numa chamada só (#269, opção A), o que o Spring AI 1.1.8
 * não faz pelas opções: ele só conhece {@code thinking} {@code enabled} (com {@code budget_tokens},
 * que o Haiku 5.5 recusa) e {@code disabled}, e não tem {@code output_config}.
 *
 * <p>Só age na requisição que traz o cabeçalho {@link RaciocinioPorChamada#CABECALHO} <strong>e</strong>
 * vai ao host da API da Anthropic configurado; nela:
 * <ul>
 *   <li>tira o cabeçalho (ele nunca sai do servidor);</li>
 *   <li>troca o {@code thinking} do corpo por {@code {"type": "adaptive"}} e põe
 *       {@code output_config.effort} com o valor do cabeçalho;</li>
 *   <li>lê da resposta {@code usage.output_tokens_details.thinking_tokens}, quando vem, para
 *       {@link RaciocinioPorChamada}. A saída inteira, raciocínio incluído, segue no
 *       {@code output_tokens} que o Spring AI já lê.</li>
 * </ul>
 * Sem o cabeçalho, a requisição passa intacta (todas as outras chamadas, inclusive as de outros
 * clientes HTTP da aplicação). Com o cabeçalho e outro host, o cabeçalho sai e o corpo não muda. Valor
 * de esforço fora de {@code low}/{@code medium}/{@code high} ou corpo que não é JSON: a requisição
 * falha aqui, antes da rede, e o juiz fecha (falha = reprova).
 */
public class InterceptorRaciocinioAnthropic implements ClientHttpRequestInterceptor {

  private static final Logger log = LoggerFactory.getLogger(InterceptorRaciocinioAnthropic.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> ESFORCOS = Set.of("low", "medium", "high");

  private final String host;

  /**
   * Constrói o interceptor para o host da API da Anthropic.
   *
   * @param urlBase o {@code spring.ai.anthropic.base-url} (ex.: {@code https://api.anthropic.com})
   * @throws IllegalStateException se a URL não tem host (sem esquema, por exemplo): sem isso, as
   *         configurações adaptativas do juiz rodariam caladas sem o raciocínio
   */
  public InterceptorRaciocinioAnthropic(String urlBase) {
    this.host = URI.create(urlBase).getHost();
    if (host == null) {
      throw new IllegalStateException("spring.ai.anthropic.base-url sem host: " + urlBase);
    }
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    String esforco = request.getHeaders().getFirst(RaciocinioPorChamada.CABECALHO);
    if (esforco == null) {
      return execution.execute(request, body);
    }
    request.getHeaders().remove(RaciocinioPorChamada.CABECALHO);
    if (!host.equalsIgnoreCase(request.getURI().getHost())) {
      log.warn("Ceres: marca de raciocinio numa requisicao fora da API da Anthropic; ignorada");
      return execution.execute(request, body);
    }
    String nivel = esforco.strip().toLowerCase(Locale.ROOT);
    if (!ESFORCOS.contains(nivel)) {
      throw new IllegalStateException("Esforço de raciocínio inválido: " + esforco);
    }
    byte[] reescrito = reescrever(body, nivel);
    request.getHeaders().setContentLength(reescrito.length);
    RaciocinioPorChamada.limpar();
    ClientHttpResponse resposta = execution.execute(request, reescrito);
    byte[] corpo;
    try (InputStream entrada = resposta.getBody()) {
      corpo = entrada.readAllBytes();
    }
    RaciocinioPorChamada.registrar(tokensDeRaciocinio(corpo));
    return new RespostaLida(resposta, corpo);
  }

  /** O corpo com {@code thinking} adaptativo e o esforço em {@code output_config}. */
  static byte[] reescrever(byte[] corpo, String esforco) throws IOException {
    JsonNode raiz = JSON.readTree(corpo);
    if (!(raiz instanceof ObjectNode objeto)) {
      throw new IllegalStateException("Corpo da chamada ao modelo não é um objeto JSON");
    }
    objeto.putObject("thinking").put("type", "adaptive");
    JsonNode config = objeto.get("output_config");
    ObjectNode saida = config instanceof ObjectNode o ? o : objeto.putObject("output_config");
    saida.put("effort", esforco);
    return JSON.writeValueAsBytes(objeto);
  }

  /** {@code usage.output_tokens_details.thinking_tokens}, ou nulo. */
  static Integer tokensDeRaciocinio(byte[] corpo) {
    try {
      JsonNode tokens = JSON.readTree(corpo).path("usage").path("output_tokens_details")
          .path("thinking_tokens");
      return tokens.isNumber() ? tokens.asInt() : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** A resposta com o corpo já lido, para o Spring AI ler de novo. */
  private static final class RespostaLida implements ClientHttpResponse {

    private final ClientHttpResponse original;
    private final byte[] corpo;

    RespostaLida(ClientHttpResponse original, byte[] corpo) {
      this.original = original;
      this.corpo = corpo;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return original.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return original.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
      return original.getHeaders();
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(corpo);
    }

    @Override
    public void close() {
      original.close();
    }
  }
}

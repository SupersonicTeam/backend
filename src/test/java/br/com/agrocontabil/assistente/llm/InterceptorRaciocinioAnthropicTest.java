package br.com.agrocontabil.assistente.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** O interceptor que liga o raciocínio só na chamada marcada do juiz (#269, opção A). */
class InterceptorRaciocinioAnthropicTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CORPO = "{\"model\":\"claude-haiku-5-5\",\"max_tokens\":3072,"
      + "\"thinking\":{\"type\":\"disabled\"},\"messages\":[]}";
  private static final String RESPOSTA = "{\"content\":[],\"usage\":{\"input_tokens\":3,"
      + "\"output_tokens\":200,\"output_tokens_details\":{\"thinking_tokens\":150}}}";

  private final InterceptorRaciocinioAnthropic interceptor =
      new InterceptorRaciocinioAnthropic("https://api.anthropic.com");

  @AfterEach
  void limpar() {
    RaciocinioPorChamada.limpar();
  }

  private static MockClientHttpRequest requisicao(String url, String esforco) {
    MockClientHttpRequest r = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
    if (esforco != null) {
      r.getHeaders().add(RaciocinioPorChamada.CABECALHO, esforco);
    }
    return r;
  }

  @Test
  @DisplayName("marcada e no host da Anthropic: thinking adaptativo, esforço, sem a marca, tokens lidos")
  void reescreveAChamadaMarcada() throws IOException {
    MockClientHttpRequest req = requisicao("https://api.anthropic.com/v1/messages", "Medium");
    AtomicReference<byte[]> enviado = new AtomicReference<>();

    ClientHttpResponse resposta = interceptor.intercept(req,
        CORPO.getBytes(StandardCharsets.UTF_8), (r, corpo) -> {
          enviado.set(corpo);
          assertThat(r.getHeaders().containsKey(RaciocinioPorChamada.CABECALHO)).isFalse();
          assertThat(r.getHeaders().getContentLength()).isEqualTo(corpo.length);
          return new MockClientHttpResponse(RESPOSTA.getBytes(StandardCharsets.UTF_8),
              HttpStatus.OK);
        });

    JsonNode corpo = JSON.readTree(enviado.get());
    assertThat(corpo.path("thinking").path("type").asText()).isEqualTo("adaptive");
    assertThat(corpo.path("thinking").has("budget_tokens")).isFalse();
    assertThat(corpo.path("output_config").path("effort").asText()).isEqualTo("medium");
    assertThat(corpo.path("max_tokens").asInt()).isEqualTo(3072);
    // O Spring AI le o corpo de novo: tem de estar inteiro.
    assertThat(new String(resposta.getBody().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo(RESPOSTA);
    assertThat(RaciocinioPorChamada.consumir()).isEqualTo(150);
    assertThat(RaciocinioPorChamada.consumir()).isNull();
  }

  @Test
  @DisplayName("sem a marca, a requisição passa intacta (todas as outras chamadas)")
  void semMarcaPassaIntacta() throws IOException {
    byte[] original = CORPO.getBytes(StandardCharsets.UTF_8);
    AtomicReference<byte[]> enviado = new AtomicReference<>();
    interceptor.intercept(requisicao("https://api.anthropic.com/v1/messages", null), original,
        (r, corpo) -> {
          enviado.set(corpo);
          return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        });
    assertThat(enviado.get()).isSameAs(original);
  }

  @Test
  @DisplayName("marca noutro host: a marca sai, o corpo não muda")
  void outroHostSoTiraAMarca() throws IOException {
    MockClientHttpRequest req = requisicao("http://outro.local/v1/messages", "high");
    byte[] original = CORPO.getBytes(StandardCharsets.UTF_8);
    AtomicReference<byte[]> enviado = new AtomicReference<>();
    interceptor.intercept(req, original, (r, corpo) -> {
      enviado.set(corpo);
      assertThat(r.getHeaders().containsKey(RaciocinioPorChamada.CABECALHO)).isFalse();
      return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    });
    assertThat(enviado.get()).isSameAs(original);
  }

  @Test
  @DisplayName("esforço desconhecido ou corpo que não é objeto JSON falham antes da rede")
  void entradaInvalidaFalha() {
    assertThatThrownBy(() -> interceptor.intercept(
        requisicao("https://api.anthropic.com/v1/messages", "max"),
        CORPO.getBytes(StandardCharsets.UTF_8), (r, corpo) -> {
          throw new AssertionError("nao deveria chamar a rede");
        })).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> InterceptorRaciocinioAnthropic.reescrever(
        "[1]".getBytes(StandardCharsets.UTF_8), "low")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("output_config que já existe é mantido, só com o esforço; sem o detalhe, tokens nulos")
  void mantemOutputConfigETokensAusentes() throws IOException {
    byte[] reescrito = InterceptorRaciocinioAnthropic.reescrever(("{\"output_config\":"
        + "{\"format\":{\"type\":\"json_schema\"}}}").getBytes(StandardCharsets.UTF_8), "low");
    JsonNode corpo = JSON.readTree(reescrito);
    assertThat(corpo.path("output_config").path("format").path("type").asText())
        .isEqualTo("json_schema");
    assertThat(corpo.path("output_config").path("effort").asText()).isEqualTo("low");
    assertThat(InterceptorRaciocinioAnthropic.tokensDeRaciocinio(
        "{\"usage\":{\"output_tokens\":5}}".getBytes(StandardCharsets.UTF_8))).isNull();
    assertThat(InterceptorRaciocinioAnthropic.tokensDeRaciocinio(
        "nao e json".getBytes(StandardCharsets.UTF_8))).isNull();
  }
  @Test
  @DisplayName("URL base sem host falha na construção, em vez de desligar o raciocínio calado")
  void urlSemHostFalha() {
    assertThatThrownBy(() -> new InterceptorRaciocinioAnthropic("api.anthropic.com"))
        .isInstanceOf(IllegalStateException.class);
  }
}

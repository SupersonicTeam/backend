package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Testes do {@link LimiteCorpoAssistenteFilter} (issue #144). */
class LimiteCorpoAssistenteFilterTest {

  private final LimiteCorpoAssistenteFilter filtro =
      new LimiteCorpoAssistenteFilter(new ObjectMapper().registerModule(new JavaTimeModule()));

  @Test
  @DisplayName("Content-Length acima do limite: 413 no envelope, a cadeia nao segue")
  void acimaDoLimite413() throws Exception {
    MockHttpServletRequest request = requisicao("/api/assistente/chat",
        new byte[LimiteCorpoAssistenteFilter.LIMITE_BYTES + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain cadeia = new MockFilterChain();

    filtro.doFilter(request, response, cadeia);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    assertThat(cadeia.getRequest()).isNull();
  }

  @Test
  @DisplayName("Caminho codificado (/api/%61ssistente) nao escapa do limite")
  void caminhoCodificadoNaoEscapa() throws Exception {
    MockHttpServletRequest request = requisicao("/api/%61ssistente/chat",
        new byte[LimiteCorpoAssistenteFilter.LIMITE_BYTES + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filtro.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  @DisplayName("Fora de /api/assistente o filtro nao age")
  void outrasRotasIntactas() throws Exception {
    MockHttpServletRequest request = requisicao("/api/notas/importar",
        new byte[LimiteCorpoAssistenteFilter.LIMITE_BYTES + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain cadeia = new MockFilterChain();

    filtro.doFilter(request, response, cadeia);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(cadeia.getRequest()).isSameAs(request);
  }

  @Test
  @DisplayName("Sem Content-Length (chunked): stream e reader param no limite")
  void chunkedCortadoNoLimite() throws Exception {
    byte[] corpo = "x".repeat(LimiteCorpoAssistenteFilter.LIMITE_BYTES * 3)
        .getBytes(StandardCharsets.UTF_8);
    AtomicReference<Integer> lidosStream = new AtomicReference<>();
    AtomicReference<Integer> lidosReader = new AtomicReference<>();

    filtro.doFilter(requisicaoChunked(corpo), new MockHttpServletResponse(),
        (req, res) -> {
          try (InputStream in = ((HttpServletRequest) req).getInputStream()) {
            lidosStream.set(in.readAllBytes().length);
          }
        });
    filtro.doFilter(requisicaoChunked(corpo), new MockHttpServletResponse(),
        (req, res) -> {
          try (BufferedReader reader = ((HttpServletRequest) req).getReader()) {
            int total = 0;
            while (reader.read() >= 0) {
              total++;
            }
            lidosReader.set(total);
          }
        });

    assertThat(lidosStream.get()).isEqualTo(LimiteCorpoAssistenteFilter.LIMITE_BYTES);
    assertThat(lidosReader.get()).isEqualTo(LimiteCorpoAssistenteFilter.LIMITE_BYTES);
  }

  private static MockHttpServletRequest requisicao(String uri, byte[] corpo) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setContent(corpo);
    return request;
  }

  /** MockHttpServletRequest com Content-Length desconhecido (-1), como uma requisição chunked. */
  private static MockHttpServletRequest requisicaoChunked(byte[] corpo) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistente/chat") {
      @Override
      public long getContentLengthLong() {
        return -1;
      }

      @Override
      public int getContentLength() {
        return -1;
      }
    };
    request.setContent(corpo);
    request.setCharacterEncoding("UTF-8");
    return request;
  }
}

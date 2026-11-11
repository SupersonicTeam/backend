package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.support.RequisicaoSemCaminhoAnalisado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@DisplayName("Escritor da CSP sem ler o caminho analisado (#345)")
class SecurityConfigCspTest {

  private static MockHttpServletResponse escrever(HeaderWriter escritor, String uri) {
    MockHttpServletResponse response = new MockHttpServletResponse();
    escritor.writeHeaders(
        new RequisicaoSemCaminhoAnalisado(new MockHttpServletRequest("GET", uri)), response);
    return response;
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/x", "/api/v1/health", "/%61pi/x", "/api"})
  @DisplayName("escreve a CSP na API, inclusive com o caminho codificado, sem tocar no atributo")
  void escreveNaApi(String uri) {
    assertThat(escrever(SecurityConfig.escritorCsp(), uri).getHeader("Content-Security-Policy"))
        .as(uri).isEqualTo(SecurityConfig.CSP_API);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html", "/apix", "/error"})
  @DisplayName("fora da API, sem CSP")
  void naoEscreveForaDaApi(String uri) {
    assertThat(escrever(SecurityConfig.escritorCsp(), uri).getHeader("Content-Security-Policy"))
        .as(uri).isNull();
  }

  @Test
  @DisplayName("falsificacao: o matcher antigo (PathPatternRequestMatcher) le o atributo e falha")
  void matcherAntigoFalhaNoVigia() {
    HeaderWriter antigo = new DelegatingRequestMatcherHeaderWriter(
        PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
        new StaticHeadersWriter("Content-Security-Policy", SecurityConfig.CSP_API));

    assertThatThrownBy(() -> escrever(antigo, "/api/x")).isInstanceOf(AssertionError.class)
        .hasMessageContaining("caminho analisado");
  }
}

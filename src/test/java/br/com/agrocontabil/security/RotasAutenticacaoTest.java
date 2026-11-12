package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.support.RequisicaoSemCaminhoAnalisado;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;

@DisplayName("RotasAutenticacao.ehApi — caminho da API sem estado (#345)")
class RotasAutenticacaoTest {

  /** Requisição com a URI crua, como o container a entrega, embrulhada pelo vigia do atributo. */
  private static HttpServletRequest requisicao(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    return new RequisicaoSemCaminhoAnalisado(request);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/api", "/api/", "/api/notas", "/api/v1/health", "/%61pi/notas", "/%61%70%69/v1/health",
      "/api;jsessionid=1/notas", "/api/contador/produtores/1/notas"})
  @DisplayName("/api e tudo abaixo de /api/, inclusive codificado ou com parametro de matriz")
  void ehApi(String uri) {
    assertThat(RotasAutenticacao.ehApi(requisicao(uri))).as(uri).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/apix", "/apix/notas", "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html",
      "/actuator/health", "/error", "/interno/tls/permitido", "/", "/api-docs"})
  @DisplayName("fora da API: /apix, Swagger, Actuator, /error e o ask do TLS")
  void naoEhApi(String uri) {
    assertThat(RotasAutenticacao.ehApi(requisicao(uri))).as(uri).isFalse();
  }

  @Test
  @DisplayName("context path fica de fora da comparacao")
  void contextPath() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/notas");
    request.setContextPath("/app");
    assertThat(RotasAutenticacao.ehApi(new RequisicaoSemCaminhoAnalisado(request))).isTrue();
  }

  @Test
  @DisplayName("o vigia pega o matcher que le o caminho analisado")
  void vigiaPegaQuemLeOAtributo() {
    HttpServletRequest vigiada = requisicao("/api/notas");
    assertThatThrownBy(() -> vigiada.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE))
        .isInstanceOf(AssertionError.class);
  }
}

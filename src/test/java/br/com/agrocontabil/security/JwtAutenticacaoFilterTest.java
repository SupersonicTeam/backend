package br.com.agrocontabil.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.service.AutenticacaoTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("JwtAutenticacaoFilter — Bearer na cadeia de filtros (#206)")
class JwtAutenticacaoFilterTest {

  private final AutenticacaoTokenService tokens = mock(AutenticacaoTokenService.class);
  private final SecurityErrorResponder responder =
      new SecurityErrorResponder(new ObjectMapper().findAndRegisterModules());
  private final JwtAutenticacaoFilter filtro = new JwtAutenticacaoFilter(tokens, responder);

  @AfterEach
  void limpar() {
    SecurityContextHolder.clearContext();
  }

  private static MockHttpServletRequest comAutorizacao(String valor) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
    request.addHeader("Authorization", valor);
    return request;
  }

  @Test
  @DisplayName("Bearer valido: identidade na cadeia e contexto limpo ao final")
  void valido() throws Exception {
    UsuarioAutenticado usuario = UsuarioAutenticado.deProdutor(
        Produtor.builder().id(UUID.randomUUID()).nomeCompleto("F").build());
    when(tokens.autenticar("abc")).thenReturn(Optional.of(usuario));
    FilterChain cadeia = (req, res) -> assertThat(
        SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(usuario);

    filtro.doFilter(comAutorizacao("Bearer abc"), new MockHttpServletResponse(), cadeia);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("Bearer invalido: 401 invalid_token e a cadeia nao segue")
  void invalido() throws Exception {
    when(tokens.autenticar("abc")).thenReturn(Optional.empty());
    FilterChain cadeia = mock(FilterChain.class);
    MockHttpServletResponse resposta = new MockHttpServletResponse();

    filtro.doFilter(comAutorizacao("Bearer abc"), resposta, cadeia);

    assertThat(resposta.getStatus()).isEqualTo(401);
    assertThat(resposta.getHeader("WWW-Authenticate")).contains("invalid_token");
    verify(cadeia, never()).doFilter(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("banco fora ao autenticar: 500 no envelope, sem detalhe e sem seguir a cadeia")
  void falhaDeBanco() throws Exception {
    when(tokens.autenticar(anyString()))
        .thenThrow(new DataAccessResourceFailureException("Connection refused to db:5432"));
    MockHttpServletResponse resposta = new MockHttpServletResponse();

    filtro.doFilter(comAutorizacao("Bearer abc"), resposta, mock(FilterChain.class));

    assertThat(resposta.getStatus()).isEqualTo(500);
    assertThat(resposta.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("5432");
  }

  @Test
  @DisplayName("rota publica de autenticacao: Bearer ignorado, sem consultar tokens; GET nao conta")
  void rotaDeAutenticacaoIgnoraBearer() throws Exception {
    FilterChain cadeia = mock(FilterChain.class);
    MockHttpServletRequest refresh = new MockHttpServletRequest("POST", "/api/auth/refresh");
    refresh.addHeader("Authorization", "Bearer expirado");

    filtro.doFilter(refresh, new MockHttpServletResponse(), cadeia);

    verify(cadeia).doFilter(org.mockito.ArgumentMatchers.eq(refresh),
        org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(tokens);

    // Mesmo caminho com outro metodo nao e rota de autenticacao: o token e conferido.
    when(tokens.autenticar("expirado")).thenReturn(Optional.empty());
    MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/auth/refresh");
    get.addHeader("Authorization", "Bearer expirado");
    MockHttpServletResponse resposta = new MockHttpServletResponse();
    filtro.doFilter(get, resposta, mock(FilterChain.class));
    assertThat(resposta.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("sem Authorization ou outro esquema: segue sem consultar tokens")
  void semBearer() throws Exception {
    FilterChain cadeia = mock(FilterChain.class);
    MockHttpServletRequest semHeader = new MockHttpServletRequest("GET", "/api/auth/me");

    filtro.doFilter(semHeader, new MockHttpServletResponse(), cadeia);
    filtro.doFilter(comAutorizacao("Basic dXNlcjpwdw=="), new MockHttpServletResponse(), cadeia);

    verifyNoInteractions(tokens);
  }
}

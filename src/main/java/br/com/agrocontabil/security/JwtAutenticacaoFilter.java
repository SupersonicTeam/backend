package br.com.agrocontabil.security;

import br.com.agrocontabil.service.AutenticacaoTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica a requisição pelo JWT de {@code Authorization: Bearer} (issue #206).
 *
 * <p>Contrato:
 *
 * <ul>
 *   <li>sem {@code Authorization} → segue sem identidade (rotas públicas respondem; as demais caem
 *       no 401 do {@link SecurityErrorResponder}). Em {@code dev}/{@code homolog} o
 *       {@code MockIdentidadeFilter}, que roda depois, ainda pode resolver o {@code X-Usuario-Id};
 *   <li>{@code Bearer} válido → identidade do token, e o mock não é consultado;
 *   <li>{@code Bearer} inválido por qualquer motivo → <strong>401 aqui mesmo</strong>, sem seguir a
 *       cadeia. Seguir sem identidade deixaria o mock cair no produtor semente e transformaria um
 *       token adulterado num acesso válido;
 *   <li>outro esquema ({@code Basic}...) → segue sem identidade, e o 401 vem da cadeia.
 * </ul>
 *
 * <p>Não é um bean de propósito: o Spring Boot registraria todo {@code Filter} do contexto também
 * no container, e ele rodaria duas vezes (uma fora da cadeia de segurança). O
 * {@code SecurityConfig} o instancia e o põe na posição certa.
 */
public class JwtAutenticacaoFilter extends OncePerRequestFilter {

  private static final String PREFIXO_BEARER = "bearer ";

  private final AutenticacaoTokenService autenticacaoTokenService;
  private final SecurityErrorResponder errorResponder;

  public JwtAutenticacaoFilter(
      AutenticacaoTokenService autenticacaoTokenService, SecurityErrorResponder errorResponder) {
    this.autenticacaoTokenService = autenticacaoTokenService;
    this.errorResponder = errorResponder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String autorizacao = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (autorizacao == null || !ehBearer(autorizacao)) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = autorizacao.substring(PREFIXO_BEARER.length()).strip();
    Optional<UsuarioAutenticado> usuario = autenticacaoTokenService.autenticar(token);
    if (usuario.isEmpty()) {
      errorResponder.recusarToken(request, response);
      return;
    }

    UsuarioAutenticado identidade = usuario.get();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(identidade, null, identidade.authorities()));
    try {
      filterChain.doFilter(request, response);
    } finally {
      // Threads do Tomcat sao reusadas: a identidade nao pode vazar para a proxima requisicao.
      SecurityContextHolder.clearContext();
    }
  }

  /** O esquema é case-insensitive (RFC 9110 §11.1). */
  private static boolean ehBearer(String autorizacao) {
    return autorizacao.length() > PREFIXO_BEARER.length()
        && autorizacao.regionMatches(true, 0, PREFIXO_BEARER, 0, PREFIXO_BEARER.length());
  }
}

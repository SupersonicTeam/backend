package br.com.agrocontabil.security;

import br.com.agrocontabil.config.RefreshProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * O cookie que leva o refresh token (issue #207).
 *
 * <p>Atributos, todos por propriedade ({@code app.seguranca.refresh.cookie-*}):
 *
 * <ul>
 *   <li>{@code HttpOnly} sempre: o JavaScript da página nunca lê o refresh, então um XSS não
 *       exfiltra a sessão longa — no máximo usa o access token de 15 min enquanto a página está
 *       aberta;
 *   <li>{@code Secure} (padrão): só vai por HTTPS. Desligado só em dev sobre HTTP;
 *   <li>{@code SameSite=Strict} (padrão): o navegador não manda o cookie em requisição iniciada por
 *       outro site — é a proteção de CSRF de {@code /refresh} e {@code /logout}, que não exigem
 *       access token. O CORS restrito às origens do frontend completa: uma página de outra origem
 *       não lê a resposta;
 *   <li>{@code Path=/api/auth}: o cookie não acompanha as demais chamadas da API.
 * </ul>
 */
@Component
@ConditionalOnWebApplication
public class CookieRefresh {

  private final RefreshProperties propriedades;
  private final Clock clock;

  public CookieRefresh(RefreshProperties propriedades, Clock clock) {
    this.propriedades = propriedades;
    this.clock = clock;
  }

  /**
   * Cookie com o refresh token, vivo até ele expirar.
   *
   * @param refreshToken token
   * @param expiraEm     validade do token
   * @return o valor do {@code Set-Cookie}
   */
  public String criar(String refreshToken, Instant expiraEm) {
    Duration maxAge = Duration.between(clock.instant(), expiraEm);
    return base(refreshToken).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build()
        .toString();
  }

  /**
   * Cookie que apaga o do navegador (logout).
   *
   * @return o valor do {@code Set-Cookie}
   */
  public String apagar() {
    return base("").maxAge(Duration.ZERO).build().toString();
  }

  /**
   * Lê o refresh token do cookie da requisição.
   *
   * @param request requisição
   * @return o token, se o cookie veio
   */
  public Optional<String> ler(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(c -> propriedades.cookieNome().equals(c.getName()))
        .map(Cookie::getValue)
        .filter(v -> v != null && !v.isBlank())
        .findFirst();
  }

  private ResponseCookie.ResponseCookieBuilder base(String valor) {
    return ResponseCookie.from(propriedades.cookieNome(), valor)
        .httpOnly(true)
        .secure(propriedades.cookieSeguro())
        .sameSite(propriedades.cookieSameSite())
        .path(propriedades.cookieCaminho());
  }
}

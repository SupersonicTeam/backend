package br.com.agrocontabil.security;

import java.util.Set;

/**
 * Rotas públicas de autenticação (issues #205–#207): fonte única para o {@code SecurityConfig},
 * que as libera, e para o {@link JwtAutenticacaoFilter}, que ignora o Bearer nelas.
 *
 * <p>Duas listas iguais em dois arquivos se separam no primeiro endpoint novo; uma rota pública
 * que o filtro não conhecesse responderia 401 a quem mandasse um token expirado junto.
 */
public final class RotasAutenticacao {

  /** Cadastro de conta (#205). */
  public static final String CADASTRO = "/api/auth/cadastro";

  /** Login: e-mail e senha pelo par de tokens (#206). */
  public static final String LOGIN = "/api/auth/login";

  /** Renovação pelo cookie de refresh (#207). */
  public static final String REFRESH = "/api/auth/refresh";

  /** Logout deste dispositivo pelo cookie de refresh (#207). */
  public static final String LOGOUT = "/api/auth/logout";

  /** Todas as rotas públicas de autenticação, só com {@code POST}. */
  public static final Set<String> PUBLICAS_POST = Set.of(CADASTRO, LOGIN, REFRESH, LOGOUT);

  private RotasAutenticacao() {
  }
}

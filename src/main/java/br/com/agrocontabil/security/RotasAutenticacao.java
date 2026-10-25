package br.com.agrocontabil.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UrlPathHelper;

/**
 * Rotas públicas de autenticação (issues #205–#209): fonte única para o {@code SecurityConfig},
 * que as libera, e para o {@link JwtAutenticacaoFilter}, que ignora o Bearer nelas.
 *
 * <p>Duas listas iguais em dois arquivos se separam no primeiro endpoint novo; uma rota pública
 * que o filtro não conhecesse responderia 401 a quem mandasse um token expirado junto.
 */
public final class RotasAutenticacao {

  /** Cadastro de conta (#205). */
  public static final String CADASTRO = "/api/auth/cadastro";

  /** Cadastro de contador pelo link do convite de equipe (#228). */
  public static final String CADASTRO_EQUIPE = "/api/auth/cadastro/equipe";

  /** Login: e-mail e senha pelo par de tokens (#206). */
  public static final String LOGIN = "/api/auth/login";

  /** Renovação pelo cookie de refresh (#207). */
  public static final String REFRESH = "/api/auth/refresh";

  /** Logout deste dispositivo pelo cookie de refresh (#207). */
  public static final String LOGOUT = "/api/auth/logout";

  /** Pedido de redefinição de senha por e-mail (#209). */
  public static final String SENHA_ESQUECI = "/api/auth/senha/esqueci";

  /** Redefinição de senha com o token do e-mail (#209). */
  public static final String SENHA_REDEFINIR = "/api/auth/senha/redefinir";

  /** Todas as rotas públicas de autenticação, só com {@code POST}. */
  public static final Set<String> PUBLICAS_POST =
      Set.of(CADASTRO, CADASTRO_EQUIPE, LOGIN, REFRESH, LOGOUT, SENHA_ESQUECI, SENHA_REDEFINIR);

  /** Decodifica o percent-encoding e tira o conteúdo após {@code ;} — como o roteamento faz. */
  private static final UrlPathHelper CAMINHOS = new UrlPathHelper();

  private RotasAutenticacao() {
  }

  /**
   * Caminho da requisição como o Spring o roteia: decodificado, sem o context path e sem
   * parâmetros de matriz.
   *
   * <p>Comparar o {@code getRequestURI()} cru abre um desvio (achado do review de segurança da
   * #208): {@code POST /api/auth/logi%6E} chega ao login — o Spring decodifica para rotear —, mas
   * não casaria com {@code "/api/auth/login"} e escaparia do limite de tentativas. Todo filtro
   * que decide por caminho precisa usar este método.
   *
   * @param request requisição
   * @return caminho decodificado dentro da aplicação
   */
  public static String caminho(HttpServletRequest request) {
    return CAMINHOS.getPathWithinApplication(request);
  }

  /**
   * A requisição é um {@code POST} para uma das rotas públicas de autenticação?
   *
   * @param request requisição
   * @return {@code true} para cadastro, login, refresh e logout
   */
  public static boolean ehPublicaPost(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod()) && PUBLICAS_POST.contains(caminho(request));
  }
}

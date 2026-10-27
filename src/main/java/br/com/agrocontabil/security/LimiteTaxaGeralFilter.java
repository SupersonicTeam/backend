package br.com.agrocontabil.security;

import br.com.agrocontabil.config.LimiteTaxaProperties;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite geral de requisições em {@code /api/**} e limite próprio do chat da Ceres (issue #150).
 * Acima dele, 429 {@code MUITAS_TENTATIVAS} com {@code Retry-After}.
 *
 * <p><strong>Por quem, não só por onde.</strong> Roda depois da identificação (JWT e mock), e a
 * chave é a identidade quando há uma: um escritório inteiro atrás do mesmo NAT não divide o limite
 * de 100/min, e um usuário não ganha cota trocando de rede. Sem identidade (rotas públicas), a
 * chave é o IP — agrupado por /64 no IPv6, como no limite de autenticação.
 *
 * <p>O chat tem limite próprio, menor (20/min): cada pergunta custa uma chamada ao modelo. Ele se
 * soma ao geral — uma pergunta gasta uma ficha de cada.
 *
 * <p>Não é bean, pelo mesmo motivo dos outros filtros da cadeia: o {@code SecurityConfig} o
 * instancia e o põe na posição certa.
 */
public class LimiteTaxaGeralFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(LimiteTaxaGeralFilter.class);

  private static final Duration MINUTO = Duration.ofMinutes(1);

  private static final String PREFIXO_API = "/api/";

  private static final String PREFIXO_CHAT = "/api/assistente/chat";

  private final LimitadorTaxa geral;
  private final LimitadorTaxa chat;
  private final SecurityErrorResponder errorResponder;

  public LimiteTaxaGeralFilter(
      LimiteTaxaProperties propriedades, SecurityErrorResponder errorResponder) {
    this.geral = new LimitadorTaxa(propriedades.geralPorMinuto(), MINUTO, propriedades.maxChaves());
    this.chat = new LimitadorTaxa(propriedades.chatPorMinuto(), MINUTO, propriedades.maxChaves());
    this.errorResponder = errorResponder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String caminho = RotasAutenticacao.caminho(request);
    // Rotas publicas de autenticacao ja tem limite proprio por IP (#208/#209): conta-las aqui de
    // novo faria quem divide IP (CGNAT de operadora movel) perder o refresh por trafego alheio.
    if (!caminho.startsWith(PREFIXO_API) || RotasAutenticacao.ehPublicaPost(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String chave = chaveCliente(request);
    long esperar = geral.tentar(chave);
    if (esperar == 0 && ehChat(caminho)) {
      esperar = chat.tentar(chave);
    }
    if (esperar > 0) {
      log.warn("Limite geral de requisicoes excedido: rota={}, cliente={}", caminho,
          chave.startsWith("u:") ? chave : "ip");
      errorResponder.muitasTentativas(request, response, esperar);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /** {@code /api/assistente/chat} e o que estiver sob ele ({@code /chat/stream}), e só. */
  private static boolean ehChat(String caminho) {
    return caminho.equals(PREFIXO_CHAT) || caminho.startsWith(PREFIXO_CHAT + "/");
  }

  /**
   * Identidade autenticada, ou o IP (/64 no IPv6) de quem ainda não tem uma.
   *
   * <p>Requisição sem {@code Authorization} e sem {@code X-Usuario-Id} vai pelo IP mesmo que haja
   * principal: em dev o mock a autentica como o produtor semente, e todo cliente sem header
   * dividiria um balde só — um cliente com 100 req/min derrubaria o login de todos (achado do
   * review da #150). Em prod o mock não existe e nada muda.
   */
  private static String chaveCliente(HttpServletRequest request) {
    Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
    boolean identidadeDeclarada = request.getHeader(HttpHeaders.AUTHORIZATION) != null
        || request.getHeader(MockIdentidadeFilter.HEADER_USUARIO_ID) != null;
    if (identidadeDeclarada && autenticacao != null
        && autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario) {
      return "u:" + usuario.papel() + ":" + usuario.id();
    }
    return "ip:" + LimiteTaxaAutenticacaoFilter.chaveCliente(request.getRemoteAddr());
  }
}

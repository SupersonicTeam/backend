package br.com.agrocontabil.security;

import br.com.agrocontabil.config.LimiteTaxaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    if (!caminho.startsWith(PREFIXO_API)) {
      filterChain.doFilter(request, response);
      return;
    }

    String chave = chaveCliente(request);
    long esperar = geral.tentar(chave);
    if (esperar == 0 && caminho.startsWith(PREFIXO_CHAT)) {
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

  /** Identidade autenticada, ou o IP (/64 no IPv6) de quem ainda não tem uma. */
  private static String chaveCliente(HttpServletRequest request) {
    Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
    if (autenticacao != null
        && autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario) {
      return "u:" + usuario.papel() + ":" + usuario.id();
    }
    return "ip:" + LimiteTaxaAutenticacaoFilter.chaveCliente(request.getRemoteAddr());
  }
}

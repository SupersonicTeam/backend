package br.com.agrocontabil.security;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite próprio do endpoint público do escritório (issue #464): 30 consultas por minuto por IP
 * (/64 no IPv6), contra a varredura de slugs. Soma-se ao limite geral de {@code /api/**}. Acima
 * dele, 429 {@code MUITAS_TENTATIVAS} com {@code Retry-After}.
 *
 * <p>A chave é sempre o IP: a rota é pública e ignora o Bearer. Não é bean, como os outros filtros
 * da cadeia: o {@code SecurityConfig} o instancia.
 */
public class LimiteTaxaPublicoFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(LimiteTaxaPublicoFilter.class);

  private final LimitadorTaxa limitador;
  private final SecurityErrorResponder errorResponder;

  public LimiteTaxaPublicoFilter(int porMinuto, int maxChaves,
      SecurityErrorResponder errorResponder) {
    this.limitador = new LimitadorTaxa(porMinuto, Duration.ofMinutes(1), maxChaves);
    this.errorResponder = errorResponder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (HttpMethod.GET.matches(request.getMethod())
        && RotasAutenticacao.caminho(request).startsWith(RotasEscritorio.PUBLICO_PREFIXO)) {
      long esperar = limitador.tentar(
          LimiteTaxaAutenticacaoFilter.chaveCliente(request.getRemoteAddr()));
      if (esperar > 0) {
        log.warn("Limite do endpoint publico do escritorio excedido: ip={}",
            MascaraDadosPessoais.truncarIp(request.getRemoteAddr()));
        errorResponder.muitasTentativas(request, response, esperar);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }
}

package br.com.agrocontabil.security;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.config.ForcaBrutaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de tentativas por IP nas rotas públicas de autenticação (issue #208): login, cadastro e
 * refresh. Acima do limite, 429 {@code MUITAS_TENTATIVAS} com {@code Retry-After}, antes de
 * qualquer consulta ao banco ou BCrypt.
 *
 * <p>Complementa o bloqueio por conta: aquele para quem tenta muitas senhas num e-mail; este para
 * quem tenta poucas senhas em muitos e-mails, para quem varre o cadastro procurando quem tem conta
 * e para quem tentaria travar contas alheias em massa.
 *
 * <p><strong>O IP é o do {@code remoteAddr}.</strong> Atrás de proxy, o Tomcat só o substitui pelo
 * {@code X-Forwarded-For} com {@code server.forward-headers-strategy=native} — e mesmo assim só
 * quando quem conecta é um proxy interno ({@code RemoteIpValve}). Ler o header direto aqui deixaria
 * qualquer cliente trocar de "IP" a cada tentativa.
 *
 * <p>Não é bean, pelo mesmo motivo do {@link JwtAutenticacaoFilter}: o {@code SecurityConfig} o
 * instancia e o põe na frente da cadeia.
 */
public class LimiteTaxaAutenticacaoFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(LimiteTaxaAutenticacaoFilter.class);

  private static final Duration MINUTO = Duration.ofMinutes(1);

  private final Map<String, LimitadorTaxa> limitePorRota;
  private final SecurityErrorResponder errorResponder;

  public LimiteTaxaAutenticacaoFilter(
      ForcaBrutaProperties propriedades, SecurityErrorResponder errorResponder) {
    this.errorResponder = errorResponder;
    int maxIps = propriedades.maxIps();
    LimitadorTaxa senha = new LimitadorTaxa(propriedades.senhaPorMinuto(), MINUTO, maxIps);
    // Um limitador por rota: cadastrar nao gasta as tentativas de login, e vice-versa.
    this.limitePorRota = Map.of(
        RotasAutenticacao.LOGIN,
        new LimitadorTaxa(propriedades.loginPorMinuto(), MINUTO, maxIps),
        RotasAutenticacao.CADASTRO,
        new LimitadorTaxa(propriedades.cadastroPorMinuto(), MINUTO, maxIps),
        RotasAutenticacao.REFRESH,
        new LimitadorTaxa(propriedades.refreshPorMinuto(), MINUTO, maxIps),
        // Um balde so para os dois passos da recuperacao: alternar entre eles nao dobra a cota.
        RotasAutenticacao.SENHA_ESQUECI, senha,
        RotasAutenticacao.SENHA_REDEFINIR, senha);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    LimitadorTaxa limitador = HttpMethod.POST.matches(request.getMethod())
        ? limitePorRota.get(RotasAutenticacao.caminho(request))
        : null;
    if (limitador != null) {
      long esperar = limitador.tentar(chaveCliente(request.getRemoteAddr()));
      if (esperar > 0) {
        log.warn("Limite de tentativas excedido: rota={}, ip={}", request.getRequestURI(),
            MascaraDadosPessoais.truncarIp(request.getRemoteAddr()));
        errorResponder.muitasTentativas(request, response, esperar);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Chave do cliente no limitador: o IPv4 inteiro; do IPv6, só o prefixo /64.
   *
   * <p>Um assinante IPv6 recebe um /64 inteiro (2^64 endereços): limitar por endereço completo
   * deixaria o atacante trocar de "IP" a cada tentativa e ainda varrer o teto de memória do
   * {@link LimitadorTaxa}. O /64 é a menor unidade que um provedor entrega a um cliente.
   *
   * @param ip {@code remoteAddr}
   * @return chave para o limitador
   */
  static String chaveCliente(String ip) {
    if (ip == null || ip.indexOf(':') < 0) {
      return ip;
    }
    try {
      byte[] bytes = InetAddress.getByName(ip).getAddress();
      if (bytes.length != 16) {
        return ip;
      }
      return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
    } catch (UnknownHostException e) {
      // remoteAddr e sempre literal; sem resolver DNS, um literal invalido nao chega aqui.
      return ip;
    }
  }
}

package br.com.agrocontabil.config;

import br.com.agrocontabil.security.JwtAutenticacaoFilter;
import br.com.agrocontabil.security.LimiteTaxaAutenticacaoFilter;
import br.com.agrocontabil.security.LimiteTaxaGeralFilter;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.RotasAutenticacao;
import br.com.agrocontabil.security.SecurityErrorResponder;
import br.com.agrocontabil.service.AutenticacaoTokenService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configuracao de seguranca da aplicacao.
 *
 * <p><strong>Fim do {@code permitAll()} (issue #93).</strong> Ate aqui todos os endpoints estavam
 * liberados na cadeia de filtros e a unica barreira real era o 401/404 que cada service produzia.
 * Agora a cadeia exige identidade e, onde faz sentido, papel — com o {@link SecurityErrorResponder}
 * devolvendo o mesmo envelope {@code ApiError} que o {@code GlobalExceptionHandler} usa, porque
 * negativa de filtro acontece antes do controller e nao passaria pelo
 * {@code @RestControllerAdvice}.
 *
 * <p>Regras:
 *
 * <table border="1">
 *   <caption>Endpoint x papel</caption>
 *   <tr><th>Rota</th><th>Quem acessa</th></tr>
 *   <tr><td>{@code /api/v1/health}, Swagger, Actuator</td><td>publico</td></tr>
 *   <tr><td>{@code POST /api/auth/cadastro}, {@code /login}, {@code /refresh}, {@code /logout}
 *   </td><td>publico</td></tr>
 *   <tr><td>{@code PATCH /api/contador/guias/&#42;/pagar}</td><td>somente PRODUTOR</td></tr>
 *   <tr><td>demais {@code /api/**}</td><td>PRODUTOR ou CONTADOR</td></tr>
 * </table>
 *
 * <p>Por que as guias e o calendario nao sao exclusivos do contador: o produtor consulta as
 * proprias guias e o proprio calendario nas telas dele. O recorte fino — <em>qual</em> produtor
 * cada identidade alcanca — e do {@code ProdutorEmContextoResolver}, que responde 404 (nunca 403)
 * para nao revelar a existencia do produtor alheio. A cadeia so responde a pergunta grossa: "tem
 * identidade?" e "o papel faz este tipo de operacao?".
 *
 * <p>Mantemos CSRF desabilitado (API stateless), sessao stateless e CORS controlado por
 * configuracao.
 *
 * <p>Em {@code dev} e {@code homolog}, o {@link MockIdentidadeFilter} resolve a identidade a partir
 * do header {@code X-Usuario-Id} (UUID de produtor ou de contador) e preenche a authority do papel;
 * sem o header, cai no produtor semente. O header <strong>declara</strong> a identidade, nao a
 * prova — nao e autenticacao, e sim o mock multi-usuario que permite exercitar anti-IDOR e escopo
 * por papel ate o login real chegar.
 *
 * <p>Fora desses profiles o filtro nao existe (dai o {@code required = false} na injecao): nenhuma
 * requisicao carrega principal e a cadeia responde <strong>401</strong> com o envelope padrao.
 *
 * <p>So existe em aplicacao web: o {@code HttpSecurity} nao e criado sem servlet, e o comando de
 * ingestao da Ceres (profile {@code ingestao}, issue #156) sobe sem servidor web.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  /**
   * Rotas publicas: health, documentacao e os dois endpoints de Actuator realmente expostos.
   *
   * <p>Deliberadamente <strong>nao</strong> e {@code /actuator/**}: se amanha alguem ampliar
   * {@code management.endpoints.web.exposure.include} (env, beans, heapdump, threaddump), o novo
   * endpoint nao nasce publico por tabela — precisa passar por aqui e ser decidido.
   *
   * <p>{@code /error} e o forward interno do container; barra-lo transformaria erro em erro.
   */
  private static final String[] ROTAS_PUBLICAS = {
      "/api/v1/health/**",
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/actuator/health",
      "/actuator/health/**",
      "/actuator/info",
      "/error"
  };

  private final CorsConfigurationSource corsConfigurationSource;
  private final SecurityErrorResponder errorResponder;
  private final AutenticacaoTokenService autenticacaoTokenService;
  private final ForcaBrutaProperties forcaBruta;
  private final LimiteTaxaProperties limiteTaxa;
  private final MockIdentidadeFilter mockIdentidadeFilter;

  public SecurityConfig(
      CorsConfigurationSource corsConfigurationSource,
      SecurityErrorResponder errorResponder,
      AutenticacaoTokenService autenticacaoTokenService,
      ForcaBrutaProperties forcaBruta,
      LimiteTaxaProperties limiteTaxa,
      @Autowired(required = false) MockIdentidadeFilter mockIdentidadeFilter) {
    this.corsConfigurationSource = corsConfigurationSource;
    this.errorResponder = errorResponder;
    this.autenticacaoTokenService = autenticacaoTokenService;
    this.forcaBruta = forcaBruta;
    this.limiteTaxa = limiteTaxa;
    this.mockIdentidadeFilter = mockIdentidadeFilter;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            excecoes ->
                excecoes
                    .authenticationEntryPoint(errorResponder)
                    .accessDeniedHandler(errorResponder))
        .authorizeHttpRequests(
            auth ->
                // Dispatch ASYNC (fim de uma resposta em streaming da Ceres, issue #157): a
                // requisicao ja foi autorizada no dispatch REQUEST. Sem isto, o dispatch que fecha
                // o SseEmitter rodaria sem SecurityContext e seria negado com a resposta ja enviada.
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                    .requestMatchers(ROTAS_PUBLICAS).permitAll()
                    // Cadastro, login, refresh e logout (#205-#207): quem chega aqui ainda nao tem
                    // identidade (ou so tem o cookie de refresh). So o
                    // POST — um GET ou DELETE no mesmo caminho cai na regra de /api/** abaixo.
                    .requestMatchers(HttpMethod.POST,
                        RotasAutenticacao.PUBLICAS_POST.toArray(String[]::new)).permitAll()
                    // Quitar a guia e ato do produtor: e ele quem paga o que o escritorio anexou.
                    .requestMatchers(HttpMethod.PATCH, "/api/contador/guias/*/pagar")
                    .hasRole(PapelUsuario.PRODUTOR.name())
                    .requestMatchers("/api/**")
                    .hasAnyRole(PapelUsuario.PRODUTOR.name(), PapelUsuario.CONTADOR.name())
                    .anyRequest()
                    .authenticated());

    // JWT primeiro (#206): Bearer valido autentica; Bearer invalido morre aqui com 401, antes que
    // o mock de dev/homolog tenha chance de cair no produtor semente.
    JwtAutenticacaoFilter jwtFilter =
        new JwtAutenticacaoFilter(autenticacaoTokenService, errorResponder);
    http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    // Limite por IP (#208) antes de tudo que toca banco ou BCrypt; depois do CORS, para o 429
    // levar os headers de CORS e o Angular conseguir ler o envelope.
    http.addFilterBefore(
        new LimiteTaxaAutenticacaoFilter(forcaBruta, errorResponder), JwtAutenticacaoFilter.class);
    if (mockIdentidadeFilter != null) {
      http.addFilterAfter(mockIdentidadeFilter, JwtAutenticacaoFilter.class);
    }
    // Limite geral (#150) depois de toda identificacao (JWT, mock, anonimo): a chave e a
    // identidade, nao o IP, quando ha uma. Antes da autorizacao, para nao gastar o resto da cadeia.
    http.addFilterAfter(
        new LimiteTaxaGeralFilter(limiteTaxa, errorResponder), AnonymousAuthenticationFilter.class);

    http.headers(headers -> headers
        // HSTS: o navegador so fala HTTPS com a API por 1 ano. So sai em requisicao segura (atras
        // do Nginx, o X-Forwarded-Proto do forward-headers-strategy=native faz isSecure valer).
        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
        .frameOptions(frame -> frame.deny())
        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=()"))
        // CSP so na API: respostas JSON/PDF nao carregam nada. O Swagger UI (fora de /api) precisa
        // de script e estilo, e uma CSP global o quebraria.
        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
            PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
            new StaticHeadersWriter("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))));

    return http.build();
  }
}

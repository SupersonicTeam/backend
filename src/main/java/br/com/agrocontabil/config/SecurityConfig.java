package br.com.agrocontabil.config;

import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.SecurityErrorResponder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
 */
@Configuration
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
  private final MockIdentidadeFilter mockIdentidadeFilter;

  public SecurityConfig(
      CorsConfigurationSource corsConfigurationSource,
      SecurityErrorResponder errorResponder,
      @Autowired(required = false) MockIdentidadeFilter mockIdentidadeFilter) {
    this.corsConfigurationSource = corsConfigurationSource;
    this.errorResponder = errorResponder;
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
                auth.requestMatchers(ROTAS_PUBLICAS).permitAll()
                    // Quitar a guia e ato do produtor: e ele quem paga o que o escritorio anexou.
                    .requestMatchers(HttpMethod.PATCH, "/api/contador/guias/*/pagar")
                    .hasRole(PapelUsuario.PRODUTOR.name())
                    .requestMatchers("/api/**")
                    .hasAnyRole(PapelUsuario.PRODUTOR.name(), PapelUsuario.CONTADOR.name())
                    .anyRequest()
                    .authenticated());

    if (mockIdentidadeFilter != null) {
      http.addFilterBefore(mockIdentidadeFilter, UsernamePasswordAuthenticationFilter.class);
    }

    return http.build();
  }
}

package br.com.agrocontabil.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configuracao de seguranca da aplicacao.
 *
 * <p><strong>Estado atual (auth em stand-by ate a Sprint 8):</strong> todos os endpoints estao
 * liberados via {@code permitAll()}. A estrutura ja esta pronta para receber autenticacao JWT sem
 * refatorar controllers — quando a Sprint de autenticacao chegar, basta trocar o {@code
 * permitAll()} por regras de autorizacao e plugar o filtro JWT.
 *
 * <p>Mesmo liberado, mantemos: CSRF desabilitado (API stateless), sessao stateless e CORS
 * controlado por configuracao.
 *
 * <p>Em perfil {@code dev}, o {@link MockIdentidadeFilter} e registrado automaticamente e resolve, a
 * cada requisicao, qual identidade esta "autenticada" a partir do header {@code X-Usuario-Id} (UUID
 * de produtor ou de contador); sem o header, cai no produtor semente. O header
 * <strong>declara</strong> a identidade, nao a prova — nao e autenticacao, e sim o mock
 * multi-usuario que permite exercitar o anti-IDOR e o escopo por papel ate o login real chegar.
 *
 * <p>Desde a issue #93 o token carrega a authority do papel ({@code ROLE_PRODUTOR} /
 * {@code ROLE_CONTADOR}) — e o que torna possivel escrever regra por papel aqui, no lugar do
 * {@code permitAll()}.
 *
 * <p>Fora desse perfil o filtro nao existe (dai o {@code required = false} na injecao): nenhuma
 * requisicao carrega principal e o {@code AuthenticatedProdutorProvider} responde <strong>401</strong>
 * nos endpoints que dependem de identidade.
 */
@Configuration
public class SecurityConfig {

  private final CorsConfigurationSource corsConfigurationSource;
  private final MockIdentidadeFilter mockIdentidadeFilter;

  public SecurityConfig(
      CorsConfigurationSource corsConfigurationSource,
      @Autowired(required = false) MockIdentidadeFilter mockIdentidadeFilter) {
    this.corsConfigurationSource = corsConfigurationSource;
    this.mockIdentidadeFilter = mockIdentidadeFilter;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // TODO[Sprint 8 - Auth]: substituir por regras reais (.authenticated())
                    // e adicionar o filtro JWT antes do UsernamePasswordAuthenticationFilter.
                    .anyRequest().permitAll());

    if (mockIdentidadeFilter != null) {
      http.addFilterBefore(mockIdentidadeFilter, UsernamePasswordAuthenticationFilter.class);
    }

    return http.build();
  }
}

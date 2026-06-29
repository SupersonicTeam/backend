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
 * <p>Em perfil {@code dev}, o {@link MockProducerFilter} e registrado automaticamente para simular
 * um usuario autenticado sem necessidade de credenciais reais.
 */
@Configuration
public class SecurityConfig {

  private final CorsConfigurationSource corsConfigurationSource;
  private final MockProducerFilter mockProdutorFilter;

  public SecurityConfig(
      CorsConfigurationSource corsConfigurationSource,
      @Autowired(required = false) MockProducerFilter mockProdutorFilter) {
    this.corsConfigurationSource = corsConfigurationSource;
    this.mockProdutorFilter = mockProdutorFilter;
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

    if (mockProdutorFilter != null) {
      http.addFilterBefore(mockProdutorFilter, UsernamePasswordAuthenticationFilter.class);
    }

    return http.build();
  }
}

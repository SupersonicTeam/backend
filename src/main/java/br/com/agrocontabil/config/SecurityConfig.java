package br.com.agrocontabil.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Configuracao de seguranca da aplicacao.
 *
 * <p><strong>Estado atual (auth em stand-by ate a Sprint 8):</strong> todos os
 * endpoints estao liberados via {@code permitAll()}. A estrutura ja esta pronta
 * para receber autenticacao JWT sem refatorar controllers — quando a Sprint de
 * autenticacao chegar, basta trocar o {@code permitAll()} por regras de
 * autorizacao e plugar o filtro JWT.
 *
 * <p>Mesmo liberado, mantemos: CSRF desabilitado (API stateless), sessao
 * stateless e CORS controlado por configuracao.
 */
@Configuration
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // TODO[Sprint 8 - Auth]: substituir por regras reais (.authenticated())
                        // e adicionar o filtro JWT antes do UsernamePasswordAuthenticationFilter.
                        .anyRequest().permitAll());

        return http.build();
    }
}

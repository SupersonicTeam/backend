package br.com.agrocontabil.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuracao de CORS controlada por variavel de ambiente.
 *
 * <p>As origens permitidas vem da propriedade {@code app.cors.allowed-origins}
 * (default em {@code application*.yml}), tipicamente alimentada por env var em
 * producao. Evita liberar {@code *} indiscriminadamente.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value("${app.cors.allowed-origins:http://localhost:4200}") String allowedOrigins) {
        // Split explicito da lista separada por virgula (ex: "http://a, http://b"),
        // com trim e descarte de entradas vazias. Auto-documentado e independente
        // do conversion service.
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Content-Disposition: nome do arquivo nos downloads de XML/PDF.
        // X-Total-Count: total da listagem paginada de guias (issue #103), cujo corpo segue array.
        config.setExposedHeaders(List.of("Content-Disposition", "X-Total-Count"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

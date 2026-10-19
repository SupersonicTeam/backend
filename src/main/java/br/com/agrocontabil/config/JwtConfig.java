package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra {@link JwtProperties} (issue #206), no padrão do {@code AssistenteConfig}. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
}

package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link JwtProperties} (issue #206) e {@link RefreshProperties} (issue #207), no padrão do
 * {@code AssistenteConfig}.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, RefreshProperties.class})
public class JwtConfig {
}

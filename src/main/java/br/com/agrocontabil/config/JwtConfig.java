package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link JwtProperties} (issue #206), {@link RefreshProperties} (issue #207) e
 * {@link ForcaBrutaProperties} (issue #208) e {@link RedefinicaoSenhaProperties} (issue #209), no
 * padrão do
 * {@code AssistenteConfig}.
 */
@Configuration
@EnableConfigurationProperties(
    {JwtProperties.class, RefreshProperties.class, ForcaBrutaProperties.class,
        RedefinicaoSenhaProperties.class, LimiteTaxaProperties.class, LgpdProperties.class,
        AuditoriaProperties.class})
public class JwtConfig {
}

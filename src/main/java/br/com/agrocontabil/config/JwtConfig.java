package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link JwtProperties} (issue #206), {@link RefreshProperties} (issue #207) e
 * {@link ForcaBrutaProperties} (issue #208), {@link RedefinicaoSenhaProperties} (issue #209) e,
 * desde a #519, {@link CadastroProperties} e {@link TermosProperties}, no
 * padrão do
 * {@code AssistenteConfig}.
 */
@Configuration
@EnableConfigurationProperties(
    {JwtProperties.class, RefreshProperties.class, ForcaBrutaProperties.class,
        RedefinicaoSenhaProperties.class, LimiteTaxaProperties.class, LgpdProperties.class,
        AuditoriaProperties.class, CadastroProperties.class, TermosProperties.class})
public class JwtConfig {
}

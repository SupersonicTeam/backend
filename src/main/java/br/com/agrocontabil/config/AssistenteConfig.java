package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link AssistenteProperties} no contexto Spring (issue #141).
 *
 * <p>Usando {@link EnableConfigurationProperties} em vez de {@code @ConfigurationPropertiesScan}
 * para manter o registro explícito e rastreável — padrão adotado neste projeto.
 */
@Configuration
@EnableConfigurationProperties(AssistenteProperties.class)
public class AssistenteConfig {}

package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita o binding de {@link DigitalCertificateProperties}.
 *
 * <p>Usa {@link EnableConfigurationProperties} (mesmo padrão de {@code TaxConfig}) em vez
 * de {@code @ConfigurationPropertiesScan} para manter o registro explícito.
 */
@Configuration
@EnableConfigurationProperties(DigitalCertificateProperties.class)
public class DigitalCertificateConfig {}

package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra as propriedades do endereço próprio do escritório (issue #464). Fica fora do
 * {@link CarteiraConfig}, que cuida do e-mail dos convites.
 */
@Configuration
@EnableConfigurationProperties(EnderecoEscritorioProperties.class)
public class EnderecoEscritorioConfig {
}

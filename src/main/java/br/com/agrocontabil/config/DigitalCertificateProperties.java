package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do certificado digital usado para sign a NF-e.
 *
 * <p>Valores vêm de variável de ambiente — nenhum segredo no repositório. Registrada via
 * {@link org.springframework.boot.context.properties.EnableConfigurationProperties} em
 * {@link DigitalCertificateConfig}.
 *
 * @param path     caminho do fileName PKCS#12 (.p12); aceita prefixos do Spring
 *                 ({@code classpath:}, {@code file:})
 * @param password senha do KeyStore
 * @param alias    alias da entrada do certificado (opcional; se vazio, usa o primeiro alias)
 */
@ConfigurationProperties(prefix = "app.nfe.certificado")
public record DigitalCertificateProperties(String path, String password, String alias) {}

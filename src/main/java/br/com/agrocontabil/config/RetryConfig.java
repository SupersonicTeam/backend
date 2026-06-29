package br.com.agrocontabil.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Habilita o suporte a {@code @Retryable} e {@code @Recover} no contexto Spring.
 *
 * <p>Necessário para que o {@link br.com.agrocontabil.service.SefazService} aplique
 * retry automático em failures de comunicação com a SEFAZ
 * ({@link br.com.agrocontabil.exception.SefazCommunicationException}).
 *
 * <p>Requer as dependências {@code spring-retry} e {@code spring-boot-starter-aop},
 * ambas já declaradas no {@code pom.xml} do projeto.
 */
@Configuration
@EnableRetry
public class RetryConfig {}

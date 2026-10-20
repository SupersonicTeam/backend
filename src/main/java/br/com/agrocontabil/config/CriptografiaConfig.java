package br.com.agrocontabil.config;

import br.com.agrocontabil.security.CifraDeterministica;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link CriptografiaProperties} e a {@link CifraDeterministica} (issue #147) para quem
 * precisa cifrar fora do JPA — o {@code CifragemLegadoRunner}. O conversor das entidades monta a
 * própria instância da mesma configuração (mesma chave, mesma saída).
 */
@Configuration
@EnableConfigurationProperties(CriptografiaProperties.class)
public class CriptografiaConfig {

  /**
   * A cifra dos dados pessoais.
   *
   * @param propriedades configuração da chave
   * @return a cifra; chave inválida impede a subida
   */
  @Bean
  public CifraDeterministica cifraDeterministica(CriptografiaProperties propriedades) {
    return CifraDeterministica.de(propriedades);
  }
}

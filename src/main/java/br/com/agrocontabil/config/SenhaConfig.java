package br.com.agrocontabil.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hash de senha (issue #205).
 *
 * <p>{@link DelegatingPasswordEncoder} grava o algoritmo no próprio hash ({@code {bcrypt}...}).
 * Trocar o padrão (Argon2, custo maior) passa a valer para as senhas novas sem invalidar as
 * antigas, que continuam conferindo pelo prefixo delas.
 *
 * <p>Fora do {@code SecurityConfig} de propósito: aquele só existe com servidor web, e o hash de
 * senha é regra do serviço de cadastro, que também sobe nos comandos sem web.
 */
@Configuration
public class SenhaConfig {

  /** Prefixo do algoritmo padrão no hash gravado. */
  static final String ALGORITMO_PADRAO = "bcrypt";

  /**
   * Codificador de senha.
   *
   * <p>Custo 12 por padrão (≈ 250 ms por hash numa CPU de servidor): caro para quem testa milhões
   * de senhas num hash vazado, imperceptível num login. Os testes baixam para 4 por propriedade.
   *
   * @param custo fator de custo do BCrypt (log2 das rodadas), de 4 a 31
   * @return codificador delegante com BCrypt como padrão
   */
  @Bean
  public PasswordEncoder passwordEncoder(
      @Value("${app.seguranca.senha.bcrypt-custo:12}") int custo) {
    return new DelegatingPasswordEncoder(
        ALGORITMO_PADRAO, Map.of(ALGORITMO_PADRAO, new BCryptPasswordEncoder(custo)));
  }
}

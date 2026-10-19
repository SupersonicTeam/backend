package br.com.agrocontabil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Ponto de entrada do backend AgroContabil.
 *
 * <p>Sistema de auxilio ao produtor rural e ao escritorio de contabilidade
 * frente a reforma tributaria do agronegocio (IBS/CBS - EC 132/2023 + LC 214/2025).
 */
// Sem o UserDetailsService padrao (#206): a autenticacao e por JWT, e o Boot criaria um usuario
// "user" com senha gerada e logada na subida — uma credencial a mais, inutil e em log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AgroContabilApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgroContabilApplication.class, args);
    }
}

package br.com.agrocontabil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada do backend AgroContabil.
 *
 * <p>Sistema de auxilio ao producer rural e ao escritorio de contabilidade
 * frente a reforma tributaria do agronegocio (IBS/CBS - EC 132/2023 + LC 214/2025).
 */
@SpringBootApplication
public class AgroContabilApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgroContabilApplication.class, args);
    }
}

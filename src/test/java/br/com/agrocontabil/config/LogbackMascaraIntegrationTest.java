package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * O {@code logback-spring.xml} de verdade, carregado pelo Spring Boot (issue #148): mensagem e
 * stack trace saem mascarados no console. Pega uma regra que o {@code defaults.xml} do Boot
 * sobrescreva, ou a propriedade do stack trace que deixe de ser lida.
 */
@SpringBootTest(classes = LogbackMascaraIntegrationTest.Vazio.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("logback-spring.xml: mensagem e stack trace mascarados (#148)")
class LogbackMascaraIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(LogbackMascaraIntegrationTest.class);

  @Test
  @DisplayName("CPF e e-mail nao chegam ao console, nem na mensagem nem na excecao")
  void mascaraNoConsole(CapturedOutput saida) {
    log.error("falha no cadastro de {}", "52998224725",
        new IllegalStateException("Key (email)=(joao@fazenda.com.br) already exists"));

    assertThat(saida.getAll())
        .contains("falha no cadastro de [CPF]")
        .contains("Key (email)=([EMAIL])")
        .doesNotContain("52998224725")
        .doesNotContain("joao@fazenda.com.br");
  }

  /**
   * Contexto vazio: só o sistema de logging do Boot importa aqui. Sem
   * {@code @SpringBootConfiguration}
   * de propósito: a busca de configuração dos outros {@code @SpringBootTest} deste pacote a acharia
   * antes da aplicação.
   */
  static class Vazio {
  }
}

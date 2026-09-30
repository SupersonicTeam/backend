package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/**
 * A aplicação sobe sem servidor web — o modo do comando de ingestão da Ceres (profile
 * {@code ingestao}, {@code web-application-type: none}, issue #156).
 *
 * <p>O {@code SecurityConfig} pedia o {@code HttpSecurity}, que só existe com servlet, e o comando
 * de ingestão falhava na subida antes de ler qualquer arquivo. O profile {@code ingestao} em si
 * não é ativado aqui: o runner encerra a JVM ao terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ContextoSemWebTest {

  @Autowired private ApplicationContext contexto;

  @Test
  @DisplayName("Contexto sem web sobe, sem a cadeia de seguranca HTTP")
  void sobeSemWeb() {
    assertThat(contexto).isNotInstanceOf(WebApplicationContext.class);
    assertThat(contexto.getBeanNamesForType(SecurityConfig.class)).isEmpty();
  }
}

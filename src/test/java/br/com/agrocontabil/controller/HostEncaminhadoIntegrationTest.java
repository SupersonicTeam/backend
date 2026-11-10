package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * O host do login atrás do proxy (issue #464), com o Tomcat de verdade e
 * {@code server.forward-headers-strategy=native}, como na VPS: o {@code X-Forwarded-Host} só vale
 * quando quem conecta é um proxy interno. O MockMvc não tem o {@code RemoteIpValve}; por isso
 * servidor real e HTTP de verdade.
 */
@DisplayName("Login atras do proxy: X-Forwarded-Host so de proxy interno (#464)")
class HostEncaminhadoIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "admin@rural-oeste.com.br";
  private static final String CORPO =
      "{\"email\":\"%s\",\"senha\":\"%s\",\"escritorio\":\"ruraloeste\"}".formatted(EMAIL, SENHA);

  /** Base comum: servidor real, um escritório com endereço e o administrador dele. */
  abstract static class Base {

    @LocalServerPort int porta;
    @Autowired CadastroService cadastroService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestDatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
      cleaner.limparTudo();
      CadastroResponse admin = cadastroService.cadastrar(new CadastroRequest(
          PapelUsuario.CONTADOR, "Rural Oeste", "52998224725", EMAIL, SENHA));
      jdbcTemplate.update("UPDATE escritorio SET slug = 'ruraloeste', cnpj = '11222333000181'"
          + " WHERE id = (SELECT escritorio_id FROM contadores WHERE id = ?)",
          admin.usuarioId());
    }

    int loginComHostEncaminhado(String host) throws Exception {
      HttpRequest requisicao = HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + porta + "/api/auth/login"))
          .header("Content-Type", "application/json")
          .header("X-Forwarded-Host", host)
          .header("X-Forwarded-Proto", "https")
          .POST(HttpRequest.BodyPublishers.ofString(CORPO))
          .build();
      try (HttpClient cliente = HttpClient.newHttpClient()) {
        return cliente.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode();
      }
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "server.forward-headers-strategy=native")
  @Import(TestDatabaseCleaner.class)
  @ActiveProfiles("test")
  @Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
      executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
  @DisplayName("de proxy interno (o loopback, como o Caddy na rede do compose)")
  class DeProxyInterno extends Base {

    @Test
    @DisplayName("X-Forwarded-Host vale: o membro entra pelo endereco do escritorio")
    void hostEncaminhadoVale() throws Exception {
      assertThat(loginComHostEncaminhado("ruraloeste.agroconia.com.br")).isEqualTo(200);
      assertThat(loginComHostEncaminhado("outro.agroconia.com.br")).isEqualTo(401);
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {"server.forward-headers-strategy=native",
          "server.tomcat.remoteip.internal-proxies=10[.]255[.]255[.]254"})
  @Import(TestDatabaseCleaner.class)
  @ActiveProfiles("test")
  @Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
      executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
  @DisplayName("de IP externo (o loopback fora dos proxies internos)")
  class DeIpExterno extends Base {

    @Test
    @DisplayName("X-Forwarded-Host ignorado: o host continua o do socket e o escritorio do corpo nao basta")
    void hostEncaminhadoIgnorado() throws Exception {
      assertThat(loginComHostEncaminhado("ruraloeste.agroconia.com.br")).isEqualTo(401);
    }
  }
}

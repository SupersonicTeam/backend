package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.sefaz.MockSefazTransport;
import br.com.agrocontabil.sefaz.SefazTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A aplicação de verdade no profile {@code homolog} (issue #221): só JWT autentica.
 *
 * <p>Até a #221 o {@code MockIdentidadeFilter} subia em homolog ao lado do login real, e bastava
 * omitir o {@code Authorization} e mandar o UUID da vítima em {@code X-Usuario-Id} para agir como
 * ela — e o UUID não é segredo (header {@code Location}, logs, bundle do frontend).
 *
 * <p>O teste sobe o {@code application-homolog.yml} real, trocando só a infraestrutura: o H2 no
 * lugar do PostgreSQL ({@code IGNORE_UNKNOWN_SETTINGS} engole o {@code statement_timeout} do
 * profile), com o schema de teste criado antes do seed, segredos fortes de teste e os jobs
 * desligados. O {@code DataLoader} segue semeando os
 * dois produtores em homolog — é o UUID do semente que o teste tenta usar.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:homolog221;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
        + "NON_KEYWORDS=VALUE;IGNORE_UNKNOWN_SETTINGS=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-nota-fiscal-test.sql,"
        + "classpath:schema-shedlock-test.sql",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.liquibase.enabled=false",
    "app.cors.allowed-origins=http://localhost:4200",
    "app.seguranca.jwt.segredo=Kq7vX2pLm9TzR4wY8nB3cF6hJ1sD5gA0eUiOZxQ",
    "app.seguranca.cripto.chave=q1Fv2hX0b3l8mZ5nR6tY7wP9sD4gK2jA1cE0uI3oL5M=",
    "app.seguranca.cripto.migrar-legado=false",
    "app.notas.busca.preencher-legado=false",
    "app.nfe.sefaz.sync.enabled=false",
    "app.apuracao.fechamento.enabled=false",
    "app.guia-fiscal.sync.enabled=false",
    "app.auditoria.retencao.enabled=false",
    "app.alertas.enabled=false",
    "app.notificacao.aviso.enabled=false",
    "app.notificacao.retencao.enabled=false",
    "app.pendencias.antivirus.reverificacao.enabled=false",
    "app.seguranca.refresh.limpeza.enabled=false",
    // Como o deploy/compose.yaml com o envio desligado (#460).
    "spring.mail.host=",
    "spring.mail.username=",
    "spring.mail.password="})
@AutoConfigureMockMvc
@ActiveProfiles("homolog")
@DisplayName("homolog autentica so por JWT: sem mock de identidade (#221)")
class HomologSemMockIdentidadeIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext contexto;
  @Autowired private ProdutorRepository produtorRepository;

  @Test
  @DisplayName("homolog (a VPS da demonstracao) segue com o mock da SEFAZ sem configuracao (#263)")
  void mockDaSefazSegueEmHomolog() {
    assertThat(contexto.getBean(SefazTransport.class)).isInstanceOf(MockSefazTransport.class);
  }

  @Test
  @DisplayName("o MockIdentidadeFilter nao existe no contexto de homolog")
  void semFiltroDeMock() {
    assertThat(contexto.getBeanNamesForType(MockIdentidadeFilter.class)).isEmpty();
  }

  @Test
  @DisplayName("GET /api/notas sem Authorization: 401 NAO_AUTENTICADO, nunca o produtor semente")
  void semAuthorizationResponde401() throws Exception {
    mockMvc.perform(get("/api/notas"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"));
  }

  @Test
  @DisplayName("X-Usuario-Id de um produtor existente nao muda a identidade: 401")
  void headerDoMockNaoAutentica() throws Exception {
    Produtor semente = produtorRepository.findByCpfCnpj(DataLoader.CPF_PRODUTOR_SEMENTE)
        .orElseThrow(() -> new AssertionError("o DataLoader de homolog deveria semear o produtor"));

    mockMvc.perform(get("/api/auth/me")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, semente.getId().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("NAO_AUTENTICADO"));
    mockMvc.perform(get("/api/notas")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, semente.getId().toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("SPRING_MAIL_HOST vazio, como o compose entrega: cliente SMTP criado e health 200 (#460)")
  void hostSmtpVazioNaoDerrubaHealth() throws Exception {
    // O compose passa SPRING_MAIL_HOST="" com o envio desligado. O Boot cria o cliente SMTP mesmo
    // assim, e o MailHealthIndicator testaria localhost:25 e responderia DOWN (503): o healthcheck
    // do compose falharia e o deploy.sh reverteria o deploy.
    assertThat(contexto.getBeanNamesForType(JavaMailSender.class)).isNotEmpty();
    assertThat(contexto.getBean(HealthContributorRegistry.class).getContributor("mail")).isNull();
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}

package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.service.VerificacaoCnpjService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.web.context.WebApplicationContext;

/**
 * O comando da verificação do CNPJ (#520) roda sem servidor web
 * ({@code application-verificacao-cnpj.yml}, como a ingestão). Este teste sobe o contexto assim, sem
 * o comando (que encerraria a JVM), e faz a marca de ponta a ponta: um bean novo que dependa de web
 * quebraria o comando só na VPS, na hora de verificar um escritório.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Verificacao do CNPJ sem servidor web, como o comando da VPS (#520)")
class VerificacaoCnpjSemWebIntegrationTest {

  private static final String CNPJ = "11444777000161";

  @Autowired private ApplicationContext contexto;
  @Autowired private VerificacaoCnpjService verificacao;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestDatabaseCleaner cleaner;

  @AfterEach
  void limpar() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("sobe sem web, verifica, serve o endereco e audita sem usuario e sem o CNPJ")
  void verificaSemWeb() {
    assertThat(contexto).isNotInstanceOf(WebApplicationContext.class);
    Escritorio escritorio = escritorioRepository.save(Escritorio.builder()
        .nome("Contabilidade Campo Verde").tipo(TipoEscritorio.EMPRESARIAL).cnpj(CNPJ)
        .slug("semweb520").build());
    assertThat(escritorioRepository.findAtivoPorSlug("semweb520")).isEmpty();

    assertThat(verificacao.verificar(escritorio.getId(), CNPJ, MetodoVerificacaoCnpj.MANUAL)
        .mudou()).isTrue();

    assertThat(escritorioRepository.findAtivoPorSlug("semweb520")).isPresent();
    assertThat(jdbc.queryForList("SELECT estado_antes || '>' || estado_depois FROM"
            + " registro_auditoria WHERE acao = 'ESCRITORIO_CNPJ_VERIFICADO' AND recurso_id = ?"
            + " AND sucesso AND usuario_id IS NULL", String.class, escritorio.getId()))
        .containsExactly("NAO_VERIFICADO>MANUAL");
  }
}

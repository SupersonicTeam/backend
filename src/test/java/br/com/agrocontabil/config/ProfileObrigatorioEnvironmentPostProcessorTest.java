package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/**
 * A aplicação não sobe sem profile de ambiente explícito (issue #219).
 *
 * <p>O {@code application.yml} tinha {@code spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}}:
 * um deploy sem a variável subia em {@code dev} e ligava em silêncio o segredo JWT efêmero, o mock
 * de identidade por header, a chave de cifra de desenvolvimento, o link de senha no log e o seed.
 *
 * <p>Os testes de subida rodam um {@link SpringApplication} de verdade, com o
 * {@code application.yml} do classpath e o processador registrado em {@code spring.factories} —
 * então quebram tanto se o default {@code dev} voltar ao yml quanto se o registro sumir. Só o
 * listener dos {@code EnvironmentPostProcessor} entra: o de logging reconfiguraria o logback da
 * suíte inteira, e a falha desta subida acontece antes de qualquer bean.
 */
@DisplayName("Profile de ambiente obrigatorio na subida")
class ProfileObrigatorioEnvironmentPostProcessorTest {

  private final ProfileObrigatorioEnvironmentPostProcessor processador =
      new ProfileObrigatorioEnvironmentPostProcessor();

  @Test
  @DisplayName("Sem SPRING_PROFILES_ACTIVE a aplicacao recusa a subida, com mensagem de como corrigir")
  void recusaSubidaSemProfile() {
    assertThatThrownBy(() -> subir())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_PROFILES_ACTIVE")
        .hasMessageContaining("dev, homolog, prod, test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test"})
  @DisplayName("Com profile de ambiente explicito a aplicacao sobe")
  void sobeComProfileExplicito(String profile) {
    try (ConfigurableApplicationContext contexto = subir("--spring.profiles.active=" + profile)) {
      assertThat(contexto.getEnvironment().getActiveProfiles()).containsExactly(profile);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "homolog", "prod", "test", "dev,ingestao", "dev,avaliacao"})
  @DisplayName("Aceita exatamente um profile de ambiente, com ou sem profiles auxiliares")
  void aceitaUmProfileDeAmbiente(String profiles) {
    assertThatCode(() -> processar(profiles.split(","))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Nenhum profile ativo e recusado")
  void recusaNenhumProfile() {
    assertThatThrownBy(this::processar)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum profile de ambiente ativo");
  }

  @Test
  @DisplayName("spring.profiles.default=dev nao substitui o profile explicito")
  void recusaProfileSoPorDefault() {
    // Seria o atalho de quem quisesse o comportamento antigo de volta: o @Profile("dev") do mock de
    // identidade e do seed casa com o profile default, entao ele precisa continuar recusado.
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setDefaultProfiles("dev");
    assertThatThrownBy(() -> processador.postProcessEnvironment(ambiente, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum profile de ambiente ativo");
  }

  @ParameterizedTest
  @ValueSource(strings = {"default", "ingestao", "avaliacao", "producao"})
  @DisplayName("So profiles auxiliares ou desconhecidos nao bastam")
  void recusaSemProfileDeAmbiente(String profile) {
    assertThatThrownBy(() -> processar(profile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nenhum profile de ambiente ativo");
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod,dev", "dev,homolog", "homolog,prod"})
  @DisplayName("Dois profiles de ambiente juntos sao recusados")
  void recusaDoisProfilesDeAmbiente(String profiles) {
    // prod,dev ligaria o mock de identidade (dev) com os dados de producao.
    assertThatThrownBy(() -> processar(profiles.split(",")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Mais de um profile de ambiente ativo");
  }

  private void processar(String... profiles) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(profiles);
    processador.postProcessEnvironment(ambiente, new SpringApplication());
  }

  /**
   * Sobe uma aplicação vazia com o {@code application.yml} real. O ambiente não tem as fontes de
   * variável de ambiente nem de propriedade de sistema: um {@code SPRING_PROFILES_ACTIVE} na máquina
   * de quem roda a suíte não muda o resultado.
   */
  private static ConfigurableApplicationContext subir(String... argumentos) {
    StandardEnvironment ambiente = new StandardEnvironment();
    ambiente.getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    ambiente.getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    SpringApplication aplicacao = new SpringApplication(AplicacaoVazia.class);
    aplicacao.setWebApplicationType(WebApplicationType.NONE);
    aplicacao.setEnvironment(ambiente);
    aplicacao.setListeners(List.of(new EnvironmentPostProcessorApplicationListener()));
    return aplicacao.run(argumentos);
  }

  @Configuration(proxyBeanMethods = false)
  static class AplicacaoVazia {
  }
}

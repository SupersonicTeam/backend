package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.SefazConfig;
import br.com.agrocontabil.config.SefazProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * O mock da SEFAZ dá toda NF-e como autorizada sem passar pelo fisco: nunca pode subir em produção
 * (issue #263, etapa 1).
 */
@DisplayName("Mock da SEFAZ: lista de profiles permitidos (#263)")
class MockSefazTransportPerfilTest {

  /** O contexto do transporte, como na aplicação: propriedades, SefazConfig e os dois beans. */
  private final ApplicationContextRunner contexto = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
      .withUserConfiguration(SefazConfig.class, MockSefazTransport.class);

  private static MockEnvironment ambiente(String perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis.split(","));
    return ambiente;
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test", "homolog", "dev,ingestao", "dev,avaliacao"})
  @DisplayName("sobe em dev, test e homolog (a VPS da demonstracao), com os auxiliares")
  void permitido(String perfis) {
    SefazProperties propriedades = new SefazProperties();
    propriedades.getMock().setAutorizarTerceiros(true);

    assertThatCode(() -> new MockSefazTransport(propriedades, ambiente(perfis)))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "prod,homolog", "dev,prod", "producao", "ingestao"})
  @DisplayName("recusa prod, prod junto de outro e qualquer profile fora da lista")
  void recusado(String perfis) {
    assertThatThrownBy(() -> new MockSefazTransport(new SefazProperties(), ambiente(perfis)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.nfe.sefaz.mock.enabled")
        .hasMessageContaining("NFE_SEFAZ_MOCK_ENABLED=false");
  }

  @Test
  @DisplayName("sem profile nenhum tambem e recusado")
  void semProfile() {
    assertThatThrownBy(() -> MockSefazTransport.exigirPerfilPermitido(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("contexto em prod sem a propriedade (padrao true) ou com o mock ligado: nao sobe")
  void prodNaoSobeComOMock() {
    contexto.withPropertyValues("spring.profiles.active=prod")
        .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
            .hasMessageContaining("app.nfe.sefaz.mock.enabled"));
    contexto.withPropertyValues("spring.profiles.active=prod", "app.nfe.sefaz.mock.enabled=true")
        .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
            .hasMessageContaining("app.nfe.sefaz.mock.enabled"));
  }

  @Test
  @DisplayName("contexto em prod com o mock desligado: o mock nem existe e nada e recusado")
  void prodSemOMockSobe() {
    contexto.withPropertyValues("spring.profiles.active=prod", "app.nfe.sefaz.mock.enabled=false",
            "app.nfe.sefaz.webservices.homologacao.padrao.autorizacao=https://sefaz/aut",
            "app.nfe.sefaz.webservices.homologacao.padrao.consulta=https://sefaz/con",
            "app.nfe.sefaz.webservices.homologacao.padrao.evento=https://sefaz/evt")
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(MockSefazTransport.class);
        });
  }

  @Test
  @DisplayName("contexto em homolog: o mock sobe com o autorizar-terceiros da demonstracao")
  void homologSobeComOMock() {
    contexto.withPropertyValues("spring.profiles.active=homolog",
            "app.nfe.sefaz.mock.autorizar-terceiros=true")
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).hasSingleBean(MockSefazTransport.class);
        });
  }
}

package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** A demonstração grava contas com senha conhecida: nunca pode subir em produção (#315). */
@DisplayName("Seed da demonstracao: lista de profiles permitidos")
class SeedDemonstracaoPerfilTest {

  @ParameterizedTest
  @ValueSource(strings = {"dev", "homolog", "test", "dev,ingestao"})
  @DisplayName("sobe em dev, homolog e test")
  void permitido(String perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis.split(","));

    assertThatCode(() -> SeedDemonstracao.exigirPerfilPermitido(ambiente))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "prod,homolog", "producao", "avaliacao"})
  @DisplayName("recusa prod, prod junto de outro e qualquer profile fora da lista")
  void recusado(String perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis.split(","));

    assertThatThrownBy(() -> SeedDemonstracao.exigirPerfilPermitido(ambiente))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.demonstracao.habilitada");
  }
}

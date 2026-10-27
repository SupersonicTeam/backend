package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * Trava em quais profiles o mecanismo de identidade existe (issues #91/#92; mock so em dev desde a
 * #221).
 *
 * <p>Sem isto, trocar {@code homolog} por {@code homologacao} no seed — ou esquecer um dos beans
 * num refactor — passa batido: a suíte roda no profile {@code test} e registra o filtro à
 * mão, então nada quebraria no build. A falha só apareceria no deploy de homologação, com a API
 * inteira respondendo 401 (filtro ausente) ou sem produtores para o header referenciar
 * ({@code DataLoader} ausente).
 *
 * <p>O outro lado importa tanto quanto: {@code prod} <strong>não</strong> pode estar na lista.
 * Identidade declarada por header em produção seria bypass de autenticação.
 */
@DisplayName("Profiles do mecanismo de identidade")
class ProfilesDeIdentidadeTest {

  @Test
  @DisplayName("MockIdentidadeFilter existe so em dev: homolog autentica por JWT (#221)")
  void filtroSoExisteEmDev() {
    // Em homolog o mock convivia com o login real: X-Usuario-Id com o UUID da vitima, sem senha,
    // tomava a conta. O HomologSemMockIdentidadeIntegrationTest sobe o profile de verdade.
    assertThat(profilesDe(MockIdentidadeFilter.class)).containsExactly("dev");
  }

  @Test
  @DisplayName("DataLoader semeia em dev e homolog, e em nenhum outro profile")
  void seedSoExisteEmDevEHomolog() {
    assertThat(profilesDe(DataLoader.class)).containsExactlyInAnyOrder("dev", "homolog");
  }

  private static String[] profilesDe(Class<?> tipo) {
    Profile profile = tipo.getAnnotation(Profile.class);
    assertThat(profile).as("%s precisa declarar @Profile", tipo.getSimpleName()).isNotNull();
    return profile.value();
  }
}

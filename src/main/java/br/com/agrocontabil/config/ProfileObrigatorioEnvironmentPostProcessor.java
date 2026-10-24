package br.com.agrocontabil.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Recusa a subida sem exatamente um profile de ambiente explícito (issue #219).
 *
 * <p>O {@code application.yml} tinha {@code spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}},
 * e um deploy que esquecesse a variável subia em {@code dev}, só com um WARN, ligando o segredo JWT
 * efêmero, o {@link MockIdentidadeFilter} (identidade declarada por header), a chave de cifra de
 * desenvolvimento, o link de redefinição de senha no log e o {@link DataLoader}. Toda proteção de
 * dev depende de o profile estar certo; por isso o profile deixa de ter default e a ausência dele
 * vira erro de subida.
 *
 * <p>Profiles auxiliares ({@code ingestao}, {@code avaliacao}) somam-se a um de ambiente, nunca o
 * substituem. Dois de ambiente juntos também são recusados: {@code prod,dev} ligaria o mock de
 * identidade sobre a configuração de produção.
 *
 * <p>É um {@code EnvironmentPostProcessor}, e não um bean, para falhar antes de qualquer bean ser
 * criado — um bean de checagem chegaria tarde, depois de o filtro de identidade e o seed já terem
 * sido instanciados. Roda logo depois do {@link ConfigDataEnvironmentPostProcessor}, que é quem
 * resolve os profiles ativos (variável, argumento e {@code application.yml}). Registrado em
 * {@code META-INF/spring.factories}.
 */
public class ProfileObrigatorioEnvironmentPostProcessor implements EnvironmentPostProcessor,
    Ordered {

  /** Profiles que definem o ambiente; exatamente um deles precisa estar ativo. */
  static final List<String> PROFILES_DE_AMBIENTE = List.of("dev", "homolog", "prod", "test");

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
    List<String> ativos = Arrays.asList(ambiente.getActiveProfiles());
    List<String> deAmbiente = ativos.stream().filter(PROFILES_DE_AMBIENTE::contains).toList();
    if (deAmbiente.isEmpty()) {
      throw new IllegalStateException("Nenhum profile de ambiente ativo (ativos: " + ativos
          + "). Defina SPRING_PROFILES_ACTIVE com um de " + PROFILES_DE_AMBIENTE
          + " (ex.: SPRING_PROFILES_ACTIVE=prod). A aplicacao nao sobe sem ele: o antigo padrao dev"
          + " ligava segredo JWT efemero, identidade por header e chave de cifra de"
          + " desenvolvimento. Local: ./mvnw spring-boot:run ja ativa dev.");
    }
    if (deAmbiente.size() > 1) {
      throw new IllegalStateException("Mais de um profile de ambiente ativo: " + deAmbiente
          + ". Use exatamente um de " + PROFILES_DE_AMBIENTE
          + " (profiles auxiliares como ingestao e avaliacao podem acompanhar).");
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }
}

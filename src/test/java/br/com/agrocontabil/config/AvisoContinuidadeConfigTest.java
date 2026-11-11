package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Aviso de subida da continuidade (#499): flag ligada, modelo por API e fora de dev/test. */
class AvisoContinuidadeConfigTest {

  @Test
  @DisplayName("Avisa só com a flag ligada, o provedor por API e sem perfil local")
  void avisa() {
    assertThat(aviso("true", "anthropic", "homolog")).isTrue();
    assertThat(aviso("true", "openai", "prod")).isTrue();
    assertThat(aviso("false", "anthropic", "homolog")).isFalse();
    assertThat(aviso("true", "ollama", "homolog")).isFalse();
    assertThat(aviso("true", "anthropic", "dev")).isFalse();
    assertThat(aviso("true", "anthropic", "test")).isFalse();
  }

  private static boolean aviso(String flag, String provedor, String perfil) {
    MockEnvironment ambiente = new MockEnvironment()
        .withProperty("app.assistente.continuidade.habilitada", flag)
        .withProperty("spring.ai.model.chat", provedor);
    ambiente.setActiveProfiles(perfil);
    return new AvisoContinuidadeConfig(ambiente).deveAvisar();
  }
}

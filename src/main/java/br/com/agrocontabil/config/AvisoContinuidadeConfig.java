package br.com.agrocontabil.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Aviso da continuidade da conversa da Ceres (#499): ligada com o modelo por API
 * ({@code AI_CHAT_PROVIDER} {@code anthropic} ou {@code openai}) fora de {@code dev}/{@code test},
 * as trocas anteriores de qualquer conta vão inteiras ao provedor, fora do Brasil. A subida
 * registra um {@code WARN} único e segue: ligar a flag é decisão de configuração, que exige antes
 * a política de dados nova, o aviso na tela e o prazo de retenção escrito ({@code docs/lgpd.md}).
 * Com o Ollama local, nada sai do servidor e não há aviso.
 */
@Component
public class AvisoContinuidadeConfig {

  private static final Logger log = LoggerFactory.getLogger(AvisoContinuidadeConfig.class);
  private static final Set<String> PROVEDORES_POR_API = Set.of("anthropic", "openai");
  private static final Set<String> PERFIS_LOCAIS = Set.of("dev", "test");

  private final Environment ambiente;

  public AvisoContinuidadeConfig(Environment ambiente) {
    this.ambiente = ambiente;
  }

  /** Avisa uma vez, com a aplicação no ar. */
  @EventListener(ApplicationReadyEvent.class)
  public void avisar() {
    if (deveAvisar()) {
      log.warn("Ceres: continuidade da conversa LIGADA com o modelo por API: as trocas anteriores "
          + "de qualquer conta vao inteiras ao provedor, fora do Brasil. Confira a politica de "
          + "dados, o aviso na tela e o prazo de retencao no provedor (docs/lgpd.md).");
    }
  }

  /** {@code true} com a flag ligada, o modelo por API e nenhum perfil local ativo. */
  boolean deveAvisar() {
    boolean ligada = ambiente.getProperty(
        "app.assistente.continuidade.habilitada", Boolean.class, false);
    String provedor = ambiente.getProperty("spring.ai.model.chat", "ollama")
        .strip().toLowerCase(Locale.ROOT);
    boolean local = Arrays.stream(ambiente.getActiveProfiles()).anyMatch(PERFIS_LOCAIS::contains);
    return ligada && PROVEDORES_POR_API.contains(provedor) && !local;
  }
}

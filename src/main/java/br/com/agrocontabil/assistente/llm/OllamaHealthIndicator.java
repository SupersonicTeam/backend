package br.com.agrocontabil.assistente.llm;

import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Health do Ollama no Actuator (issue #141), sob a chave {@code ollama}.
 *
 * <p>Consulta {@code GET /api/tags} e confere se os modelos que a Ceres usa estão baixados: o de
 * embedding sempre, e o de chat quando o provedor de chat é o próprio Ollama.
 *
 * <p><strong>{@link #DEGRADED}, nunca {@code DOWN}:</strong> com o Ollama fora do ar ou sem o
 * modelo, a Ceres responde 503 e todo o resto do sistema (notas, SEFAZ, apuração) segue
 * funcionando. {@code DOWN} derrubaria o {@code /actuator/health} para 503 e o healthcheck do
 * compose reiniciaria um backend saudável. {@code DEGRADED} entra na ordem de agregação
 * ({@code management.endpoint.health.status.order}) e responde 200.
 *
 * <p><strong>Cliente e cache próprios:</strong> o {@code RestClient} daqui tem timeout curto
 * ({@code app.assistente.saude.timeout}) em vez do read-timeout de geração, e o resultado é
 * reaproveitado por {@code app.assistente.saude.cache}. O health é público; sem o cache, cada
 * visitante dispararia uma chamada ao Ollama.
 *
 * <p>Só existe quando o embedding é do Ollama — isto é, fora do profile {@code test}.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "ollama",
    matchIfMissing = true)
public class OllamaHealthIndicator implements HealthIndicator {

  /** Ceres indisponível, restante do sistema saudável. */
  public static final Status DEGRADED =
      new Status("DEGRADED", "Assistente Ceres indisponivel; demais funcoes operando");

  private static final Logger log = LoggerFactory.getLogger(OllamaHealthIndicator.class);

  private final RestClient restClient;
  private final Set<String> modelosObrigatorios;
  private final long cacheNanos;

  private Health ultimo;
  private long ultimoEmNanos;

  /**
   * Constrói o indicador.
   *
   * @param baseUrl        URL do Ollama ({@code spring.ai.ollama.base-url})
   * @param provedorChat   provedor de chat ({@code spring.ai.model.chat})
   * @param modeloChat     modelo de chat do Ollama
   * @param modeloEmbedding modelo de embedding do Ollama
   * @param propriedades   timeout e cache do health
   */
  public OllamaHealthIndicator(
      @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
      @Value("${spring.ai.model.chat:ollama}") String provedorChat,
      @Value("${spring.ai.ollama.chat.options.model:}") String modeloChat,
      @Value("${spring.ai.ollama.embedding.options.model:}") String modeloEmbedding,
      AssistenteProperties propriedades) {
    SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(propriedades.getSaude().getTimeout());
    fabrica.setReadTimeout(propriedades.getSaude().getTimeout());
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(fabrica).build();

    Set<String> modelos = new LinkedHashSet<>();
    if (!modeloEmbedding.isBlank()) {
      modelos.add(normalizar(modeloEmbedding));
    }
    if ("ollama".equals(provedorChat) && !modeloChat.isBlank()) {
      modelos.add(normalizar(modeloChat));
    }
    this.modelosObrigatorios = Set.copyOf(modelos);
    this.cacheNanos = propriedades.getSaude().getCache().toNanos();
  }

  /**
   * Devolve o último resultado se ainda estiver dentro da janela de cache; senão consulta o
   * Ollama. {@code synchronized}: no máximo uma consulta em voo por vez, e quem chega durante ela
   * recebe o resultado novo em vez de disparar outra.
   */
  @Override
  public synchronized Health health() {
    long agora = System.nanoTime();
    if (ultimo != null && agora - ultimoEmNanos < cacheNanos) {
      return ultimo;
    }
    ultimo = consultar();
    ultimoEmNanos = System.nanoTime();
    return ultimo;
  }

  private Health consultar() {
    TagsResponse tags;
    try {
      tags = restClient.get().uri("/api/tags").retrieve().body(TagsResponse.class);
    } catch (RestClientException e) {
      log.warn("Ollama inacessivel no health: {}", e.getClass().getName());
      return Health.status(DEGRADED).withDetail("motivo", "Ollama inacessivel").build();
    }

    Set<String> presentes = new TreeSet<>();
    if (tags != null && tags.models() != null) {
      for (ModeloOllama modelo : tags.models()) {
        if (modelo != null && modelo.name() != null) {
          presentes.add(normalizar(modelo.name()));
        }
      }
    }
    List<String> ausentes = new ArrayList<>();
    for (String obrigatorio : modelosObrigatorios) {
      if (!presentes.contains(obrigatorio)) {
        ausentes.add(obrigatorio);
      }
    }
    if (!ausentes.isEmpty()) {
      return Health.status(DEGRADED)
          .withDetail("motivo", "Modelo nao baixado no Ollama")
          .withDetail("modelosAusentes", ausentes)
          .build();
    }
    return Health.up().withDetail("modelos", new TreeSet<>(modelosObrigatorios)).build();
  }

  /** O Ollama lista {@code bge-m3} como {@code bge-m3:latest}: sem tag, a tag é {@code latest}. */
  static String normalizar(String modelo) {
    String nome = modelo.trim();
    return nome.contains(":") ? nome : nome + ":latest";
  }

  /** Recorte da resposta de {@code GET /api/tags}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TagsResponse(List<ModeloOllama> models) {}

  /** Um modelo baixado no Ollama. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ModeloOllama(String name) {}
}

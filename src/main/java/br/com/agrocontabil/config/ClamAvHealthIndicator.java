package br.com.agrocontabil.config;

import br.com.agrocontabil.service.ClamdCliente;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Health do ClamAV no Actuator (issue #227), sob a chave {@code clamav}; registrado pelo
 * {@link AntivirusConfig} só no modo {@code CLAMAV}.
 *
 * <p><strong>{@link #DEGRADED}, nunca {@code DOWN}</strong>, como o do Ollama (#141): com o clamd
 * fora do ar só os anexos ficam em verificação (falha fechada) e o resto do sistema segue
 * funcionando. {@code DOWN} levaria o {@code /actuator/health} a 503 e o healthcheck do compose
 * reiniciaria um backend saudável. Sem este indicador a queda só aparecia no WARN do job.
 *
 * <p>O health é público: o resultado fica em cache para cada visitante não abrir uma conexão
 * com o clamd.
 */
public class ClamAvHealthIndicator implements HealthIndicator {

  /** Antivírus fora do ar, restante do sistema saudável. */
  public static final Status DEGRADED = new Status("DEGRADED",
      "Antivirus dos anexos indisponivel; anexos ficam em verificacao, demais funcoes operando");

  /** Janela do cache em produção. */
  static final Duration CACHE = Duration.ofSeconds(30);

  private final ClamdCliente clamd;
  private final long cacheNanos;

  private Health ultimo;
  private long ultimoEmNanos;

  /**
   * Cria o indicador.
   *
   * @param clamd cliente do clamd
   * @param cache por quanto tempo reaproveitar o último resultado
   */
  public ClamAvHealthIndicator(ClamdCliente clamd, Duration cache) {
    this.clamd = clamd;
    this.cacheNanos = cache.toNanos();
  }

  /** Uma consulta em voo por vez; quem chega durante ela recebe o resultado novo. */
  @Override
  public synchronized Health health() {
    long agora = System.nanoTime();
    if (ultimo != null && agora - ultimoEmNanos < cacheNanos) {
      return ultimo;
    }
    ultimo = clamd.responde()
        ? Health.up().build()
        : Health.status(DEGRADED).withDetail("motivo", "clamd inacessivel").build();
    ultimoEmNanos = System.nanoTime();
    return ultimo;
  }
}

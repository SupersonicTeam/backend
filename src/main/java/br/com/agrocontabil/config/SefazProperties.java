package br.com.agrocontabil.config;

import br.com.agrocontabil.sefaz.SefazEnvironment;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades de configuração do módulo de integração SEFAZ.
 *
 * <p>Prefix: {@code app.nfe.sefaz}. Configurado em {@code application.yml}:
 *
 * <pre>
 * app:
 *   nfe:
 *     sefaz:
 *       ambiente: HOMOLOGACAO        # HOMOLOGACAO | PRODUCAO
 *       mock:
 *         enabled: true              # false em prod (Sprint 7)
 *       retry:
 *         max-attempts: 3            # tentativas de reenvio em falha de comunicação
 *         backoff-ms: 1000           # intervalo inicial de backoff (ms)
 * </pre>
 *
 * <p>Registrada via {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * em {@link SefazConfig}.
 */
@ConfigurationProperties(prefix = "app.nfe.sefaz")
public class SefazProperties {

  /**
   * Ambiente de comunicação com a SEFAZ. Padrão: {@link SefazEnvironment#HOMOLOGACAO}.
   *
   * <p>Em produção (Sprint 7), alterar para {@code PRODUCAO} e fornecer certificado ICP-Brasil.
   */
  private SefazEnvironment ambiente = SefazEnvironment.HOMOLOGACAO;

  /** Configurações do mock de transporte SEFAZ. */
  private Mock mock = new Mock();

  /** Configurações de retry automático em failures de comunicação. */
  private Retry retry = new Retry();

  /** Configurações do job de sincronização de status (issue #16). */
  private Sync sync = new Sync();

  /** Retorna o ambiente de comunicação com a SEFAZ. */
  public SefazEnvironment getAmbiente() {
    return ambiente;
  }

  /** Define o ambiente de comunicação com a SEFAZ. */
  public void setAmbiente(SefazEnvironment ambiente) {
    this.ambiente = ambiente;
  }

  /** Retorna as configurações de mock. */
  public Mock getMock() {
    return mock;
  }

  /** Define as configurações de mock. */
  public void setMock(Mock mock) {
    this.mock = mock;
  }

  /** Retorna as configurações de retry. */
  public Retry getRetry() {
    return retry;
  }

  /** Define as configurações de retry. */
  public void setRetry(Retry retry) {
    this.retry = retry;
  }

  /** Retorna as configurações do job de sincronização. */
  public Sync getSync() {
    return sync;
  }

  /** Define as configurações do job de sincronização. */
  public void setSync(Sync sync) {
    this.sync = sync;
  }

  /**
   * Configurações do mock de transporte.
   *
   * <p>Quando {@code enabled=true} (padrão), o {@link br.com.agrocontabil.sefaz.MockSefazTransport}
   * é ativado. Em produção (Sprint 7), definir como {@code false} para usar o transporte SOAP real.
   */
  public static class Mock {

    /**
     * Habilita o mock de transporte SEFAZ. Padrão: {@code true}.
     * Definir {@code false} em produção após implementação do transporte SOAP real (Sprint 7).
     */
    private boolean enabled = true;

    /** Retorna se o mock está habilitado. */
    public boolean isEnabled() {
      return enabled;
    }

    /** Define se o mock está habilitado. */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /**
   * Configurações de retry automático para failures de comunicação com a SEFAZ.
   *
   * <p>Aplicado via {@code @Retryable} no {@link br.com.agrocontabil.service.SefazService}.
   * Somente {@link br.com.agrocontabil.exception.SefazCommunicationException} aciona o retry.
   */
  public static class Retry {

    /**
     * Número máximo de tentativas (incluindo a original). Padrão: {@code 3}.
     * Configurável por {@code app.nfe.sefaz.retry.max-attempts}.
     */
    private int maxAttempts = 3;

    /**
     * Intervalo inicial de backoff em milissegundos. Padrão: {@code 1000} (1 segundo).
     * Configurável por {@code app.nfe.sefaz.retry.backoff-ms}.
     */
    private long backoffMs = 1000L;

    /** Retorna o número máximo de tentativas. */
    public int getMaxAttempts() {
      return maxAttempts;
    }

    /** Define o número máximo de tentativas. */
    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    /** Retorna o intervalo de backoff em milissegundos. */
    public long getBackoffMs() {
      return backoffMs;
    }

    /** Define o intervalo de backoff em milissegundos. */
    public void setBackoffMs(long backoffMs) {
      this.backoffMs = backoffMs;
    }
  }

  /**
   * Configurações do job agendado de sincronização de status com a SEFAZ (issue #16).
   *
   * <p>O job ({@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}) consulta periodicamente
   * a SEFAZ para as notas em {@code PENDENTE_SEFAZ}. Desativado no perfil de testes.
   */
  public static class Sync {

    /** Habilita o job de sincronização. Padrão {@code true}. Definir {@code false} em testes. */
    private boolean enabled = true;

    /** Intervalo entre execuções em milissegundos. Padrão {@code 900000} (15 min). */
    private long intervalMs = 900000L;

    /** Retorna se o job de sincronização está habilitado. */
    public boolean isEnabled() {
      return enabled;
    }

    /** Define se o job de sincronização está habilitado. */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** Retorna o intervalo entre execuções em milissegundos. */
    public long getIntervalMs() {
      return intervalMs;
    }

    /** Define o intervalo entre execuções em milissegundos. */
    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }
  }
}

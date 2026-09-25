package br.com.agrocontabil.config;

import br.com.agrocontabil.sefaz.SefazAmbiente;
import org.springframework.beans.factory.InitializingBean;
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
 *         max-attempts: 3            # tentativas de reenvio em falha de conexão
 *         backoff-ms: 1000           # intervalo inicial de backoff (ms)
 *       timeout:
 *         connect-ms: 10000          # timeout de conexão do transporte real (issue #83)
 *         read-ms: 30000             # timeout de leitura do transporte real (issue #83)
 *       sync:
 *         lote-tamanho: 100          # IDs por lote do keyset (issue #83)
 *         max-falhas-consecutivas: 5 # disjuntor do job com a SEFAZ fora (issue #83)
 * </pre>
 *
 * <p><strong>Validação na subida (issue #83):</strong> {@link #afterPropertiesSet()} recusa
 * valores fora de faixa e exige que o pior caso de uma transmissão caiba em
 * {@code transmissao.timeout-ms}; ver {@link #piorCasoTransmissaoMs()}.
 *
 * <p>Registrada via {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * em {@link SefazConfig}.
 */
@ConfigurationProperties(prefix = "app.nfe.sefaz")
public class SefazProperties implements InitializingBean {

  /**
   * Ambiente de comunicação com a SEFAZ. Padrão: {@link SefazAmbiente#HOMOLOGACAO}.
   *
   * <p>Em produção (Sprint 7), alterar para {@code PRODUCAO} e fornecer certificado ICP-Brasil.
   */
  private SefazAmbiente ambiente = SefazAmbiente.HOMOLOGACAO;

  /** Configurações do mock de transporte SEFAZ. */
  private Mock mock = new Mock();

  /** Configurações de retry automático em falhas de comunicação. */
  private Retry retry = new Retry();

  /** Configurações do job de sincronização de status (issue #16). */
  private Sync sync = new Sync();
  private Transmissao transmissao = new Transmissao();

  /** Timeouts de rede do transporte real (issue #83). */
  private Timeout timeout = new Timeout();

  /**
   * Valida a configuração assim que o binder a preenche (issue #83). Uma configuração
   * inconsistente impede a aplicação de subir, em vez de falhar em produção na primeira queda da
   * SEFAZ.
   *
   * <p>O invariante central: o pior caso de uma transmissão precisa caber em
   * {@code transmissao.timeout-ms}. Esse é o tempo depois do qual
   * {@link br.com.agrocontabil.service.SefazService#reconciliarTransmissoesPresas()} considera a
   * nota presa e a tira de {@code ENVIANDO}/{@code CANCELANDO}. Se uma transmissão viva pudesse
   * durar mais do que isso, o job a "reconciliaria" no meio — e a tx2 dela ainda aplicaria o
   * desfecho por cima.
   *
   * @throws IllegalStateException se algum valor estiver fora de faixa ou o invariante falhar
   */
  @Override
  public void afterPropertiesSet() {
    exigir(retry.getMaxAttempts() >= 1, "app.nfe.sefaz.retry.max-attempts deve ser >= 1");
    exigir(retry.getBackoffMs() >= 0, "app.nfe.sefaz.retry.backoff-ms deve ser >= 0");
    exigir(timeout.getConnectMs() > 0, "app.nfe.sefaz.timeout.connect-ms deve ser > 0");
    exigir(timeout.getReadMs() > 0, "app.nfe.sefaz.timeout.read-ms deve ser > 0");
    exigir(sync.getLoteTamanho() > 0, "app.nfe.sefaz.sync.lote-tamanho deve ser > 0");
    exigir(sync.getMaxFalhasConsecutivas() > 0,
        "app.nfe.sefaz.sync.max-falhas-consecutivas deve ser > 0");
    long piorCaso = piorCasoTransmissaoMs();
    exigir(transmissao.getTimeoutMs() > piorCaso,
        "app.nfe.sefaz.transmissao.timeout-ms (" + transmissao.getTimeoutMs() + " ms) deve ser "
            + "maior que o pior caso de uma transmissao (" + piorCaso + " ms = envio + consulta de "
            + "reconciliacao, cada uma com max-attempts x (connect-ms + read-ms) + backoffs); "
            + "senao o job reconciliaria uma transmissao ainda em andamento");
  }

  /**
   * Pior caso, em ms, de uma operação na SEFAZ com todas as tentativas estourando os timeouts:
   * {@code max-attempts × (connect-ms + read-ms) + (max-attempts − 1) × backoff-ms}.
   *
   * @return duração máxima de uma chamada do {@code SefazClient}
   */
  public long piorCasoOperacaoMs() {
    long tentativas = retry.getMaxAttempts();
    return tentativas * (timeout.getConnectMs() + timeout.getReadMs())
        + (tentativas - 1) * retry.getBackoffMs();
  }

  /**
   * Pior caso, em ms, de uma transmissão: o envio (ou cancelamento) e a consulta de reconciliação
   * pela chave que ele pode disparar (issue #83), cada um no seu pior caso.
   *
   * @return duração máxima entre a reserva ({@code ENVIANDO}) e o desfecho
   */
  public long piorCasoTransmissaoMs() {
    return 2 * piorCasoOperacaoMs();
  }

  private static void exigir(boolean condicao, String mensagem) {
    if (!condicao) {
      throw new IllegalStateException("Configuracao SEFAZ invalida: " + mensagem);
    }
  }

  /** Retorna o ambiente de comunicação com a SEFAZ. */
  public SefazAmbiente getAmbiente() {
    return ambiente;
  }

  /** Define o ambiente de comunicação com a SEFAZ. */
  public void setAmbiente(SefazAmbiente ambiente) {
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

  /** Retorna as configuracoes de transmissao (issue #96). */
  public Transmissao getTransmissao() {
    return transmissao;
  }

  /** Define as configuracoes de transmissao (issue #96). */
  public void setTransmissao(Transmissao transmissao) {
    this.transmissao = transmissao;
  }

  /** Retorna os timeouts de rede do transporte (issue #83). */
  public Timeout getTimeout() {
    return timeout;
  }

  /** Define os timeouts de rede do transporte (issue #83). */
  public void setTimeout(Timeout timeout) {
    this.timeout = timeout;
  }

  /**
   * Timeouts de rede do transporte real da SEFAZ (issue #83).
   *
   * <p>O {@code MockSefazTransport} não abre conexão e os ignora; o transporte SOAP deve aplicá-los
   * ao cliente HTTP. Um timeout ausente deixaria uma chamada pendurada indefinidamente com a SEFAZ
   * lenta — e, como a transmissão roda fora de transação, seria a thread HTTP, e não o pool, que
   * ficaria presa.
   *
   * <p>Separar conexão de leitura é o que dá ao transporte a informação para classificar a falha:
   * estouro de {@code connect-ms} vira {@code SefazConexaoException} (nada saiu); estouro de
   * {@code read-ms} vira {@code SefazRespostaIndeterminadaException} (pode ter sido processado).
   */
  public static class Timeout {

    /** Tempo máximo para estabelecer a conexão (TCP + TLS). Padrão 10 s. */
    private long connectMs = 10_000L;

    /** Tempo máximo esperando a resposta depois de enviar. Padrão 30 s. */
    private long readMs = 30_000L;

    /** Retorna o timeout de conexão em ms. */
    public long getConnectMs() {
      return connectMs;
    }

    /** Define o timeout de conexão em ms. */
    public void setConnectMs(long connectMs) {
      this.connectMs = connectMs;
    }

    /** Retorna o timeout de leitura em ms. */
    public long getReadMs() {
      return readMs;
    }

    /** Define o timeout de leitura em ms. */
    public void setReadMs(long readMs) {
      this.readMs = readMs;
    }
  }

  /**
   * Parametros da transmissao a SEFAZ (issue #96).
   */
  public static class Transmissao {

    /**
     * Tempo maximo que uma nota pode permanecer num estado transitorio de transmissao
     * ({@code ENVIANDO}/{@code CANCELANDO}) antes de ser reconciliada pelo job.
     *
     * <p>Cobre a queda do processo entre a marcacao do estado transitorio e o desfecho: sem a
     * reconciliacao a nota ficaria presa, e nenhuma operacao sobre ela seria possivel. O padrao
     * de 10 minutos e folgado o bastante para uma transmissao com retry
     * ({@code app.nfe.sefaz.retry}) nao ser reconciliada enquanto ainda esta em andamento.
     */
    private long timeoutMs = 600_000L;

    /** Retorna o tempo, em ms, apos o qual uma transmissao presa e reconciliada. */
    public long getTimeoutMs() {
      return timeoutMs;
    }

    /** Define o tempo, em ms, apos o qual uma transmissao presa e reconciliada. */
    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }
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
   * Configurações de retry automático para falhas de comunicação com a SEFAZ.
   *
   * <p>Aplicado via {@code @Retryable} no {@link br.com.agrocontabil.sefaz.SefazClient}: envio e
   * cancelamento só re-tentam {@link br.com.agrocontabil.exception.SefazConexaoException}; a
   * consulta re-tenta qualquer falha de comunicação (issue #83).
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

    /**
     * IDs de notas pendentes lidos por lote, por keyset (issue #83). Padrão {@code 100}. Só IDs:
     * cada nota é carregada, consultada e aplicada isoladamente.
     */
    private int loteTamanho = 100;

    /**
     * Falhas de comunicação seguidas que encerram a execução (issue #83). Padrão {@code 5}.
     *
     * <p>Com a SEFAZ fora do ar, cada nota gasta o pior caso de uma consulta (~2 min com os
     * padrões); sem este disjuntor, 100 notas prenderiam o job por mais de 3 horas, muito além do
     * {@code lockAtMostFor} do ShedLock. Com ele, a execução para em ~10 min e as notas restantes
     * ficam para o próximo ciclo. Um sucesso zera a contagem.
     */
    private int maxFalhasConsecutivas = 5;

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

    /** Retorna o tamanho do lote de IDs do keyset. */
    public int getLoteTamanho() {
      return loteTamanho;
    }

    /** Define o tamanho do lote de IDs do keyset. */
    public void setLoteTamanho(int loteTamanho) {
      this.loteTamanho = loteTamanho;
    }

    /** Retorna o limite de falhas de comunicação seguidas antes de encerrar a execução. */
    public int getMaxFalhasConsecutivas() {
      return maxFalhasConsecutivas;
    }

    /** Define o limite de falhas de comunicação seguidas antes de encerrar a execução. */
    public void setMaxFalhasConsecutivas(int maxFalhasConsecutivas) {
      this.maxFalhasConsecutivas = maxFalhasConsecutivas;
    }
  }
}

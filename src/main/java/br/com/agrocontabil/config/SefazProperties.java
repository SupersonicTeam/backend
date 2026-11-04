package br.com.agrocontabil.config;

import br.com.agrocontabil.sefaz.SefazAmbiente;
import java.util.HashMap;
import java.util.Map;
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

  /** Endereços dos webservices por ambiente e UF autorizadora (issue #128). */
  private Webservices webservices = new Webservices();

  /** Cadeia de confiança dos servidores da SEFAZ (ICP-Brasil), para o transporte real. */
  private Truststore truststore = new Truststore();

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
    if (!mock.isEnabled()) {
      validarTransporteReal();
    }
    long piorCaso = piorCasoTransmissaoMs();
    exigir(transmissao.getTimeoutMs() > piorCaso,
        "app.nfe.sefaz.transmissao.timeout-ms (" + transmissao.getTimeoutMs() + " ms) deve ser "
            + "maior que o pior caso de uma transmissao (" + piorCaso + " ms = envio + consulta de "
            + "reconciliacao, cada uma com max-attempts x (connect-ms + read-ms) + backoffs); "
            + "senao o job reconciliaria uma transmissao ainda em andamento");
  }

  /**
   * Com o transporte real (issue #128): o ambiente configurado precisa de ao menos um conjunto de
   * endereços (de uma UF ou o {@code PADRAO}), todos completos e em HTTPS. Endereço faltando
   * descoberto só na primeira transmissão deixaria a nota presa; em HTTP, o XML assinado e o
   * certificado iriam em claro.
   */
  private void validarTransporteReal() {
    Map<String, Endpoints> doAmbiente = webservices.doAmbiente(ambiente);
    exigir(!doAmbiente.isEmpty(), "app.nfe.sefaz.webservices." + ambiente.name().toLowerCase()
        + " sem endereco: configure ao menos PADRAO ou a UF autorizadora");
    doAmbiente.forEach((uf, e) -> {
      for (String url : new String[] {e.getAutorizacao(), e.getConsulta(), e.getEvento()}) {
        exigir(url != null && url.startsWith("https://"),
            "app.nfe.sefaz.webservices." + ambiente.name().toLowerCase() + "." + uf
                + ": autorizacao, consulta e evento sao obrigatorios e em https://");
      }
    });
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

  /** Endereços dos webservices (issue #128). */
  public Webservices getWebservices() {
    return webservices;
  }

  /** Define os endereços dos webservices. */
  public void setWebservices(Webservices webservices) {
    this.webservices = webservices;
  }

  /** Cadeia de confiança dos servidores da SEFAZ. */
  public Truststore getTruststore() {
    return truststore;
  }

  /** Define a cadeia de confiança. */
  public void setTruststore(Truststore truststore) {
    this.truststore = truststore;
  }

  /**
   * Endereços dos webservices NF-e 4.00 por ambiente (issue #128). A chave do mapa é a sigla da
   * UF autorizadora (pelo cUF da chave de acesso) ou {@code PADRAO}, usado quando a UF não tem
   * entrada própria (ex.: as UFs atendidas pela SVRS).
   */
  public static class Webservices {

    /** Chave do mapa usada quando a UF da nota não tem endereço próprio. */
    public static final String PADRAO = "PADRAO";

    private Map<String, Endpoints> homologacao = new HashMap<>();
    private Map<String, Endpoints> producao = new HashMap<>();

    /** Endereços do ambiente informado. */
    public Map<String, Endpoints> doAmbiente(SefazAmbiente ambiente) {
      return ambiente == SefazAmbiente.PRODUCAO ? producao : homologacao;
    }

    /**
     * Endereços de uma UF (ou do {@code PADRAO}) sem diferenciar maiúsculas: vindo de variável de
     * ambiente, o Spring entrega a chave do mapa em minúsculas ({@code pr}, {@code padrao}).
     *
     * @return os endereços, ou {@code null} se não houver entrada
     */
    public Endpoints buscar(SefazAmbiente ambiente, String chave) {
      if (chave == null) {
        return null;
      }
      return doAmbiente(ambiente).entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(chave))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
    }

    public Map<String, Endpoints> getHomologacao() {
      return homologacao;
    }

    public void setHomologacao(Map<String, Endpoints> homologacao) {
      this.homologacao = homologacao;
    }

    public Map<String, Endpoints> getProducao() {
      return producao;
    }

    public void setProducao(Map<String, Endpoints> producao) {
      this.producao = producao;
    }
  }

  /**
   * Os três serviços usados de uma autorizadora.
   *
   * <p>{@code autorizacao} = NFeAutorizacao4, {@code consulta} = NFeConsultaProtocolo4,
   * {@code evento} = NFeRecepcaoEvento4.
   */
  public static class Endpoints {

    private String autorizacao;
    private String consulta;
    private String evento;

    public String getAutorizacao() {
      return autorizacao;
    }

    public void setAutorizacao(String autorizacao) {
      this.autorizacao = autorizacao;
    }

    public String getConsulta() {
      return consulta;
    }

    public void setConsulta(String consulta) {
      this.consulta = consulta;
    }

    public String getEvento() {
      return evento;
    }

    public void setEvento(String evento) {
      this.evento = evento;
    }
  }

  /**
   * Truststore com a cadeia ICP-Brasil dos servidores da SEFAZ. Sem caminho, vale o truststore
   * padrão da JVM, que <strong>não</strong> traz a AC raiz ICP-Brasil: em homologação real é
   * preciso configurá-lo.
   */
  public static class Truststore {

    private String path;
    private String password;

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }
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
   * {@code read-ms} com a conexão já feita vira {@code SefazRespostaIndeterminadaException} (pode
   * ter sido processado).
   *
   * <p>O {@code read-ms} é o timeout da requisição do {@code HttpClient}, que conta desde o envio,
   * com a conexão e o handshake dentro: se ele vence antes de a conexão se estabelecer, a falha é
   * de conexão (nada saiu). Na prática, a conexão tem o menor dos dois prazos (#335).
   */
  public static class Timeout {

    /** Tempo máximo para estabelecer a conexão (TCP + TLS). Padrão 10 s. */
    private long connectMs = 10_000L;

    /** Tempo máximo pela resposta, contado desde o envio (conexão inclusa). Padrão 30 s. */
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

    /**
     * Na consulta, a chave que o mock não transmitiu volta AUTORIZADA em vez de 217 (issue #223).
     * Padrão: {@code false}.
     *
     * <p>A importação de NF-e de terceiro confirma a autorização na SEFAZ pela chave; o mock só
     * conhece as chaves que ele mesmo transmitiu, então no dev nenhuma NF-e de terceiro seria
     * confirmada. Ligado só no {@code application-dev.yml}, para a demonstração; sem o
     * {@code digVal}, o conteúdo do XML não é conferido. Vale para qualquer chave desconhecida —
     * inclusive a de nota própria pendente que nunca chegou ao mock, que a sincronização daria como
     * autorizada (a guarda da #96 fica desligada em dev). Nunca fora de dev.
     */
    private boolean autorizarTerceiros = false;

    /** Retorna se o mock está habilitado. */
    public boolean isEnabled() {
      return enabled;
    }

    /** Define se o mock está habilitado. */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** Retorna se a consulta de chave desconhecida volta autorizada (só dev). */
    public boolean isAutorizarTerceiros() {
      return autorizarTerceiros;
    }

    /** Define se a consulta de chave desconhecida volta autorizada (só dev). */
    public void setAutorizarTerceiros(boolean autorizarTerceiros) {
      this.autorizarTerceiros = autorizarTerceiros;
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

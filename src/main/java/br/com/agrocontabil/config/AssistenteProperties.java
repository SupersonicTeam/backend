package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades da Ceres, a assistente fiscal (issue #141).
 *
 * <p>Prefix: {@code app.assistente}. A escolha do provedor e do modelo fica nas propriedades do
 * próprio Spring AI ({@code spring.ai.*}); aqui vivem só as decisões do AgroContabil.
 *
 * <pre>
 * app:
 *   assistente:
 *     saude:
 *       timeout: 2s     # timeout do health do Ollama
 *       cache: 10s      # por quanto tempo o último resultado do health é reaproveitado
 *     max-rodadas-ferramentas: 3     # teto do laço modelo → função → modelo (issue #142)
 *     max-conversas-simultaneas: 4   # perguntas em andamento ao mesmo tempo (issues #142, #157)
 *     timeout-pergunta: 5m           # tempo total de uma pergunta (issue #157)
 *     timeout-funcao: 10s            # tempo de cada consulta aos dados do produtor (issue #157)
 * </pre>
 *
 * <p>Registrada via {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * em {@link AssistenteConfig}.
 */
@ConfigurationProperties(prefix = "app.assistente")
public class AssistenteProperties implements InitializingBean {

  /** Health indicator do Ollama. */
  private Saude saude = new Saude();

  /**
   * Quantas vezes, numa mesma pergunta, o modelo pode pedir funções antes de o backend desistir
   * (issue #142). O laço de ferramentas é do backend justamente para ter este teto: sem ele, um
   * modelo confuso (ou induzido por prompt injection) chamaria funções indefinidamente.
   */
  private int maxRodadasFerramentas = 3;

  /**
   * Perguntas processadas ao mesmo tempo (issues #142 e #157); é também o tamanho do pool de
   * threads da Ceres. Cada pergunta ocupa uma thread pelo tempo de geração do modelo — dezenas de
   * segundos em CPU. Acima do teto a resposta é 503 {@code ASSISTENTE_OCUPADO}, sem fila.
   */
  private int maxConversasSimultaneas = 4;

  /**
   * Tempo total de uma pergunta: classificação, funções e geração (issue #157). Passou disso, a
   * pergunta é interrompida e o cliente recebe 503 {@code ASSISTENTE_TEMPO_ESGOTADO}. Vale também
   * como timeout do streaming (SSE).
   */
  private Duration timeoutPergunta = Duration.ofMinutes(5);

  /**
   * Tempo de cada consulta de uma função aos dados do produtor (issue #157), aplicado como timeout
   * de transação — e, por ela, como timeout das queries no banco. Resolução de segundos (a do
   * timeout de transação do Spring).
   */
  private Duration timeoutFuncao = Duration.ofSeconds(10);

  /** Recuperação de legislação (issue #156). */
  private Rag rag = new Rag();

  /** Orquestração (issue #157). */
  private Orquestracao orquestracao = new Orquestracao();

  /**
   * Recusa valores fora de faixa na subida, em vez de descobrir o problema no primeiro health.
   *
   * @throws IllegalStateException se algum valor for nulo, zero ou negativo
   */
  @Override
  public void afterPropertiesSet() {
    exigirPositivo(saude.getTimeout(), "app.assistente.saude.timeout");
    exigirPositivo(saude.getCache(), "app.assistente.saude.cache");
    if (maxRodadasFerramentas < 1 || maxRodadasFerramentas > 10) {
      throw new IllegalStateException(
          "app.assistente.max-rodadas-ferramentas deve estar entre 1 e 10, recebido: "
              + maxRodadasFerramentas);
    }
    if (rag.getTopK() < 1 || rag.getTopK() > 10) {
      throw new IllegalStateException(
          "app.assistente.rag.top-k deve estar entre 1 e 10, recebido: " + rag.getTopK());
    }
    if (rag.getSimilaridadeMinima() < 0 || rag.getSimilaridadeMinima() >= 1) {
      throw new IllegalStateException(
          "app.assistente.rag.similaridade-minima deve estar em [0, 1), recebido: "
              + rag.getSimilaridadeMinima());
    }
    if (orquestracao.getConfiancaMinima() < 0 || orquestracao.getConfiancaMinima() > 1) {
      throw new IllegalStateException(
          "app.assistente.orquestracao.confianca-minima deve estar em [0, 1], recebido: "
              + orquestracao.getConfiancaMinima());
    }
    exigirPositivo(timeoutPergunta, "app.assistente.timeout-pergunta");
    exigirPositivo(timeoutFuncao, "app.assistente.timeout-funcao");
    if (timeoutFuncao.toSeconds() < 1) {
      throw new IllegalStateException(
          "app.assistente.timeout-funcao deve ser de pelo menos 1s (resolucao do timeout de "
              + "transacao), recebido: " + timeoutFuncao);
    }
    if (timeoutFuncao.compareTo(timeoutPergunta) >= 0) {
      throw new IllegalStateException(
          "app.assistente.timeout-funcao (" + timeoutFuncao + ") deve ser menor que "
              + "app.assistente.timeout-pergunta (" + timeoutPergunta + ")");
    }
    if (maxConversasSimultaneas < 1) {
      throw new IllegalStateException(
          "app.assistente.max-conversas-simultaneas deve ser >= 1, recebido: "
              + maxConversasSimultaneas);
    }
  }

  private static void exigirPositivo(Duration valor, String nome) {
    if (valor == null || valor.isNegative() || valor.isZero()) {
      throw new IllegalStateException(nome + " deve ser maior que zero, recebido: " + valor);
    }
  }

  public int getMaxRodadasFerramentas() {
    return maxRodadasFerramentas;
  }

  public void setMaxRodadasFerramentas(int maxRodadasFerramentas) {
    this.maxRodadasFerramentas = maxRodadasFerramentas;
  }

  public int getMaxConversasSimultaneas() {
    return maxConversasSimultaneas;
  }

  public void setMaxConversasSimultaneas(int maxConversasSimultaneas) {
    this.maxConversasSimultaneas = maxConversasSimultaneas;
  }

  public Duration getTimeoutPergunta() {
    return timeoutPergunta;
  }

  public void setTimeoutPergunta(Duration timeoutPergunta) {
    this.timeoutPergunta = timeoutPergunta;
  }

  public Duration getTimeoutFuncao() {
    return timeoutFuncao;
  }

  public void setTimeoutFuncao(Duration timeoutFuncao) {
    this.timeoutFuncao = timeoutFuncao;
  }

  public Orquestracao getOrquestracao() {
    return orquestracao;
  }

  public void setOrquestracao(Orquestracao orquestracao) {
    this.orquestracao = orquestracao;
  }

  public Rag getRag() {
    return rag;
  }

  public void setRag(Rag rag) {
    this.rag = rag;
  }

  public Saude getSaude() {
    return saude;
  }

  public void setSaude(Saude saude) {
    this.saude = saude;
  }

  /** Orquestração (issue #157). */
  public static class Orquestracao {

    /**
     * Confiança mínima do classificador de intenção. Abaixo dela a pergunta é tratada como fora de
     * escopo: na dúvida, a Ceres não responde.
     */
    private double confiancaMinima = 0.5;

    public double getConfiancaMinima() {
      return confiancaMinima;
    }

    public void setConfiancaMinima(double confiancaMinima) {
      this.confiancaMinima = confiancaMinima;
    }
  }

  /** Recuperação de trechos de lei (issue #156). */
  public static class Rag {

    /** Quantos trechos recuperar por pergunta (as marcas vão de [T1] a [Tn]). */
    private int topK = 5;

    /**
     * Similaridade de cosseno mínima para um trecho entrar. Abaixo dela, o trecho provavelmente não
     * trata do assunto — e a Ceres responde "não encontrei fundamento legal", em vez de citar lei
     * sem relação.
     */
    private double similaridadeMinima = 0.55;

    public int getTopK() {
      return topK;
    }

    public void setTopK(int topK) {
      this.topK = topK;
    }

    public double getSimilaridadeMinima() {
      return similaridadeMinima;
    }

    public void setSimilaridadeMinima(double similaridadeMinima) {
      this.similaridadeMinima = similaridadeMinima;
    }
  }

  /** Configuração do health indicator do Ollama. */
  public static class Saude {

    /**
     * Timeout de conexão e de leitura da consulta ao Ollama. Curto de propósito: o health não pode
     * herdar o read-timeout da geração ({@code spring.http.client.read-timeout}).
     */
    private Duration timeout = Duration.ofSeconds(2);

    /**
     * Janela em que o último resultado é reaproveitado. O {@code /actuator/health} é público:
     * sem o cache, cada visitante dispararia uma requisição ao Ollama.
     */
    private Duration cache = Duration.ofSeconds(10);

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public Duration getCache() {
      return cache;
    }

    public void setCache(Duration cache) {
      this.cache = cache;
    }
  }
}

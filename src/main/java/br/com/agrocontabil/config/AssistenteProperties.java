package br.com.agrocontabil.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
 *     max-conversas-simultaneas: 4   # perguntas em andamento ao mesmo tempo (issues #142, #157)
 *     timeout-pergunta: 5m           # tempo total de uma pergunta (issue #157)
 *     timeout-funcao: 10s            # tempo de cada consulta aos dados do produtor (issue #157)
 *     tetos:                         # tokens gerados por tipo de chamada ao modelo (#270)
 *       funcoes: 512
 *       explicacao: 256              # explicacao depois da resposta de dados (#268)
 *       classificacao: 256
 *       legislacao: 1024
 *       contexto: 8192               # = num-ctx do Ollama, so para a validacao
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

  /** Modo fallback, sem modelo (issue #146). */
  private Fallback fallback = new Fallback();

  /** Classificação de intenção em camadas (issue #265). */
  private Classificacao classificacao = new Classificacao();

  /** Cota semanal de créditos (issue #166). */
  private Cota cota = new Cota();

  /** Explicação do modelo depois da resposta de dados (#268), atrás da flag. */
  private Explicacao explicacao = new Explicacao();

  /** Conferência de fidelidade da rota de lei (#269); desligada por padrão. */
  private FidelidadeLei fidelidadeLei = new FidelidadeLei();

  /** Teto de tokens gerados por tipo de chamada ao modelo (#270). */
  private Tetos tetos = new Tetos();

  /**
   * Recusa valores fora de faixa na subida, em vez de descobrir o problema no primeiro health.
   *
   * @throws IllegalStateException se algum valor for nulo, zero ou negativo
   */
  @Override
  public void afterPropertiesSet() {
    exigirPositivo(saude.getTimeout(), "app.assistente.saude.timeout");
    exigirPositivo(saude.getCache(), "app.assistente.saude.cache");
    if (rag.getTopK() < 1 || rag.getTopK() > 10) {
      throw new IllegalStateException(
          "app.assistente.rag.top-k deve estar entre 1 e 10, recebido: " + rag.getTopK());
    }
    if (rag.getSimilaridadeMinima() < 0 || rag.getSimilaridadeMinima() >= 1) {
      throw new IllegalStateException(
          "app.assistente.rag.similaridade-minima deve estar em [0, 1), recebido: "
              + rag.getSimilaridadeMinima());
    }
    if (classificacao.getLimiarConfianca() < 0 || classificacao.getLimiarConfianca() > 1
        || classificacao.getSimilaridadeMinima() < -1 || classificacao.getSimilaridadeMinima() > 1
        || classificacao.getVizinhos() < 1 || classificacao.getVizinhos() > 50) {
      throw new IllegalStateException("app.assistente.classificacao fora de faixa: limiar e"
          + " similaridade-minima entre 0 e 1, vizinhos entre 1 e 50");
    }
    exigirPositivo(classificacao.getTimeoutEmbedding(),
        "app.assistente.classificacao.timeout-embedding");
    exigirPositivo(classificacao.getPausaAposFalha(),
        "app.assistente.classificacao.pausa-apos-falha");
    if (orquestracao.getConfiancaMinima() < 0 || orquestracao.getConfiancaMinima() > 1) {
      throw new IllegalStateException(
          "app.assistente.orquestracao.confianca-minima deve estar em [0, 1], recebido: "
              + orquestracao.getConfiancaMinima());
    }
    exigirPositivo(fallback.getPausaAposFalha(), "app.assistente.fallback.pausa-apos-falha");
    if (fallback.getMaxTrechos() < 1 || fallback.getMaxTrechos() > 10) {
      throw new IllegalStateException(
          "app.assistente.fallback.max-trechos deve estar entre 1 e 10, recebido: "
              + fallback.getMaxTrechos());
    }
    if (fallback.getTamanhoMaximoTrecho() < 100) {
      throw new IllegalStateException(
          "app.assistente.fallback.tamanho-maximo-trecho deve ser >= 100, recebido: "
              + fallback.getTamanhoMaximoTrecho());
    }
    if (cota.getCreditosPorSemanaProdutor() < 1 || cota.getCreditosPorSemanaEscritorio() < 1
        || cota.getTokensPorCredito() < 1) {
      throw new IllegalStateException(
          "app.assistente.cota: creditos por semana e tokens-por-credito devem ser >= 1");
    }
    if (cota.getPesoEntrada() < 0 || cota.getPesoSaida() <= 0 || cota.getFatorPadrao() <= 0
        || cota.getFatorPorModelo().values().stream().anyMatch(f -> f == null || f <= 0)) {
      throw new IllegalStateException(
          "app.assistente.cota: peso-saida e fatores devem ser > 0 e peso-entrada >= 0");
    }
    tetos.validar();
    if (cota.getTetoTokensEntradaPorChamada() < tetos.getContexto()) {
      // O Ollama aceita um prompt do tamanho da janela inteira: abaixo dela, uma chamada de prompt
      // longo (a lei, com os trechos) consumiria mais entrada do que a cota reservou.
      throw new IllegalStateException("app.assistente.cota.teto-tokens-entrada-por-chamada ("
          + cota.getTetoTokensEntradaPorChamada() + ") deve ser >= app.assistente.tetos.contexto ("
          + tetos.getContexto() + ")");
    }
    long teto = tetoDaPergunta(cota.maiorFator());
    long menorPlano = Math.min(
        cota.getCreditosPorSemanaProdutor(), cota.getCreditosPorSemanaEscritorio());
    if (cota.isHabilitada() && teto > menorPlano) {
      // Sem isto a aplicacao sobe e toda pergunta recebe 429 "creditos esgotados".
      throw new IllegalStateException("app.assistente.cota: o teto de uma pergunta (" + teto
          + " creditos, com o maior fator) passa do menor plano (" + menorPlano + "). Aumente os "
          + "creditos por semana ou reduza os tetos de app.assistente.tetos.");
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
    explicacao.validar(timeoutPergunta);
    fidelidadeLei.validar();
  }

  /**
   * {@code true} se o juiz da fidelidade da lei (#269, camada 2) roda: ele e a camada 1 ligados.
   * Só então a reserva da pergunta inclui a chamada dele.
   */
  public boolean juizDaLeiAtivo() {
    return fidelidadeLei.isEnabled() && fidelidadeLei.getJuiz().isEnabled();
  }

  /**
   * Custo máximo de uma pergunta (#166): as chamadas da fórmula ({@link Cota#tetoDaPergunta}) e,
   * com o juiz da lei ativo (#269), a chamada dele como parcela própria
   * ({@link Cota#tetoDoJuiz}). O juiz roda no meio da pergunta e os tokens dele entram nos da
   * pergunta: por isso a parcela fica na reserva dela, não numa reserva à parte como a explicação.
   *
   * @param fator fator de custo do modelo
   * @return créditos
   */
  public long tetoDaPergunta(double fator) {
    long teto = cota.tetoDaPergunta(tetos, fator);
    return juizDaLeiAtivo() ? teto + cota.tetoDoJuiz(tetos, fator) : teto;
  }

  public FidelidadeLei getFidelidadeLei() {
    return fidelidadeLei;
  }

  public void setFidelidadeLei(FidelidadeLei fidelidadeLei) {
    this.fidelidadeLei = fidelidadeLei;
  }

  public Explicacao getExplicacao() {
    return explicacao;
  }

  public void setExplicacao(Explicacao explicacao) {
    this.explicacao = explicacao;
  }

  public Tetos getTetos() {
    return tetos;
  }

  public void setTetos(Tetos tetos) {
    this.tetos = tetos;
  }

  private static void exigirPositivo(Duration valor, String nome) {
    if (valor == null || valor.isNegative() || valor.isZero()) {
      throw new IllegalStateException(nome + " deve ser maior que zero, recebido: " + valor);
    }
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

  public Cota getCota() {
    return cota;
  }

  public void setCota(Cota cota) {
    this.cota = cota;
  }

  public Fallback getFallback() {
    return fallback;
  }

  public Classificacao getClassificacao() {
    return classificacao;
  }

  public void setClassificacao(Classificacao classificacao) {
    this.classificacao = classificacao;
  }

  public void setFallback(Fallback fallback) {
    this.fallback = fallback;
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

  /**
   * Classificação de intenção em camadas (issue #265): regras → embeddings → modelo de linguagem
   * só no desempate e na separação da pergunta de lei do MISTO.
   */
  public static class Classificacao {

    /**
     * Liga as regras e as embeddings. Desligado, toda pergunta vai ao classificador por modelo de
     * linguagem (Fase 1) — o profile {@code test} desliga para os testes de rota não dependerem
     * do conjunto de treino.
     */
    private boolean camadas = true;

    /** Confiança mínima do voto das embeddings; abaixo dela, o modelo desempata. */
    private double limiarConfianca = 0.6;

    /** Vizinhos que votam no kNN. */
    private int vizinhos = 7;

    /** Similaridade de cosseno mínima do vizinho mais próximo; abaixo, as embeddings não decidem. */
    private double similaridadeMinima = 0.5;

    /**
     * Prazo do embedding da pergunta na classificação. Venceu (Ollama ocupado numa geração ou
     * fora do ar), a camada não decide e a pergunta segue para o desempate; o read-timeout do
     * cliente de IA (minutos) não serve aqui.
     */
    private Duration timeoutEmbedding = Duration.ofMillis(500);

    /** Depois de uma falha ou prazo vencido, a camada fica sem chamar o embedding por este tempo. */
    private Duration pausaAposFalha = Duration.ofSeconds(30);

    public Duration getTimeoutEmbedding() {
      return timeoutEmbedding;
    }

    public void setTimeoutEmbedding(Duration timeoutEmbedding) {
      this.timeoutEmbedding = timeoutEmbedding;
    }

    public Duration getPausaAposFalha() {
      return pausaAposFalha;
    }

    public void setPausaAposFalha(Duration pausaAposFalha) {
      this.pausaAposFalha = pausaAposFalha;
    }

    public boolean isCamadas() {
      return camadas;
    }

    public void setCamadas(boolean camadas) {
      this.camadas = camadas;
    }

    public double getLimiarConfianca() {
      return limiarConfianca;
    }

    public void setLimiarConfianca(double limiarConfianca) {
      this.limiarConfianca = limiarConfianca;
    }

    public int getVizinhos() {
      return vizinhos;
    }

    public void setVizinhos(int vizinhos) {
      this.vizinhos = vizinhos;
    }

    public double getSimilaridadeMinima() {
      return similaridadeMinima;
    }

    public void setSimilaridadeMinima(double similaridadeMinima) {
      this.similaridadeMinima = similaridadeMinima;
    }
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

  /**
   * Cota semanal de créditos da Ceres por titular (issue #166, etapa A do épico de planos).
   *
   * <pre>
   * créditos = ⌈(tokens_entrada × pesoEntrada + tokens_saída × pesoSaida) × fator_do_modelo
   *             ÷ tokensPorCredito⌉
   * </pre>
   *
   * <p>A semana vai de segunda 00:00 a domingo 23:59 em {@code America/Sao_Paulo}; sobra não
   * acumula. Antes de cada pergunta é reservado o teto possível dela ({@link #tetoDaPergunta});
   * no fim, o consumo real é acertado e a diferença volta.
   */
  public static class Cota {

    /**
     * Chamadas ao modelo que uma pergunta pode fazer, no máximo: classificação (só no desempate
     * das camadas e na {@code perguntaLei} do MISTO, #265), escolha das funções de dados (uma
     * só desde a #266) e lei. O pior caso é o MISTO com desempate. Uma chamada nova ao modelo
     * exige rever este número e {@link #tetoDaPergunta}: o {@code TetoDaCotaCobreChamadasTest}
     * quebra se aparecer outro ponto que chame o modelo.
     */
    public static final int CHAMADAS_POR_PERGUNTA = 3;

    /** Desliga a cota (demonstração do TCC): nada é reservado nem bloqueado. */
    private boolean habilitada = true;

    /**
     * Créditos de um consumo — a fórmula única da cota:
     * {@code ⌈(entrada × pesoEntrada + saída × pesoSaida) × fator ÷ tokensPorCredito⌉}. Consumo
     * zero (modo fallback, sem modelo) custa zero.
     */
    public long creditos(long tokensEntrada, long tokensSaida, double fator) {
      double ponderado = Math.max(0, tokensEntrada) * pesoEntrada
          + Math.max(0, tokensSaida) * pesoSaida;
      if (ponderado <= 0) {
        return 0;
      }
      return (long) Math.ceil(ponderado * fator / tokensPorCredito);
    }

    /**
     * Custo máximo possível de uma pergunta (#166, revisto depois da #270): as
     * {@value #CHAMADAS_POR_PERGUNTA} chamadas, cada uma com o teto de entrada e com o próprio
     * teto de saída ({@code app.assistente.tetos}: classificação + funções + lei). O teto de saída
     * é o {@code maxTokens} que a própria chamada manda ao modelo, então a saída real nunca passa
     * dele.
     */
    public long tetoDaPergunta(Tetos tetos, double fator) {
      long saida = (long) tetos.getClassificacao() + tetos.getFuncoes() + tetos.getLegislacao();
      return creditos(CHAMADAS_POR_PERGUNTA * tetoTokensEntradaPorChamada, saida, fator);
    }

    /**
     * Custo máximo do juiz da fidelidade da lei (#269, camada 2): uma chamada, com o teto de entrada
     * e o teto de saída próprio ({@code app.assistente.tetos.juiz}). Entra na reserva da pergunta
     * só com o juiz ativo ({@code AssistenteProperties.tetoDaPergunta}).
     */
    public long tetoDoJuiz(Tetos tetos, double fator) {
      return creditos(tetoTokensEntradaPorChamada, tetos.getJuiz(), fator);
    }

    /**
     * Custo máximo da explicação depois da resposta de dados (#268): uma chamada, com o teto de
     * entrada e o teto de saída próprio ({@code app.assistente.tetos.explicacao}). É uma reserva à
     * parte, feita só quando a explicação vai acontecer; sem saldo para ela, a resposta sai sem a
     * explicação e sem 429.
     */
    public long tetoDaExplicacao(Tetos tetos, double fator) {
      return creditos(tetoTokensEntradaPorChamada, tetos.getExplicacao(), fator);
    }

    /** Maior fator configurado (o padrão ou o de algum modelo). */
    public double maiorFator() {
      double maior = fatorPadrao;
      for (Double fator : fatorPorModelo.values()) {
        if (fator != null && fator > maior) {
          maior = fator;
        }
      }
      return maior;
    }

    /** Nome do plano padrão mostrado ao usuário até as assinaturas existirem. */
    private String plano = "padrao";

    /** Créditos por semana do produtor no plano padrão (1 crédito por token desde a #511). */
    private long creditosPorSemanaProdutor = 1_000_000;

    /** Créditos por semana do escritório de contabilidade (quando o contador puder perguntar). */
    private long creditosPorSemanaEscritorio = 5_000_000;

    /** Peso do token de entrada. */
    private double pesoEntrada = 1.0;

    /** Peso do token de saída: custa mais nas APIs e é a parte lenta rodando local. */
    private double pesoSaida = 4.0;

    /**
     * Tokens ponderados que valem um crédito: 1 desde a #511 (decisão do Murillo, 2026-11-11, "1
     * crédito por token, tudo em números inteiros"); o arredondamento para cima fica só no total
     * da pergunta, então um "olá" custa menos que um "quanto imposto devo".
     */
    private long tokensPorCredito = 1;

    /** Fator do modelo quando ele não está em {@link #fatorPorModelo}. */
    private double fatorPadrao = 1.0;

    /**
     * Fator por modelo, pela identificação da auditoria ({@code provedor/modelo}, ex.:
     * {@code openai/gpt-4o}). Trocar de modelo ajusta o fator, não os planos.
     */
    private Map<String, Double> fatorPorModelo = new HashMap<>();

    /**
     * Teto de tokens de entrada de uma chamada ao modelo, para a reserva. Padrão = a janela do
     * Ollama ({@code num-ctx}), o maior prompt que ele aceita; abaixo dela a aplicação não sobe.
     * Com provedor por API o backend não limita o prompt (a lei não tem teto de tamanho para os
     * trechos): o acerto cobra no máximo o reservado e registra WARN se o real passar.
     */
    private long tetoTokensEntradaPorChamada = 8192;

    public boolean isHabilitada() {
      return habilitada;
    }

    public void setHabilitada(boolean habilitada) {
      this.habilitada = habilitada;
    }

    public String getPlano() {
      return plano;
    }

    public void setPlano(String plano) {
      this.plano = plano;
    }

    public long getCreditosPorSemanaProdutor() {
      return creditosPorSemanaProdutor;
    }

    public void setCreditosPorSemanaProdutor(long creditosPorSemanaProdutor) {
      this.creditosPorSemanaProdutor = creditosPorSemanaProdutor;
    }

    public long getCreditosPorSemanaEscritorio() {
      return creditosPorSemanaEscritorio;
    }

    public void setCreditosPorSemanaEscritorio(long creditosPorSemanaEscritorio) {
      this.creditosPorSemanaEscritorio = creditosPorSemanaEscritorio;
    }

    public double getPesoEntrada() {
      return pesoEntrada;
    }

    public void setPesoEntrada(double pesoEntrada) {
      this.pesoEntrada = pesoEntrada;
    }

    public double getPesoSaida() {
      return pesoSaida;
    }

    public void setPesoSaida(double pesoSaida) {
      this.pesoSaida = pesoSaida;
    }

    public long getTokensPorCredito() {
      return tokensPorCredito;
    }

    public void setTokensPorCredito(long tokensPorCredito) {
      this.tokensPorCredito = tokensPorCredito;
    }

    public double getFatorPadrao() {
      return fatorPadrao;
    }

    public void setFatorPadrao(double fatorPadrao) {
      this.fatorPadrao = fatorPadrao;
    }

    public Map<String, Double> getFatorPorModelo() {
      return fatorPorModelo;
    }

    public void setFatorPorModelo(Map<String, Double> fatorPorModelo) {
      this.fatorPorModelo = fatorPorModelo;
    }

    public long getTetoTokensEntradaPorChamada() {
      return tetoTokensEntradaPorChamada;
    }

    public void setTetoTokensEntradaPorChamada(long tetoTokensEntradaPorChamada) {
      this.tetoTokensEntradaPorChamada = tetoTokensEntradaPorChamada;
    }
  }

  /**
   * Teto de tokens gerados por tipo de chamada ao modelo (#270), no lugar de um
   * {@code AI_MAX_TOKENS} único para tudo. Cada chamada leva o seu no {@code num_predict} (Ollama)
   * ou {@code max_tokens} (API compatível com a da OpenAI). São também a saída da reserva da cota
   * (#166, {@link Cota#tetoDaPergunta}); o {@code AI_MAX_TOKENS} ficou só como padrão do provedor
   * para chamada sem teto próprio, que hoje não existe.
   *
   * <p>Não há teto separado para a {@code perguntaLei} do MISTO: ela é um campo do mesmo JSON da
   * classificação, na mesma chamada, e no desempate o modelo também pode responder MISTO com ela.
   * Um teto menor no desempate a cortaria.
   *
   * <p>Resposta cortada pelo teto nunca é entregue como se estivesse completa: quem chama confere o
   * motivo do fim ({@code ModeloLinguagem.cortadaPeloTeto}).
   *
   * <p>Com o raciocínio ligado ({@code AI_OLLAMA_THINK} diferente de {@code false}, ou a tag
   * {@code qwen3:4b}, que pensa sempre), os tokens de raciocínio contam no teto e estes padrões
   * cortam quase toda chamada (desfecho {@code RESPOSTA_CORTADA}): suba os tetos junto.
   *
   * <p>O {@code contexto} vem do {@code num-ctx} do Ollama; com provedor por API ele só limita os
   * tetos a 4.096 (metade do padrão), o que nenhuma chamada da Ceres precisa passar.
   */
  public static class Tetos {

    /** Menor teto aceito: abaixo disso nem o JSON da classificação cabe. */
    public static final int MINIMO = 16;

    /**
     * Menor contexto aceito: o maior prompt estimado (rota de dados: parte fixa, pergunta e
     * template, até ~4.900 tokens) mais o teto de saída das funções (512), arredondado. Abaixo
     * disso o Ollama descartaria o início do prompt, onde ficam as regras e as funções.
     */
    public static final int CONTEXTO_MINIMO = 6144;

    /**
     * Escolha das funções de dados: só as chamadas de função, em JSON. Cada uma tem algumas dezenas
     * de tokens; 512 cabe meia dúzia com folga.
     */
    private int funcoes = 512;

    /**
     * Explicação depois da resposta de dados (#268): 2 ou 3 frases, até
     * {@code app.assistente.explicacao.max-caracteres} caracteres. Fica fora de
     * {@link Cota#tetoDaPergunta}: a explicação tem reserva própria ({@link Cota#tetoDaExplicacao}).
     */
    private int explicacao = 256;

    /** Classificação: JSON com intenção, confiança e {@code perguntaLei} (até 300 caracteres). */
    private int classificacao = 256;

    /**
     * Lei: texto curto com as marcas de citação. Padrão = o teto único de antes (conservador, sem
     * medição); o candidato menor, a medir na VPS, está em {@code docs/ceres-assistente.md}.
     */
    private int legislacao = 1024;

    /**
     * Juiz da fidelidade da lei (#269, camada 2): um veredito curto por frase em JSON. Entra na
     * reserva da pergunta só com o juiz ativo ({@link Cota#tetoDoJuiz}).
     */
    private int juiz = 128;

    /**
     * Janela de contexto do modelo (o {@code num-ctx} do Ollama, lido da mesma propriedade). Só
     * para a validação: nenhum teto pode comer mais da metade dela, que é do prompt.
     */
    private int contexto = 8192;

    void validar() {
      if (contexto < CONTEXTO_MINIMO) {
        throw new IllegalStateException("app.assistente.tetos.contexto deve ser >= "
            + CONTEXTO_MINIMO + ", recebido: " + contexto);
      }
      exigirTeto("funcoes", funcoes);
      exigirTeto("explicacao", explicacao);
      exigirTeto("classificacao", classificacao);
      exigirTeto("legislacao", legislacao);
      exigirTeto("juiz", juiz);
    }

    private void exigirTeto(String nome, int valor) {
      if (valor < MINIMO || valor > contexto / 2) {
        throw new IllegalStateException("app.assistente.tetos." + nome + " deve estar entre "
            + MINIMO + " e a metade do contexto (" + contexto / 2 + "), recebido: " + valor);
      }
    }

    public int getFuncoes() {
      return funcoes;
    }

    public void setFuncoes(int funcoes) {
      this.funcoes = funcoes;
    }

    public int getExplicacao() {
      return explicacao;
    }

    public void setExplicacao(int explicacao) {
      this.explicacao = explicacao;
    }

    public int getClassificacao() {
      return classificacao;
    }

    public void setClassificacao(int classificacao) {
      this.classificacao = classificacao;
    }

    public int getLegislacao() {
      return legislacao;
    }

    public int getJuiz() {
      return juiz;
    }

    public void setJuiz(int juiz) {
      this.juiz = juiz;
    }

    public void setLegislacao(int legislacao) {
      this.legislacao = legislacao;
    }

    public int getContexto() {
      return contexto;
    }

    public void setContexto(int contexto) {
      this.contexto = contexto;
    }
  }

  /**
   * Explicação do modelo depois da resposta de dados (#268, com o que era da #415): 2 ou 3 frases
   * curtas que dizem ao produtor o que o número quer dizer, conferidas em código pelo
   * {@code VerificadorFidelidade}. Qualquer falha descarta a explicação e a resposta do template
   * sai sozinha.
   *
   * <p><strong>Desligada por padrão.</strong> Com ela desligada a Ceres responde exatamente como
   * antes: nenhuma chamada a mais, nenhum evento a mais, o mesmo JSON. Ligar exige a medição com o
   * modelo real (taxa de descarte, tempo extra, exemplos) e o OK do Murillo (critérios da #415).
   */
  public static class Explicacao {

    /** Liga a explicação para todos os produtores ({@code AI_EXPLICACAO_HABILITADA}). */
    private boolean enabled;

    /**
     * Prazo da explicação, depois de a resposta ficar pronta. Nunca passa do que resta da
     * pergunta ({@code timeout-pergunta}): a resposta do template nunca vira tempo esgotado por
     * causa da explicação.
     */
    private Duration timeout = Duration.ofSeconds(8);

    /** Frases da explicação, no mínimo. */
    private int minFrases = 2;

    /** Frases da explicação, no máximo. */
    private int maxFrases = 3;

    /** Caracteres da explicação, no mínimo (uma frase solta não explica nada). */
    private int minCaracteres = 40;

    /** Caracteres da explicação, no máximo (três frases curtas). */
    private int maxCaracteres = 480;

    void validar(Duration timeoutPergunta) {
      exigirPositivo(timeout, "app.assistente.explicacao.timeout");
      // So ligada: os testes com timeout-pergunta curto sobem com a explicacao desligada.
      if (enabled && timeout.compareTo(timeoutPergunta) >= 0) {
        throw new IllegalStateException("app.assistente.explicacao.timeout (" + timeout
            + ") deve ser menor que app.assistente.timeout-pergunta (" + timeoutPergunta + ")");
      }
      if (minFrases < 1 || maxFrases < minFrases || maxFrases > 10) {
        throw new IllegalStateException("app.assistente.explicacao: min-frases >= 1 e "
            + "min-frases <= max-frases <= 10, recebido: " + minFrases + " e " + maxFrases);
      }
      if (minCaracteres < 1 || maxCaracteres <= minCaracteres || maxCaracteres > 2000) {
        throw new IllegalStateException("app.assistente.explicacao: min-caracteres >= 1 e "
            + "min-caracteres < max-caracteres <= 2000, recebido: " + minCaracteres + " e "
            + maxCaracteres);
      }
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public int getMinFrases() {
      return minFrases;
    }

    public void setMinFrases(int minFrases) {
      this.minFrases = minFrases;
    }

    public int getMaxFrases() {
      return maxFrases;
    }

    public void setMaxFrases(int maxFrases) {
      this.maxFrases = maxFrases;
    }

    public int getMinCaracteres() {
      return minCaracteres;
    }

    public void setMinCaracteres(int minCaracteres) {
      this.minCaracteres = minCaracteres;
    }

    public int getMaxCaracteres() {
      return maxCaracteres;
    }

    public void setMaxCaracteres(int maxCaracteres) {
      this.maxCaracteres = maxCaracteres;
    }
  }

  /**
   * Conferência de fidelidade da rota de lei (#269): o modelo responde em JSON, cada frase com a
   * passagem do trecho que a sustenta, e o {@code ConferenciaFidelidadeLei} confere em código a
   * cópia, o número, a negação e a cobertura de cada frase e põe a citação no nível do dispositivo
   * em que a passagem está (o caput, quando a regra está no caput). Reprovada, o texto do modelo sai
   * e ficam os trechos, sem afirmar que respondem.
   *
   * <p><strong>Desligada por padrão.</strong> Com ela desligada a rota de lei é a de antes: o mesmo
   * prompt, nenhuma conferência a mais. Ligar exige a medição no conjunto novo e o OK do Murillo.
   */
  public static class FidelidadeLei {

    /** Liga a conferência para todas as perguntas de lei ({@code AI_FIDELIDADE_LEI_HABILITADA}). */
    private boolean enabled;

    /**
     * Fração mínima (maior que 0, no máximo 1) das palavras de conteúdo de cada frase que têm de
     * aparecer nas passagens copiadas dela ({@code AI_FIDELIDADE_LEI_COBERTURA_MINIMA}).
     */
    private double coberturaMinima = 0.7;

    /**
     * A mesma fração com o juiz ativo ({@code AI_FIDELIDADE_LEI_COBERTURA_MINIMA_COM_JUIZ}): um
     * piso baixo, para a paráfrase em linguagem simples passar; quem decide o sentido passa a ser o
     * juiz. Literalidade, número e polaridade seguem eliminatórios em código.
     */
    private double coberturaMinimaComJuiz = 0.3;

    /** Camada 2, o juiz. */
    private Juiz juiz = new Juiz();

    /**
     * Juiz da fidelidade da lei (#269, camada 2): uma chamada ao modelo por resposta aprovada pela
     * camada 1, com as frases e as cópias; qualquer frase sem {@code SUSTENTA} reprova. Desligado
     * por padrão, e sem efeito com a camada 1 desligada.
     */
    public static class Juiz {

      /** Liga o juiz ({@code AI_FIDELIDADE_LEI_JUIZ_HABILITADO}). */
      private boolean enabled;

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }
    }

    /** A cobertura mínima em vigor: a com o juiz, quando ele está ligado. */
    public double coberturaEmVigor() {
      return juiz.isEnabled() ? coberturaMinimaComJuiz : coberturaMinima;
    }

    void validar() {
      exigirFracao("cobertura-minima", coberturaMinima);
      exigirFracao("cobertura-minima-com-juiz", coberturaMinimaComJuiz);
    }

    private static void exigirFracao(String nome, double valor) {
      if (!(valor > 0 && valor <= 1)) {
        throw new IllegalStateException("app.assistente.fidelidade-lei." + nome + " deve estar"
            + " entre 0 (exclusive) e 1, recebido: " + valor);
      }
    }

    public double getCoberturaMinimaComJuiz() {
      return coberturaMinimaComJuiz;
    }

    public void setCoberturaMinimaComJuiz(double coberturaMinimaComJuiz) {
      this.coberturaMinimaComJuiz = coberturaMinimaComJuiz;
    }

    public Juiz getJuiz() {
      return juiz;
    }

    public void setJuiz(Juiz juiz) {
      this.juiz = juiz;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public double getCoberturaMinima() {
      return coberturaMinima;
    }

    public void setCoberturaMinima(double coberturaMinima) {
      this.coberturaMinima = coberturaMinima;
    }
  }

  /** Modo fallback, sem modelo (issue #146). */
  public static class Fallback {

    /**
     * Depois de uma falha do modelo, por quanto tempo as perguntas vão direto ao fallback, sem
     * tentar o modelo de novo. Sem a pausa, cada pergunta esperaria a mesma falha (um timeout de
     * leitura leva até 2 minutos) antes de receber os trechos.
     */
    private Duration pausaAposFalha = Duration.ofSeconds(30);

    /** Quantos trechos de lei a busca textual devolve. */
    private int maxTrechos = 3;

    /**
     * Tamanho máximo de cada trecho no texto da resposta (o texto completo segue na citação). Um
     * artigo longo inteiro no chat esconderia os outros trechos.
     */
    private int tamanhoMaximoTrecho = 600;

    public Duration getPausaAposFalha() {
      return pausaAposFalha;
    }

    public void setPausaAposFalha(Duration pausaAposFalha) {
      this.pausaAposFalha = pausaAposFalha;
    }

    public int getMaxTrechos() {
      return maxTrechos;
    }

    public void setMaxTrechos(int maxTrechos) {
      this.maxTrechos = maxTrechos;
    }

    public int getTamanhoMaximoTrecho() {
      return tamanhoMaximoTrecho;
    }

    public void setTamanhoMaximoTrecho(int tamanhoMaximoTrecho) {
      this.tamanhoMaximoTrecho = tamanhoMaximoTrecho;
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

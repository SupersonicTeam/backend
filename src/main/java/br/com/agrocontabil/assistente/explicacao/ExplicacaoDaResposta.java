package br.com.agrocontabil.assistente.explicacao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.auditoria.AuditoriaAssistente;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.assistente.porta.CotaUsoPorta;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoExplicacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A explicação do modelo depois da resposta de dados (#268, com o que era da #415), em três fases,
 * para a {@code AssistenteService} só interromper a thread durante a chamada ao modelo:
 * <ol>
 *   <li>{@link #preparar}: confere se a explicação se aplica, se ainda há tempo e se o modelo está
 *       no ar, e reserva a cota dela, <strong>à parte da pergunta</strong>: sem saldo, a resposta
 *       sai sozinha e não há 429;</li>
 *   <li>{@link Pedido#chamar}: a chamada ao modelo e a conferência, na janela em que a thread pode
 *       ser interrompida (prazo da explicação ou cliente que saiu). Nunca lança;</li>
 *   <li>{@link #concluir}: acerta a cota pelo uso real (chamada que terminou) ou a estorna,
 *       completa o registro da auditoria com o desfecho (sem o texto, #144) e devolve o texto
 *       aceito. Sem o registro gravado, a explicação não é entregue (RF020). Nunca lança.</li>
 * </ol>
 *
 * <p>Só se aplica com a flag ligada ({@code app.assistente.explicacao.enabled}) e à resposta de
 * dados aceita, sem citação de lei (o tratamento do produto e a retenção trazem texto legal do
 * backend), fora do modo fallback e numa pergunta que reservou cota para o modelo (o atalho fixo
 * da tela não chama o modelo, #343). Fora disso, a coluna da auditoria fica nula.
 */
@Component
public class ExplicacaoDaResposta {

  private static final Logger log = LoggerFactory.getLogger(ExplicacaoDaResposta.class);

  private final boolean habilitada;
  private final Duration timeout;
  private final ExplicadorResposta explicador;
  private final CotaUsoPorta cota;
  private final AuditoriaAssistente auditoria;
  private final ModeloLinguagem modelo;
  private final DisponibilidadeModelo disponibilidade;

  /** Constrói a etapa com a flag e o prazo de {@code app.assistente.explicacao}. */
  @Autowired
  public ExplicacaoDaResposta(ExplicadorResposta explicador, CotaUsoPorta cota,
      AuditoriaAssistente auditoria, ModeloLinguagem modelo,
      DisponibilidadeModelo disponibilidade, AssistenteProperties propriedades) {
    this(propriedades.getExplicacao().isEnabled(), propriedades.getExplicacao().getTimeout(),
        explicador, cota, auditoria, modelo, disponibilidade);
  }

  private ExplicacaoDaResposta(boolean habilitada, Duration timeout,
      ExplicadorResposta explicador, CotaUsoPorta cota, AuditoriaAssistente auditoria,
      ModeloLinguagem modelo, DisponibilidadeModelo disponibilidade) {
    this.habilitada = habilitada;
    this.timeout = timeout;
    this.explicador = explicador;
    this.cota = cota;
    this.auditoria = auditoria;
    this.modelo = modelo;
    this.disponibilidade = disponibilidade;
  }

  /** A etapa desligada: nunca se aplica (testes do serviço sem a explicação). */
  public static ExplicacaoDaResposta desligada() {
    return new ExplicacaoDaResposta(false, Duration.ofSeconds(1), null, null, null, null, null);
  }

  /** Prazo configurado da explicação ({@code app.assistente.explicacao.timeout}). */
  public Duration timeout() {
    return timeout;
  }

  /**
   * A explicação se aplica a esta resposta: flag ligada, rota de dados com a resposta aceita, sem
   * citação de lei, fora do modo fallback, pergunta que reservou cota para o modelo e não
   * interrompida.
   *
   * @param resultado resultado da orquestração, já auditado
   * @param contexto  contexto da pergunta
   * @return se a explicação deve ser tentada
   */
  public boolean seAplica(ResultadoOrquestracao resultado, ContextoAssistente contexto) {
    return habilitada && resultado != null
        && resultado.rota() == Intencao.DADOS
        && resultado.exibirFontes()
        && !resultado.modoFallback()
        && resultado.citacoes().isEmpty()
        && resultado.texto() != null && !resultado.texto().isBlank()
        && contexto.reservouParaOModelo()
        && contexto.interrupcao().isEmpty();
  }

  /**
   * Fase 1: confere tempo, modelo e saldo e reserva a cota da explicação. Quando não segue,
   * registra o motivo na auditoria (sem tempo, modelo em pausa, sem saldo).
   *
   * @param resultado   resultado da orquestração, já auditado
   * @param contexto    contexto da pergunta
   * @param interacaoId registro da pergunta na auditoria
   * @param prazo       tempo disponível para a explicação (o menor entre o dela e o que resta da
   *                    pergunta)
   * @return o pedido pronto para a chamada, ou vazio
   */
  public Optional<Pedido> preparar(ResultadoOrquestracao resultado, ContextoAssistente contexto,
      UUID interacaoId, Duration prazo) {
    if (!seAplica(resultado, contexto)) {
      return Optional.empty();
    }
    if (interacaoId == null) {
      // Sem o registro da pergunta nao ha onde gravar o desfecho: a explicacao nao sai (RF020).
      log.warn("Ceres: explicacao sem o registro da pergunta na auditoria; nao sera feita");
      return Optional.empty();
    }
    long inicio = System.nanoTime();
    if (prazo == null || prazo.isNegative() || prazo.isZero()) {
      registrar(interacaoId, DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO, inicio,
          null, null);
      return Optional.empty();
    }
    if (!disponibilidade.disponivel()) {
      registrar(interacaoId, DesfechoExplicacao.EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL,
          inicio, null, null);
      return Optional.empty();
    }
    Optional<CotaUsoPorta.ReservaCota> reserva;
    try {
      reserva = cota.reservarExplicacao(contexto.produtorId(), modelo.identificacao());
    } catch (RuntimeException e) {
      log.error("Ceres: falha ao reservar a cota da explicacao: {}", e.getClass().getName());
      registrar(interacaoId, DesfechoExplicacao.EXPLICACAO_DESCARTADA_ERRO, inicio, null, null);
      return Optional.empty();
    }
    if (reserva.isEmpty()) {
      registrar(interacaoId, DesfechoExplicacao.EXPLICACAO_DESCARTADA_SEM_SALDO, inicio, null,
          null);
      return Optional.empty();
    }
    return Optional.of(new Pedido(explicador, contexto, resultado.texto(), interacaoId,
        reserva.get(), inicio));
  }

  /**
   * Fase 3: acerta ou estorna a cota, completa a auditoria e devolve o texto aceito. Chamada com
   * a thread fora da janela de interrupção: a cota e a auditoria precisam da conexão do banco.
   *
   * @param pedido   o pedido da fase 1, já chamado
   * @param esgotada o prazo da explicação venceu
   * @return o texto aceito, num parágrafo; vazio se descartada ou sem o registro gravado
   */
  public Optional<String> concluir(Pedido pedido, boolean esgotada) {
    ContextoAssistente contexto = pedido.contexto;
    DesfechoExplicacao desfecho = pedido.desfecho(esgotada);
    Integer entrada = null;
    Integer saida = null;
    try {
      if (pedido.concluida) {
        // A chamada terminou: o modelo foi usado, aceita ou nao a explicacao (#511: uso real).
        entrada = valor(contexto.tokensEntrada()) - pedido.entradaAntes;
        saida = valor(contexto.tokensSaida()) - pedido.saidaAntes;
        cota.acertarExplicacao(pedido.reserva, entrada, saida, modelo.identificacao());
      } else {
        cota.estornar(pedido.reserva);
      }
    } catch (RuntimeException e) {
      log.error("Falha ao encerrar a cota da explicacao da Ceres: {}", e.getClass().getName());
    }
    if (!registrar(pedido.interacaoId, desfecho, pedido.inicio, entrada, saida)) {
      return Optional.empty();
    }
    return desfecho == DesfechoExplicacao.EXPLICACAO_ACEITA
        ? Optional.of(pedido.explicacao.texto()) : Optional.empty();
  }

  /** Completa a auditoria; falha vai ao log e impede a entrega. */
  private boolean registrar(UUID interacaoId, DesfechoExplicacao desfecho, long inicio,
      Integer entrada, Integer saida) {
    try {
      auditoria.registrarExplicacao(interacaoId, desfecho,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio), entrada, saida);
      return true;
    } catch (RuntimeException e) {
      log.error("Falha ao registrar a explicacao da Ceres na auditoria: {}",
          e.getClass().getName());
      return false;
    }
  }

  private static int valor(Integer tokens) {
    return tokens == null ? 0 : tokens;
  }

  /**
   * Uma explicação em andamento: o estado entre as três fases. A chamada roda numa thread só (a da
   * Ceres); o {@code volatile} cobre a leitura do desfecho por quem venceu o prazo.
   */
  public static final class Pedido {

    private final ExplicadorResposta explicador;
    private final ContextoAssistente contexto;
    private final String template;
    private final UUID interacaoId;
    private final CotaUsoPorta.ReservaCota reserva;
    private final long inicio;
    private final int entradaAntes;
    private final int saidaAntes;
    private volatile ExplicadorResposta.Explicacao explicacao;
    private volatile DesfechoExplicacao falha;
    private volatile boolean concluida;

    private Pedido(ExplicadorResposta explicador, ContextoAssistente contexto, String template,
        UUID interacaoId, CotaUsoPorta.ReservaCota reserva, long inicio) {
      this.explicador = explicador;
      this.contexto = contexto;
      this.template = template;
      this.interacaoId = interacaoId;
      this.reserva = reserva;
      this.inicio = inicio;
      this.entradaAntes = valor(contexto.tokensEntrada());
      this.saidaAntes = valor(contexto.tokensSaida());
    }

    /**
     * Fase 2: a chamada ao modelo e a conferência. Roda na janela em que a thread pode ser
     * interrompida; nunca lança (a resposta já foi entregue e não pode virar erro).
     */
    public void chamar() {
      try {
        explicacao = explicador.explicar(template, contexto);
        concluida = true;
      } catch (PerguntaInterrompidaException e) {
        falha = DesfechoExplicacao.EXPLICACAO_DESCARTADA_CANCELADA;
      } catch (AssistenteIndisponivelException e) {
        falha = DesfechoExplicacao.EXPLICACAO_DESCARTADA_MODELO_INDISPONIVEL;
      } catch (RuntimeException e) {
        log.error("Falha inesperada na explicacao da Ceres: {}", e.getClass().getName());
        falha = DesfechoExplicacao.EXPLICACAO_DESCARTADA_ERRO;
      }
    }

    /**
     * Desfecho final. Prazo vencido vale mais que o resto: a explicação que terminou depois do
     * prazo também é descartada. Interrupção da pergunta (cliente que saiu, prazo da pergunta)
     * vem do contexto.
     */
    private DesfechoExplicacao desfecho(boolean esgotada) {
      Optional<DesfechoInteracao> interrupcao = contexto.interrupcao();
      if (esgotada || interrupcao.filter(DesfechoInteracao.TEMPO_ESGOTADO::equals).isPresent()) {
        return DesfechoExplicacao.EXPLICACAO_DESCARTADA_TEMPO_ESGOTADO;
      }
      if (interrupcao.isPresent()) {
        return DesfechoExplicacao.EXPLICACAO_DESCARTADA_CANCELADA;
      }
      if (explicacao != null) {
        return explicacao.desfecho();
      }
      return falha == null ? DesfechoExplicacao.EXPLICACAO_DESCARTADA_ERRO : falha;
    }
  }
}

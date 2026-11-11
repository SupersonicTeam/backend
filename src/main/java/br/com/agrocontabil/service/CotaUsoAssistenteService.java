package br.com.agrocontabil.service;

import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.CaminhoCota;
import br.com.agrocontabil.exception.LimiteUsoAssistenteException;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cota semanal de créditos da Ceres por titular (issue #166, etapa A do épico de planos).
 *
 * <p>Ciclo de uma pergunta:
 * <ol>
 *   <li><strong>reserva do teto</strong> antes de qualquer chamada ao modelo: o custo máximo
 *       possível da pergunta, derivado dos limites dela ({@link #tetoDaPergunta}). {@code UPDATE}
 *       condicional e atômico na linha da semana — várias perguntas simultâneas do mesmo titular
 *       (contadores de um mesmo escritório, no futuro) não passam do limite. Sem saldo: 429
 *       {@code LIMITE_USO_ASSISTENTE} com {@code Retry-After} até a segunda-feira;</li>
 *   <li><strong>acerto</strong> no fim: debita o consumo real e devolve a diferença;</li>
 *   <li><strong>estorno integral</strong> em falha, timeout ou interrupção.</li>
 * </ol>
 *
 * <p>Cada passo é uma transação curta própria ({@link TransactionTemplate}), nunca aberta durante
 * a chamada ao modelo (regra da issue #157): quem chama reserva, solta a conexão, conversa com o
 * modelo e só então acerta.
 *
 * <p><strong>Semana:</strong> de segunda 00:00 a domingo 23:59:59 em {@code America/Sao_Paulo}. A
 * chave é a data da segunda: não há job de reset, e sobra não acumula. Uma pergunta que atravessa
 * a meia-noite de domingo é acertada na semana em que foi reservada.
 */
@Service
public class CotaUsoAssistenteService {

  private static final Logger log = LoggerFactory.getLogger(CotaUsoAssistenteService.class);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final UsoSemanalAssistenteRepository repository;
  private final PlanoUsoProvider planoProvider;
  private final AssistenteProperties.Cota cota;
  private final AssistenteProperties.Tetos tetos;
  private final Duration timeoutPergunta;
  private final Clock clock;
  private final TransactionTemplate transacao;
  private final TransactionTemplate transacaoNova;

  /** Constrói o serviço sobre a tabela de uso semanal e o provedor de plano. */
  public CotaUsoAssistenteService(UsoSemanalAssistenteRepository repository,
      PlanoUsoProvider planoProvider, AssistenteProperties propriedades, Clock clock,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.planoProvider = planoProvider;
    this.cota = propriedades.getCota();
    this.tetos = propriedades.getTetos();
    this.timeoutPergunta = propriedades.getTimeoutPergunta();
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
    this.transacaoNova = new TransactionTemplate(transactionManager);
    this.transacaoNova.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Reserva o teto de uma pergunta.
   *
   * @param tipo      produtor ou escritório
   * @param titularId titular já autorizado por quem chama
   * @param modelo    identificação do modelo ({@code provedor/modelo}), para o fator
   * @return a reserva (inativa, sem créditos, com a cota desligada)
   * @throws LimiteUsoAssistenteException sem saldo para o teto (429)
   */
  public Reserva reservar(TipoTitularCota tipo, UUID titularId, String modelo) {
    return reservar(tipo, titularId, modelo, CaminhoCota.NORMAL);
  }

  /**
   * Reserva o teto do caminho da pergunta (#499): a continuidade tem caminhos mais baratos
   * (reconferência) e um mais caro (reescrita) que a pergunta comum. O caminho sem modelo não
   * passa por aqui: paga a leitura ({@link #reservarLeitura}, #511).
   *
   * @param tipo      produtor ou escritório
   * @param titularId titular já autorizado por quem chama
   * @param modelo    identificação do modelo (fator)
   * @param caminho   caminho da pergunta
   * @return a reserva
   * @throws IllegalArgumentException com {@link CaminhoCota#SEM_MODELO}
   */
  public Reserva reservar(TipoTitularCota tipo, UUID titularId, String modelo,
      CaminhoCota caminho) {
    if (caminho == CaminhoCota.SEM_MODELO) {
      throw new IllegalArgumentException("Caminho sem modelo reserva a leitura (#511)");
    }
    return reservarTeto(tipo, titularId, tetoDaPergunta(modelo, caminho));
  }

  /**
   * Reserva o custo de leitura de uma pergunta que não chama o modelo (#511): atalho da tela,
   * conversa leve. Toda pergunta tem custo; sem saldo para ele, 429 como as outras.
   *
   * @param tipo          titular
   * @param titularId     titular já autorizado por quem chama
   * @param modelo        identificação do modelo (fator)
   * @param tokensLeitura tokens estimados da mensagem
   * @return a reserva, a ser acertada pelo mesmo custo
   * @throws LimiteUsoAssistenteException sem saldo (429)
   */
  public Reserva reservarLeitura(TipoTitularCota tipo, UUID titularId, String modelo,
      long tokensLeitura) {
    return reservarTeto(tipo, titularId, creditos(tokensLeitura, 0, modelo));
  }

  private Reserva reservarTeto(TipoTitularCota tipo, UUID titularId, long teto) {
    OffsetDateTime agora = OffsetDateTime.now(clock.withZone(ZONE));
    LocalDate semana = semanaDe(agora);
    if (!cota.isHabilitada()) {
      return new Reserva(tipo, titularId, semana, 0, false);
    }
    long limite = planoProvider.planoDe(tipo, titularId).creditosPorSemana();
    garantirLinha(tipo, titularId, semana, agora);
    Integer reservou = transacao.execute(status -> {
      // Reserva presa (processo morto no meio da pergunta, sem chegar ao acerto): toda escrita
      // atualiza atualizado_em, e nenhuma pergunta dura mais que timeout-pergunta. Linha parada ha
      // mais que o dobro com reserva so pode ter reservas mortas; elas voltam ao saldo aqui.
      repository.liberarReservasParadas(
          tipo, titularId, semana, agora.minus(timeoutPergunta.multipliedBy(2)), agora);
      return repository.reservar(tipo, titularId, semana, teto, limite, agora);
    });
    if (reservou == null || reservou == 0) {
      if (teto > limite) {
        log.error("Cota da Ceres: teto de uma pergunta ({}) maior que o plano ({}); revise "
            + "app.assistente.cota", teto, limite);
      }
      throw new LimiteUsoAssistenteException(segundosAteVirada(agora));
    }
    return new Reserva(tipo, titularId, semana, teto, true);
  }

  /**
   * Acerta a pergunta pelo consumo real e devolve a diferença da reserva.
   *
   * @return créditos consumidos (0 com a cota desligada)
   */
  public long acertar(Reserva reserva, long tokensEntrada, long tokensSaida, String modelo) {
    if (!reserva.ativa()) {
      return 0;
    }
    long real = creditos(tokensEntrada, tokensSaida, modelo);
    // A pergunta nunca custa mais que o teto reservado: e o que garante que o titular nao passa
    // do limite da semana. Passar dele quer dizer que os tetos por chamada estao baixos.
    long consumidos = Math.min(real, reserva.creditos());
    if (real > reserva.creditos()) {
      log.warn("Cota da Ceres: consumo real ({}) passou do teto reservado ({}); cobrado o teto. "
          + "Revise app.assistente.cota.teto-tokens-entrada-por-chamada e app.assistente.tetos",
          real, reserva.creditos());
    }
    OffsetDateTime agora = OffsetDateTime.now(clock.withZone(ZONE));
    Integer acertou = transacao.execute(status -> repository.acertar(reserva.tipo(),
        reserva.titularId(), reserva.semana(), reserva.creditos(), consumidos,
        Math.max(0, tokensEntrada), Math.max(0, tokensSaida), agora));
    if (acertou == null || acertou == 0) {
      log.warn("Cota da Ceres: acerto sem reserva correspondente (acerto ou estorno repetido)");
    }
    return consumidos;
  }

  /** Estorno integral da reserva (falha, timeout, interrupção). */
  public void estornar(Reserva reserva) {
    if (!reserva.ativa()) {
      return;
    }
    OffsetDateTime agora = OffsetDateTime.now(clock.withZone(ZONE));
    Integer estornou = transacao.execute(status -> repository.estornar(reserva.tipo(),
        reserva.titularId(), reserva.semana(), reserva.creditos(), agora));
    if (estornou == null || estornou == 0) {
      log.warn("Cota da Ceres: estorno sem reserva correspondente (acerto ou estorno repetido)");
    }
  }

  /**
   * Uso da semana corrente, para a barra de uso do chat. Só créditos, nunca tokens.
   *
   * @param tipo      produtor ou escritório
   * @param titularId titular já autorizado por quem chama
   */
  public Uso consultar(TipoTitularCota tipo, UUID titularId) {
    OffsetDateTime agora = OffsetDateTime.now(clock.withZone(ZONE));
    LocalDate semana = semanaDe(agora);
    PlanoUsoProvider.PlanoUso plano = planoProvider.planoDe(tipo, titularId);
    OffsetDateTime renovaEm = semana.plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();
    UsoSemanalAssistente uso = transacao.execute(status -> repository
        .findById(new UsoSemanalAssistenteId(tipo, titularId, semana)).orElse(null));
    long consumidos = uso == null ? 0 : uso.getCreditosConsumidos();
    // Reserva de linha parada e reserva morta (ver liberarReservasParadas): nao desconta.
    boolean parada = uso != null
        && uso.getAtualizadoEm().isBefore(agora.minus(timeoutPergunta.multipliedBy(2)));
    long reservados = uso == null || parada ? 0 : uso.getCreditosReservados();
    long restantes = Math.max(0, plano.creditosPorSemana() - consumidos - reservados);
    return new Uso(plano.nome(), cota.isHabilitada(), plano.creditosPorSemana(), consumidos,
        restantes, renovaEm, percentualUsado(consumidos, plano.creditosPorSemana(), restantes));
  }

  /**
   * Percentual da semana já usado, a única forma de uso que a tela mostra (#511, decisão do
   * Murillo de 2026-11-11: sempre arredondado, nunca em créditos, como o Claude e outras IAs).
   * {@code ⌈consumidos ÷ creditosSemana × 100⌉}, para qualquer uso aparecer como pelo menos 1%;
   * teto de 99 enquanto houver saldo; 100 só com {@code restantes} = 0; 0 só sem uso nenhum.
   *
   * @param consumidos     créditos gastos na semana
   * @param creditosSemana limite da semana
   * @param restantes      créditos ainda disponíveis
   * @return inteiro de 0 a 100
   */
  static int percentualUsado(long consumidos, long creditosSemana, long restantes) {
    if (restantes <= 0) {
      return 100;
    }
    if (consumidos <= 0 || creditosSemana <= 0) {
      return 0;
    }
    long percentual = (consumidos * 100 + creditosSemana - 1) / creditosSemana;
    return (int) Math.max(1, Math.min(99, percentual));
  }

  /**
   * Créditos de um consumo:
   * {@code ⌈(entrada × pesoEntrada + saída × pesoSaida) × fator ÷ tokensPorCredito⌉}. Consumo
   * zero (modo fallback, sem modelo) custa zero.
   */
  public long creditos(long tokensEntrada, long tokensSaida, String modelo) {
    return cota.creditos(tokensEntrada, tokensSaida, fator(modelo));
  }

  /**
   * Custo máximo possível de uma pergunta: as três chamadas que ela pode fazer (classificação,
   * funções de dados e lei), cada uma com o teto de entrada e o próprio teto de saída
   * ({@code app.assistente.tetos}, #270). A fórmula é a de {@link AssistenteProperties.Cota}, a
   * mesma que a validação da subida usa.
   */
  public long tetoDaPergunta(String modelo) {
    return cota.tetoDaPergunta(tetos, fator(modelo));
  }

  /**
   * Teto de uma pergunta pelo caminho dela (#499).
   *
   * @param modelo  identificação do modelo
   * @param caminho caminho da pergunta
   * @return créditos
   */
  public long tetoDaPergunta(String modelo, CaminhoCota caminho) {
    return cota.tetoDaPergunta(tetos, fator(modelo), caminho);
  }

  private double fator(String modelo) {
    Double fator = modelo == null ? null : cota.getFatorPorModelo().get(modelo);
    return fator == null ? cota.getFatorPadrao() : fator;
  }

  /** Segunda-feira da semana do instante, em Brasília. */
  static LocalDate semanaDe(OffsetDateTime instante) {
    return instante.atZoneSameInstant(ZONE).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  private static long segundosAteVirada(OffsetDateTime agora) {
    OffsetDateTime virada = semanaDe(agora).plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();
    return Math.max(1, Duration.between(agora, virada).toSeconds());
  }

  /**
   * Cria a linha da semana se ainda não existe, em transação própria. Duas perguntas simultâneas
   * podem tentar ao mesmo tempo: a segunda recebe violação de chave (a linha é inserida, nunca
   * mesclada — ver {@link UsoSemanalAssistente#isNew()}) e segue, porque a linha já existe.
   */
  private void garantirLinha(
      TipoTitularCota tipo, UUID titularId, LocalDate semana, OffsetDateTime agora) {
    UsoSemanalAssistenteId id = new UsoSemanalAssistenteId(tipo, titularId, semana);
    Boolean existe = transacao.execute(status -> repository.existsById(id));
    if (Boolean.TRUE.equals(existe)) {
      return;
    }
    try {
      transacaoNova.executeWithoutResult(status -> repository.saveAndFlush(
          UsoSemanalAssistente.builder().id(id).atualizadoEm(agora).build()));
    } catch (DataIntegrityViolationException e) {
      log.debug("Linha de uso semanal criada por outra pergunta ao mesmo tempo");
    }
  }

  /**
   * Reserva de uma pergunta.
   *
   * @param tipo      titular
   * @param titularId titular
   * @param semana    segunda-feira da semana em que foi reservada (o acerto vai para ela)
   * @param creditos  teto reservado
   * @param ativa     {@code false} com a cota desligada: acerto e estorno não fazem nada
   */
  public record Reserva(
      TipoTitularCota tipo, UUID titularId, LocalDate semana, long creditos, boolean ativa) {}

  /**
   * Uso da semana mostrado ao usuário (só créditos).
   *
   * @param plano          nome do plano
   * @param habilitada     {@code false} com a cota desligada (nada é bloqueado)
   * @param creditosSemana limite da semana
   * @param consumidos     créditos já gastos
   * @param restantes      o que ainda cabe (descontadas as perguntas em andamento)
   * @param renovaEm       próxima segunda 00:00 em Brasília
   * @param percentualUsado percentual da semana usado, de 0 a 100 (o que a tela mostra, #511)
   */
  public record Uso(String plano, boolean habilitada, long creditosSemana, long consumidos,
      long restantes, OffsetDateTime renovaEm, int percentualUsado) {}
}

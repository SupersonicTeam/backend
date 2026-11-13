package br.com.agrocontabil.config.demonstracao;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.CaminhoCota;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.UsoSemanalAssistenteRepository;
import br.com.agrocontabil.service.CotaUsoAssistenteService;
import br.com.agrocontabil.service.PlanoUsoProvider;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deixa a cota semanal da Ceres (issue #166) de um produtor da demonstração com saldo para
 * <strong>uma</strong> pergunta só (issue #315): a primeira responde, a segunda recebe 429
 * {@code LIMITE_USO_ASSISTENTE} com o {@code Retry-After} da virada da semana.
 *
 * <p>O saldo fica em {@code teto da pergunta + FOLGA_LEITURAS} (#511, 1 crédito por token): a
 * reserva do teto passa mesmo depois de algumas saudações ou atalhos (que pagam só a leitura), e o
 * consumo real de uma pergunta de modelo, de milhares de créditos, deixa menos que um teto. No
 * modo fallback (modelo fora) a pergunta paga só a leitura e o limite não aparece, como no uso
 * real.
 *
 * <p>A cota zera toda segunda-feira (Brasília), então o cenário é reposto pelo job a cada semana e
 * pelo seed na subida. Só sobe o consumo (nunca o baixa), para não devolver crédito gasto de verdade
 * durante um ensaio. Sem o produtor no banco, não faz nada.
 */
@Component
@ConditionalOnProperty(name = "app.demonstracao.habilitada", havingValue = "true")
public class CotaDemonstracaoCeres {

  /**
   * Folga sobre o teto de uma pergunta de modelo (#511): toda pergunta tem custo, e a leitura de
   * um "olá" ou de um atalho da tela (1 crédito por token, poucos tokens) não pode tirar a
   * primeira pergunta de modelo do Rogério. Uma pergunta de modelo consome milhares de créditos,
   * então a segunda continua recebendo 429.
   */
  static final long FOLGA_LEITURAS = 1000;

  /** Nome do lock distribuído (ShedLock, #87). */
  public static final String LOCK = "demonstracao-cota-ceres";

  private static final Logger log = LoggerFactory.getLogger(CotaDemonstracaoCeres.class);

  private final ProdutorRepository produtorRepository;
  private final UsoSemanalAssistenteRepository usoRepository;
  private final PlanoUsoProvider planoProvider;
  private final CotaUsoAssistenteService cotaService;
  private final ModeloLinguagem modelo;
  private final Clock clock;
  private final boolean continuidade;

  /** Construído pelo Spring. */
  public CotaDemonstracaoCeres(ProdutorRepository produtorRepository,
      UsoSemanalAssistenteRepository usoRepository, PlanoUsoProvider planoProvider,
      CotaUsoAssistenteService cotaService, ModeloLinguagem modelo, Clock clock,
      AssistenteProperties propriedades) {
    this.continuidade = propriedades.getContinuidade().isHabilitada();
    this.produtorRepository = produtorRepository;
    this.usoRepository = usoRepository;
    this.planoProvider = planoProvider;
    this.cotaService = cotaService;
    this.modelo = modelo;
    this.clock = clock;
  }

  /** Repõe o cenário logo depois da virada da semana. */
  @Scheduled(cron = "${app.demonstracao.cota.cron:0 5 0 * * MON}", zone = "America/Sao_Paulo")
  @SchedulerLock(name = LOCK, lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
  public void aoVirarASemana() {
    prepararSemana();
  }

  /**
   * Sobe o consumo da semana corrente do produtor da demonstração até sobrar uma pergunta.
   *
   * @return créditos que restam na semana, ou vazio sem o produtor no banco
   */
  public Optional<Long> prepararSemana() {
    String documento = CenarioDemonstracao.cliente(CenarioDemonstracao.CHAVE_COTA_NO_FIM)
        .documento();
    Optional<Produtor> produtor = produtorRepository.findByCpfCnpj(documento);
    if (produtor.isEmpty()) {
      return Optional.empty();
    }
    OffsetDateTime agora = OffsetDateTime.now(clock);
    LocalDate semana = agora.atZoneSameInstant(SeedDemonstracao.ZONA).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    long limite = planoProvider.planoDe(TipoTitularCota.PRODUTOR, produtor.get().getId())
        .creditosPorSemana();
    // Com a continuidade ligada (#499), o teto e o da pergunta com reescrita, o mais caro: sem
    // isso, a conta "cota no fim" receberia 429 na primeira reescrita.
    long tetoComum = cotaService.tetoDaPergunta(modelo.identificacao());
    long teto = continuidade
        ? cotaService.tetoDaPergunta(modelo.identificacao(), CaminhoCota.REESCRITA) : tetoComum;
    long alvo = Math.max(0, limite - teto - FOLGA_LEITURAS);
    long alvoAntigo = Math.max(0, limite - tetoComum - FOLGA_LEITURAS);

    UsoSemanalAssistenteId id =
        new UsoSemanalAssistenteId(TipoTitularCota.PRODUTOR, produtor.get().getId(), semana);
    UsoSemanalAssistente uso = usoRepository.findById(id).orElseGet(() -> UsoSemanalAssistente
        .builder().id(id).build());
    long consumidos = uso.getCreditosConsumidos();
    if (continuidade && alvo < alvoAntigo && consumidos <= alvoAntigo
        && consumidos >= alvoAntigo - FOLGA_LEITURAS) {
      // Semana preparada com o teto de antes da continuidade: passa ao alvo novo. So o valor que
      // o proprio cenario gravou desce (ou o de antes da #511, convertido x 1000 pelo changeSet
      // 0060, que cai nessa faixa); consumo real nunca e devolvido.
      uso.setCreditosConsumidos(alvo);
      uso.setAtualizadoEm(agora);
      usoRepository.save(uso);
    } else if (uso.getCreditosConsumidos() < alvo) {
      uso.setCreditosConsumidos(alvo);
      // Perguntas e tokens de uma semana movimentada: a tela de uso mostra o histórico coerente.
      uso.setPerguntas(Math.max(uso.getPerguntas(), 27));
      // 1 credito por token ponderado (#511): entrada + 4 x saida = os creditos consumidos.
      uso.setTokensEntrada(Math.max(uso.getTokensEntrada(), alvo * 65 / 100));
      uso.setTokensSaida(Math.max(uso.getTokensSaida(), alvo * 35 / 400));
      uso.setAtualizadoEm(agora);
      usoRepository.save(uso);
    }
    long restantes = limite - uso.getCreditosConsumidos() - uso.getCreditosReservados();
    log.info("Demonstracao: cota da Ceres preparada para a semana de {} ({} creditos restantes, "
        + "teto de uma pergunta {})", semana, restantes, teto);
    return Optional.of(restantes);
  }
}

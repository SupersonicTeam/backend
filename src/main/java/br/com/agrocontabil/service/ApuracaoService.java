package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.ApuracaoNaoReabrivelException;
import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.exception.ReaberturaForaPrazoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serviço de apuração mensal de IBS/CBS do produtor (issue Agrocontabil#41 — RN004).
 *
 * <p>Consolida, por produtor e período mensal ({@code YYYY-MM}, fuso {@code America/Sao_Paulo}), o
 * débito das saídas AUTORIZADAS e o crédito das entradas IMPORTADAS, apurando o
 * <strong>saldo = débito − crédito</strong> (por IBS e por CBS). A entidade
 * {@link ApuracaoMensal} <strong>persiste</strong> esse resultado e seu ciclo de vida
 * ({@link StatusApuracao}); o fechamento é feito pelo job agendado.
 *
 * <p><strong>Reúso da agregação já existente:</strong> os somatórios vêm das queries do
 * {@link NotaFiscalRepository} ({@code somarDebitosPorPeriodo} — saídas AUTORIZADAS;
 * {@code somarCreditosPorPeriodo} — entradas IMPORTADAS). Este serviço <em>não</em> recomputa
 * somas — apenas as consome, seguindo a mesma convenção de recorte mensal do
 * {@link CreditoService}.
 *
 * <p><strong>Recálculo por evento de domínio (issue Agrocontabil#64):</strong> os listeners
 * {@code aoNotaEmitida}/{@code aoNotaCancelada}/{@code aoNotaImportada} reconsolidam o período
 * automaticamente quando uma nota que afeta débito/crédito muda de status (autorização,
 * cancelamento, importação já autorizada), mantendo um período {@code ABERTO} já consolidado
 * sincronizado sem exigir consolidação manual. Um período {@code FECHADO} nunca é recalculado por
 * evento — a correção só ocorre pela reabertura auditada (RN005).
 *
 * <p><strong>Recorte de segurança (anti-IDOR):</strong> a consolidação/consulta sob demanda usa
 * sempre o ID do produtor autenticado ({@link AuthenticatedProdutorProvider}); as agregações
 * filtram por esse ID, de modo que um produtor nunca apura notas de outro. O fechamento agendado
 * roda sem contexto de segurança e opera por ID de produtor do {@link ProdutorRepository}.
 *
 * <p><strong>Fechamento em lote (issue #100):</strong> {@link #fecharPeriodo(String)} e
 * {@link #refecharPeriodosReabertos()} não abrem transação de lote. Leem só IDs, em lotes, e
 * processam cada item numa transação própria ({@code REQUIRES_NEW}): a falha de um produtor faz
 * rollback só dele, é logada e entra no {@link ResumoLoteApuracao}, sem abortar os demais.
 */
@Service
@Transactional
public class ApuracaoService {

  private static final Logger log = LoggerFactory.getLogger(ApuracaoService.class);

  /** Fuso de referência do projeto para o recorte mensal do período. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /** Janela máxima de reabertura retroativa (RN005): 3 meses inclusivos. */
  private static final int MESES_MAX_REABERTURA = 3;

  private final NotaFiscalRepository notaFiscalRepository;
  private final ApuracaoMensalRepository apuracaoRepository;
  private final ProdutorRepository produtorRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final AuditorAware<String> auditorAware;
  private final Clock clock;

  /**
   * Transação própria por item do lote do job (issue #100). {@code REQUIRES_NEW} deixa explícito
   * que cada produtor/apuração commita ou faz rollback sozinho; o timeout é o padrão
   * ({@code spring.transaction.default-timeout}), suficiente para um único produtor.
   */
  private final TransactionTemplate transacaoPorItem;

  /** Quantidade de IDs de produtor lidos por vez no fechamento (issue #100). */
  private final int loteTamanho;

  /**
   * Constrói o serviço com os repositórios de notas/apuração/produtor, o provedor autenticado, o
   * {@link AuditorAware} (identidade de quem reabre), o {@link Clock} (mês de referência da RN005;
   * injetável para tornar determinística a borda de 3 meses nos testes), o gerenciador de
   * transações (transação por item do job) e o tamanho do lote de leitura do fechamento.
   */
  public ApuracaoService(
      NotaFiscalRepository notaFiscalRepository,
      ApuracaoMensalRepository apuracaoRepository,
      ProdutorRepository produtorRepository,
      AuthenticatedProdutorProvider produtorProvider,
      AuditorAware<String> auditorAware,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.apuracao.fechamento.lote-tamanho:100}") int loteTamanho) {
    if (loteTamanho < 1) {
      throw new IllegalArgumentException(
          "app.apuracao.fechamento.lote-tamanho deve ser >= 1, recebido: " + loteTamanho);
    }
    this.notaFiscalRepository = notaFiscalRepository;
    this.apuracaoRepository = apuracaoRepository;
    this.produtorRepository = produtorRepository;
    this.produtorProvider = produtorProvider;
    this.auditorAware = auditorAware;
    this.clock = clock;
    this.transacaoPorItem = new TransactionTemplate(transactionManager);
    this.transacaoPorItem.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.loteTamanho = loteTamanho;
  }

  /**
   * Consolida (upsert) a apuração do produtor autenticado no período, recalculando débito, crédito
   * e saldo a partir das notas do mês, e mantém/deixa o status como {@link StatusApuracao#ABERTO}.
   *
   * @param periodo período no formato {@code YYYY-MM} (ex.: {@code 2026-07})
   * @return apuração consolidada do período
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM} (HTTP 400)
   */
  public ApuracaoResponse consolidar(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    Produtor produtor = produtorProvider.get();
    ApuracaoMensal apuracao =
        consolidarParaProdutor(produtor.getId(), mes, StatusApuracao.ABERTO, null);
    return toResponse(apuracao);
  }

  /**
   * Retorna a apuração do produtor autenticado no período.
   *
   * <p>Se o período <strong>já foi consolidado</strong> (existe {@link ApuracaoMensal} persistida),
   * devolve os valores persistidos <strong>sem recalcular</strong>, preservando status e data de
   * fechamento. Se o período <strong>ainda não foi consolidado</strong> (típico do mês corrente,
   * antes do job de fechamento), projeta os valores <strong>ao vivo</strong> a partir das notas do
   * mês — mesma agregação da consolidação, porém <strong>sem persistir</strong> e sempre em
   * {@link StatusApuracao#ABERTO} (issue Agrocontabil#53 — RN004). Assim o produtor/contador enxerga
   * o débito × crédito parcial do mês em andamento, em vez de zeros.
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return apuração do período (persistida ou projeção ao vivo, não persistida)
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM} (HTTP 400)
   */
  @Transactional(readOnly = true)
  public ApuracaoResponse consultar(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    Produtor produtor = produtorProvider.get();
    return consultarParaProdutor(produtor.getId(), mes.toString());
  }

  /**
   * Mesma consulta de {@link #consultar(String)}, para um produtor já identificado (issue #142 —
   * função {@code consultarApuracao} da Ceres). Existe porque as funções da Ceres não podem ler o
   * {@code SecurityContext}: com streaming elas rodam em outra thread, onde ele chega vazio (#143).
   *
   * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de vir de identidade já
   * resolvida no servidor (o {@code ContextoAssistente} montado a partir do produtor autenticado),
   * nunca de entrada do cliente ou do modelo.
   *
   * @param produtorId produtor da sessão
   * @param periodo    período no formato {@code YYYY-MM}
   * @return apuração do período (persistida ou projeção ao vivo, não persistida)
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM}
   */
  @Transactional(readOnly = true)
  public ApuracaoResponse consultarParaProdutor(UUID produtorId, String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    return apuracaoRepository.findByProdutorIdAndPeriodo(produtorId, mes.toString())
        .map(this::toResponse)
        .orElseGet(() -> projetarAoVivo(produtorId, mes));
  }

  /**
   * Fecha o período de todos os produtores: consolida cada apuração e a marca como
   * {@link StatusApuracao#FECHADO} com {@code dataFechamento}. Usado pelo job agendado.
   *
   * <p><strong>Um produtor por transação (issue #100):</strong> este método não abre transação
   * ({@code NOT_SUPPORTED}). Lê os IDs de produtor em lotes de {@code loteTamanho}, por keyset, e
   * fecha cada produtor numa transação própria. Uma falha (na agregação, na gravação ou no commit)
   * faz rollback só daquele produtor, é logada em {@code ERROR} e entra no resumo; os demais
   * produtores seguem sendo fechados. Se a thread for interrompida (shutdown), para entre dois
   * produtores e devolve o resumo parcial.
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return resumo do lote: apurações fechadas e IDs dos produtores cujo fechamento falhou
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM}
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ResumoLoteApuracao fecharPeriodo(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    PageRequest lote = PageRequest.ofSize(loteTamanho);
    int concluidas = 0;
    List<UUID> falhas = new ArrayList<>();

    List<UUID> ids = produtorRepository.findIdsOrdenados(lote);
    lotes:
    while (!ids.isEmpty()) {
      for (UUID produtorId : ids) {
        if (Thread.currentThread().isInterrupted()) {
          // Shutdown da aplicacao (issue #101): cada produtor ja fechado esta commitado; os
          // restantes ficam ABERTOS e o proximo disparo do job os fecha.
          log.warn("Fechamento interrompido: periodo={} fechadas_ate_aqui={}", mes, concluidas);
          break lotes;
        }
        try {
          transacaoPorItem.executeWithoutResult(status ->
              consolidarParaProdutor(produtorId, mes, StatusApuracao.FECHADO, agora));
          concluidas++;
        } catch (RuntimeException e) {
          falhas.add(produtorId);
          log.error("Falha ao fechar apuracao do produtor; demais produtores seguem: "
              + "periodo={} produtorId={}", mes, produtorId, e);
        }
      }
      if (ids.size() < loteTamanho) {
        break;
      }
      ids = produtorRepository.findIdsOrdenadosApos(ids.get(ids.size() - 1), lote);
    }

    ResumoLoteApuracao resumo = new ResumoLoteApuracao(concluidas, falhas);
    log.info("Fechamento de apuracao concluido: periodo={} fechadas={} falhas={}",
        mes, resumo.concluidas(), resumo.falhas().size());
    return resumo;
  }

  /**
   * Refecha as apurações {@link StatusApuracao#ABERTO} de meses anteriores dentro da janela da
   * RN005 (3 meses retroativos), reconsolidando cada uma e marcando-a como
   * {@link StatusApuracao#FECHADO} (issue Agrocontabil#57).
   *
   * <p><strong>Motivação:</strong> o fechamento agendado ({@link #fecharPeriodo(String)}) cobre
   * apenas o mês corrente. Uma apuração <em>reaberta</em> (RN005) de um mês anterior transita para
   * ABERTO e, sem esta varredura, permaneceria ABERTA indefinidamente. Aqui o job revisita os meses
   * anteriores ainda dentro da janela de reabertura e os refecha automaticamente, reaplicando a
   * mesma consolidação (idempotente) usada no fechamento normal.
   *
   * <p>A faixa varrida é {@code [mesCorrente − 3, mesCorrente)} — exatamente os meses reabríveis
   * pela RN005, excluído o mês corrente (já coberto pelo fechamento normal). Fora dessa janela as
   * apurações não são tocadas. Roda sem contexto de segurança (por produtor da própria apuração),
   * como o demais fechamento agendado.
   *
   * <p><strong>Uma apuração por transação (issue #100):</strong> lê só os IDs e refecha cada
   * apuração numa transação própria, com o mesmo tratamento de falha de
   * {@link #fecharPeriodo(String)}. Cada apuração é relida com lock na própria transação; se foi
   * removida ou já não está ABERTA desde a leitura dos IDs, é ignorada.
   *
   * @return resumo do lote: apurações refechadas e IDs das apurações cujo refechamento falhou
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ResumoLoteApuracao refecharPeriodosReabertos() {
    YearMonth mesCorrente = YearMonth.now(clock);
    YearMonth limiteInferior = mesCorrente.minusMonths(MESES_MAX_REABERTURA);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    List<UUID> ids = apuracaoRepository.findIdsByStatusAndPeriodoEntre(
        StatusApuracao.ABERTO, limiteInferior.toString(), mesCorrente.toString());

    int concluidas = 0;
    List<UUID> falhas = new ArrayList<>();
    for (UUID apuracaoId : ids) {
      if (Thread.currentThread().isInterrupted()) {
        log.warn("Refechamento interrompido: refechadas_ate_aqui={}", concluidas);
        break;
      }
      try {
        Boolean refechada = transacaoPorItem.execute(status -> apuracaoRepository
            .findByIdForUpdate(apuracaoId)
            // Reconfere sob lock: se outra via fechou a apuracao depois da leitura dos IDs, nao
            // reconsolida nem troca a dataFechamento original.
            .filter(apuracao -> apuracao.getStatus() == StatusApuracao.ABERTO)
            .map(apuracao -> {
              aplicarConsolidacao(apuracao, apuracao.getProdutor().getId(),
                  YearMonth.parse(apuracao.getPeriodo()), StatusApuracao.FECHADO, agora);
              return true;
            })
            .orElse(false));
        if (Boolean.TRUE.equals(refechada)) {
          concluidas++;
        }
      } catch (RuntimeException e) {
        falhas.add(apuracaoId);
        log.error("Falha ao refechar apuracao reaberta; demais seguem: apuracaoId={}",
            apuracaoId, e);
      }
    }

    ResumoLoteApuracao resumo = new ResumoLoteApuracao(concluidas, falhas);
    log.info("Refechamento de periodos reabertos concluido: janela=[{},{}) refechadas={} falhas={}",
        limiteInferior, mesCorrente, resumo.concluidas(), resumo.falhas().size());
    return resumo;
  }

  /**
   * Reabre uma apuração FECHADA do produtor autenticado, transitando-a para
   * {@link StatusApuracao#ABERTO} e registrando quem reabriu e quando (auditoria — RN005).
   *
   * <p>Validações, nesta ordem:
   * <ol>
   *   <li><strong>Ownership (anti-IDOR):</strong> busca por {@code (id, produtorId)} — apuração
   *       de outro produtor (ou inexistente) resulta em {@link ResourceNotFoundException} (404),
   *       sem revelar existência.</li>
   *   <li><strong>Precondição de status:</strong> apenas {@link StatusApuracao#FECHADO} é
   *       reabrível — senão {@link ApuracaoNaoReabrivelException} (409).</li>
   *   <li><strong>RN005 — prazo:</strong> o período deve estar dentro dos 3 meses retroativos
   *       (inclusive) em relação ao mês atual ({@code America/Sao_Paulo}) — senão
   *       {@link ReaberturaForaPrazoException} (422).</li>
   * </ol>
   *
   * @param id identificador da apuração
   * @return apuração reaberta ({@code status == ABERTO})
   * @throws ResourceNotFoundException      se inexistente ou de outro produtor (404)
   * @throws ApuracaoNaoReabrivelException  se o status não for FECHADO (409)
   * @throws ReaberturaForaPrazoException   se o período estiver fora do limite de 3 meses (422)
   */
  @Auditado(acao = AcaoAuditada.APURACAO_REABERTA, recurso = RecursoAuditado.APURACAO)
  public ApuracaoResponse reabrir(UUID id) {
    Produtor produtor = produtorProvider.get();
    ApuracaoMensal apuracao = apuracaoRepository
        .findByIdAndProdutorIdForUpdate(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Apuracao", id));

    if (apuracao.getStatus() != StatusApuracao.FECHADO) {
      throw new ApuracaoNaoReabrivelException(id, apuracao.getStatus());
    }

    YearMonth periodo = YearMonth.parse(apuracao.getPeriodo());
    YearMonth periodoLimite = YearMonth.now(clock).minusMonths(MESES_MAX_REABERTURA);
    if (periodo.isBefore(periodoLimite)) {
      throw new ReaberturaForaPrazoException(id, periodo, periodoLimite);
    }

    apuracao.setStatus(StatusApuracao.ABERTO);
    apuracao.setDataFechamento(null);
    apuracao.setReabertoPor(auditorAware.getCurrentAuditor().orElse(null));
    apuracao.setDataReabertura(OffsetDateTime.now(clock));
    ApuracaoMensal salva = apuracaoRepository.save(apuracao);
    log.info("Apuracao reaberta: id={} periodo={} reabertoPor={}",
        salva.getId(), salva.getPeriodo(), salva.getReabertoPor());
    return toResponse(salva);
  }

  /**
   * Recalcula a apuração do produtor quando uma NF-e própria é autorizada (issue Agrocontabil#64).
   *
   * @param evento produtor e período (mês da {@code dataEmissao}) da nota autorizada
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void aoNotaEmitida(NotaEmitidaEvent evento) {
    recalcularSeConsolidado(evento.produtorId(), evento.periodo());
  }

  /**
   * Recalcula a apuração do produtor quando uma NF-e autorizada é cancelada (issue
   * Agrocontabil#64).
   *
   * @param evento produtor e período (mês da {@code dataEmissao} original) da nota cancelada
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void aoNotaCancelada(NotaCanceladaEvent evento) {
    recalcularSeConsolidado(evento.produtorId(), evento.periodo());
  }

  /**
   * Recalcula a apuração do produtor quando um XML de NF-e importado é persistido já autorizado
   * (issue Agrocontabil#64).
   *
   * @param evento produtor e período (mês da {@code dataEmissao}) da nota importada
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void aoNotaImportada(NotaImportadaEvent evento) {
    recalcularSeConsolidado(evento.produtorId(), evento.periodo());
  }

  /**
   * Núcleo comum dos listeners de evento de domínio: se o período já foi consolidado, reconsolida
   * (via {@link #aplicarConsolidacao}, cuja guarda da issue #54 preserva um período já
   * {@link StatusApuracao#FECHADO} sem rebaixá-lo). Se o período ainda não foi consolidado, é
   * no-op — a projeção ao vivo ({@link #consultar(String)}) já reflete o evento a partir das
   * próprias notas, sem necessidade de persistir nada.
   */
  private void recalcularSeConsolidado(UUID produtorId, YearMonth periodo) {
    Optional<ApuracaoMensal> existente =
        apuracaoRepository.findByProdutorIdAndPeriodoForUpdate(produtorId, periodo.toString());
    if (existente.isEmpty()) {
      log.debug("Periodo {} nao consolidado; projecao ao vivo ja reflete o evento (produtorId={})",
          periodo, produtorId);
      return;
    }
    try {
      aplicarConsolidacao(existente.get(), produtorId, periodo, StatusApuracao.ABERTO, null);
      log.info("Apuracao recalculada por evento de dominio: periodo={} produtorId={}",
          periodo, produtorId);
    } catch (ApuracaoPeriodoFechadoException e) {
      log.info(
          "Apuracao FECHADO preservada (nao recalculada) por evento de dominio: "
              + "periodo={} produtorId={}",
          periodo, produtorId);
    }
  }

  /**
   * Núcleo da consolidação: agrega débito/crédito do produtor no mês, calcula o saldo e faz o
   * upsert da {@link ApuracaoMensal} com o status informado.
   *
   * <p><strong>Guarda da RN005 (issue Agrocontabil#54):</strong> se o período já está
   * {@link StatusApuracao#FECHADO} e o status alvo é {@link StatusApuracao#ABERTO}, rejeita com
   * {@link ApuracaoPeriodoFechadoException} (409) <em>antes</em> de agregar ou persistir. Assim a
   * consolidação nunca rebaixa um período fechado por vias transversas — a transição FECHADO →
   * ABERTO só ocorre pela reabertura auditada ({@link #reabrir(UUID)}). O fechamento pelo job (alvo
   * FECHADO) segue permitido, inclusive re-fechando um período já FECHADO.
   */
  private ApuracaoMensal consolidarParaProdutor(
      UUID produtorId, YearMonth mes, StatusApuracao status, OffsetDateTime dataFechamento) {
    ApuracaoMensal apuracao = apuracaoRepository
        .findByProdutorIdAndPeriodoForUpdate(produtorId, mes.toString())
        .orElseGet(() -> ApuracaoMensal.builder()
            .produtor(produtorRepository.getReferenceById(produtorId))
            .periodo(mes.toString())
            .build());
    return aplicarConsolidacao(apuracao, produtorId, mes, status, dataFechamento);
  }

  /**
   * Aplica a consolidação sobre uma {@link ApuracaoMensal} já resolvida (existente ou nova):
   * valida a guarda da RN005, agrega os totais do mês e persiste com o status informado. Extraído
   * para permitir que o refechamento em lote ({@link #refecharPeriodosReabertos()}) reaproveite a
   * entidade carregada por ID, sem um {@code findByProdutorIdAndPeriodo} redundante por apuração.
   */
  private ApuracaoMensal aplicarConsolidacao(
      ApuracaoMensal apuracao, UUID produtorId, YearMonth mes, StatusApuracao status,
      OffsetDateTime dataFechamento) {
    if (apuracao.getStatus() == StatusApuracao.FECHADO && status == StatusApuracao.ABERTO) {
      throw new ApuracaoPeriodoFechadoException(mes.toString());
    }

    Totais totais = agregarTotais(produtorId, mes);

    apuracao.setStatus(status);
    apuracao.setDebitoIbs(totais.debitoIbs());
    apuracao.setDebitoCbs(totais.debitoCbs());
    apuracao.setCreditoIbs(totais.creditoIbs());
    apuracao.setCreditoCbs(totais.creditoCbs());
    apuracao.setSaldoIbs(totais.saldoIbs());
    apuracao.setSaldoCbs(totais.saldoCbs());
    apuracao.setTotalNotas(totais.totalNotas());
    apuracao.setDataFechamento(dataFechamento);

    return apuracaoRepository.save(apuracao);
  }

  /**
   * Projeção ao vivo (não persistida) da apuração do produtor no mês: agrega as notas e devolve o
   * débito × crédito × saldo do período em {@link StatusApuracao#ABERTO}, sem data de fechamento.
   * Usa exatamente a mesma agregação da consolidação ({@link #agregarTotais}), garantindo que a
   * consulta do mês em andamento coincida com o que o fechamento persistiria (issue Agrocontabil#53).
   */
  private ApuracaoResponse projetarAoVivo(UUID produtorId, YearMonth mes) {
    Totais totais = agregarTotais(produtorId, mes);
    return new ApuracaoResponse(
        mes.toString(), StatusApuracao.ABERTO.name(),
        totais.debitoIbs(), totais.debitoCbs(),
        totais.creditoIbs(), totais.creditoCbs(),
        totais.saldoIbs(), totais.saldoCbs(),
        totais.totalNotas(), null,
        mes.toString(), mes.toString(), naoTerminou(mes));
  }

  /**
   * Agrega os somatórios de débito (saídas AUTORIZADAS) e crédito (entradas IMPORTADAS) do produtor
   * na janela {@code [início, fim)} do mês ({@code America/Sao_Paulo}), tratando os nulos do
   * {@code SUM} como {@code 0.00}. Fonte única consumida tanto pela consolidação persistida quanto
   * pela projeção ao vivo, preservando a simetria débito × crédito.
   */
  private Totais agregarTotais(UUID produtorId, YearMonth mes) {
    OffsetDateTime inicio = mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();

    DebitoSaldoAgregado debito =
        notaFiscalRepository.somarDebitosPorPeriodo(produtorId, inicio, fim);
    CreditoSaldoAgregado credito =
        notaFiscalRepository.somarCreditosPorPeriodo(produtorId, inicio, fim);

    return new Totais(
        coalesce(debito.debitoIbs()), coalesce(debito.debitoCbs()),
        coalesce(credito.creditoIbs()), coalesce(credito.creditoCbs()),
        debito.quantidadeNotas() + credito.quantidadeNotas());
  }

  /**
   * Somatórios agregados de um período: débito/crédito de IBS e CBS (já com nulos tratados) e total
   * de notas. O saldo por tributo é derivado ({@code débito − crédito}).
   */
  private record Totais(
      BigDecimal debitoIbs, BigDecimal debitoCbs,
      BigDecimal creditoIbs, BigDecimal creditoCbs, long totalNotas) {

    BigDecimal saldoIbs() {
      return debitoIbs.subtract(creditoIbs);
    }

    BigDecimal saldoCbs() {
      return debitoCbs.subtract(creditoCbs);
    }
  }

  private ApuracaoResponse toResponse(ApuracaoMensal a) {
    return new ApuracaoResponse(
        a.getPeriodo(), a.getStatus().name(),
        a.getDebitoIbs(), a.getDebitoCbs(),
        a.getCreditoIbs(), a.getCreditoCbs(),
        a.getSaldoIbs(), a.getSaldoCbs(),
        a.getTotalNotas(), a.getDataFechamento(),
        a.getPeriodo(), a.getPeriodo(), naoTerminou(YearMonth.parse(a.getPeriodo())));
  }

  /** Mês corrente ou futuro: a apuração ainda pode mudar ({@code parcial}, issue #179). */
  private boolean naoTerminou(YearMonth mes) {
    return !mes.isBefore(YearMonth.now(clock));
  }

  /**
   * Faz o parse do período {@code YYYY-MM} em {@link YearMonth}. Rede de segurança para chamadas
   * diretas ao serviço — a validação de formato do parâmetro já ocorre no controller via Bean
   * Validation ({@code @Pattern}).
   */
  private YearMonth parsePeriodo(String periodo) {
    try {
      return YearMonth.parse(periodo);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new IllegalArgumentException(
          "Periodo invalido: use o formato YYYY-MM (ex.: 2026-07).");
    }
  }

  /** Trata nulo do {@code SUM} (período sem notas) como {@code 0.00} com 2 casas. */
  private BigDecimal coalesce(BigDecimal valor) {
    return (valor != null ? valor : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
  }
}

package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.exception.ApuracaoNaoReabrivelException;
import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.exception.ReaberturaForaPrazoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><strong>Recorte de segurança (anti-IDOR):</strong> a consolidação/consulta sob demanda usa
 * sempre o ID do produtor autenticado ({@link AuthenticatedProdutorProvider}); as agregações
 * filtram por esse ID, de modo que um produtor nunca apura notas de outro. O fechamento agendado
 * roda sem contexto de segurança e opera por ID de produtor do {@link ProdutorRepository}.
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
   * Constrói o serviço com os repositórios de notas/apuração/produtor, o provedor autenticado, o
   * {@link AuditorAware} (identidade de quem reabre) e o {@link Clock} (mês de referência da RN005;
   * injetável para tornar determinística a borda de 3 meses nos testes).
   */
  public ApuracaoService(
      NotaFiscalRepository notaFiscalRepository,
      ApuracaoMensalRepository apuracaoRepository,
      ProdutorRepository produtorRepository,
      AuthenticatedProdutorProvider produtorProvider,
      AuditorAware<String> auditorAware,
      Clock clock) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.apuracaoRepository = apuracaoRepository;
    this.produtorRepository = produtorRepository;
    this.produtorProvider = produtorProvider;
    this.auditorAware = auditorAware;
    this.clock = clock;
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
    return apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), mes.toString())
        .map(this::toResponse)
        .orElseGet(() -> projetarAoVivo(produtor.getId(), mes));
  }

  /**
   * Fecha o período de todos os produtores: consolida cada apuração e a marca como
   * {@link StatusApuracao#FECHADO} com {@code dataFechamento}. Usado pelo job agendado.
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return quantidade de apurações fechadas
   */
  public int fecharPeriodo(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    List<Produtor> produtores = produtorRepository.findAll();
    for (Produtor produtor : produtores) {
      consolidarParaProdutor(produtor.getId(), mes, StatusApuracao.FECHADO, agora);
    }
    log.info("Fechamento de apuracao concluido: periodo={} produtores={}", mes, produtores.size());
    return produtores.size();
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
   * @return quantidade de apurações refechadas
   */
  public int refecharPeriodosReabertos() {
    YearMonth mesCorrente = YearMonth.now(clock);
    YearMonth limiteInferior = mesCorrente.minusMonths(MESES_MAX_REABERTURA);
    OffsetDateTime agora = OffsetDateTime.now(clock);
    List<ApuracaoMensal> reabertas =
        apuracaoRepository.findByStatusAndPeriodoGreaterThanEqualAndPeriodoLessThan(
            StatusApuracao.ABERTO, limiteInferior.toString(), mesCorrente.toString());
    for (ApuracaoMensal apuracao : reabertas) {
      // A apuração já veio carregada da query acima; reaproveita a instância em vez de refazer
      // findByProdutorIdAndPeriodo (evita um round-trip por item no job).
      aplicarConsolidacao(apuracao, apuracao.getProdutor().getId(),
          YearMonth.parse(apuracao.getPeriodo()), StatusApuracao.FECHADO, agora);
    }
    log.info("Refechamento de periodos reabertos concluido: janela=[{},{}) apuracoes={}",
        limiteInferior, mesCorrente, reabertas.size());
    return reabertas.size();
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
  public ApuracaoResponse reabrir(UUID id) {
    Produtor produtor = produtorProvider.get();
    ApuracaoMensal apuracao = apuracaoRepository
        .findByIdAndProdutorId(id, produtor.getId())
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
        .findByProdutorIdAndPeriodo(produtorId, mes.toString())
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
   * entidade já carregada pela consulta, sem um {@code findByProdutorIdAndPeriodo} redundante por
   * apuração.
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
        totais.totalNotas(), null);
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
        a.getTotalNotas(), a.getDataFechamento());
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

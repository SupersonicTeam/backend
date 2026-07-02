package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
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
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final NotaFiscalRepository notaFiscalRepository;
  private final ApuracaoMensalRepository apuracaoRepository;
  private final ProdutorRepository produtorRepository;
  private final AuthenticatedProdutorProvider produtorProvider;

  /** Constrói o serviço com os repositórios de notas/apuração/produtor e o provedor autenticado. */
  public ApuracaoService(
      NotaFiscalRepository notaFiscalRepository,
      ApuracaoMensalRepository apuracaoRepository,
      ProdutorRepository produtorRepository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.apuracaoRepository = apuracaoRepository;
    this.produtorRepository = produtorRepository;
    this.produtorProvider = produtorProvider;
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
   * Retorna a apuração persistida do produtor autenticado no período, <strong>sem recalcular</strong>.
   *
   * <p>Se o período ainda não foi consolidado, devolve uma apuração zerada em
   * {@link StatusApuracao#ABERTO} (projeção transitória, não persistida).
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return apuração do período (persistida ou projeção zerada)
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM} (HTTP 400)
   */
  @Transactional(readOnly = true)
  public ApuracaoResponse consultar(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    Produtor produtor = produtorProvider.get();
    return apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), mes.toString())
        .map(this::toResponse)
        .orElseGet(() -> vaziaAberta(mes));
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
    OffsetDateTime agora = OffsetDateTime.now(ZONE);
    List<Produtor> produtores = produtorRepository.findAll();
    for (Produtor produtor : produtores) {
      consolidarParaProdutor(produtor.getId(), mes, StatusApuracao.FECHADO, agora);
    }
    log.info("Fechamento de apuracao concluido: periodo={} produtores={}", mes, produtores.size());
    return produtores.size();
  }

  /**
   * Núcleo da consolidação: agrega débito/crédito do produtor no mês, calcula o saldo e faz o
   * upsert da {@link ApuracaoMensal} com o status informado.
   */
  private ApuracaoMensal consolidarParaProdutor(
      UUID produtorId, YearMonth mes, StatusApuracao status, OffsetDateTime dataFechamento) {
    OffsetDateTime inicio = mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();

    DebitoSaldoAgregado debito =
        notaFiscalRepository.somarDebitosPorPeriodo(produtorId, inicio, fim);
    CreditoSaldoAgregado credito =
        notaFiscalRepository.somarCreditosPorPeriodo(produtorId, inicio, fim);

    BigDecimal debitoIbs = coalesce(debito.debitoIbs());
    BigDecimal debitoCbs = coalesce(debito.debitoCbs());
    BigDecimal creditoIbs = coalesce(credito.creditoIbs());
    BigDecimal creditoCbs = coalesce(credito.creditoCbs());

    ApuracaoMensal apuracao = apuracaoRepository
        .findByProdutorIdAndPeriodo(produtorId, mes.toString())
        .orElseGet(() -> ApuracaoMensal.builder()
            .produtor(produtorRepository.getReferenceById(produtorId))
            .periodo(mes.toString())
            .build());

    apuracao.setStatus(status);
    apuracao.setDebitoIbs(debitoIbs);
    apuracao.setDebitoCbs(debitoCbs);
    apuracao.setCreditoIbs(creditoIbs);
    apuracao.setCreditoCbs(creditoCbs);
    apuracao.setSaldoIbs(debitoIbs.subtract(creditoIbs));
    apuracao.setSaldoCbs(debitoCbs.subtract(creditoCbs));
    apuracao.setTotalNotas(debito.quantidadeNotas() + credito.quantidadeNotas());
    apuracao.setDataFechamento(dataFechamento);

    return apuracaoRepository.save(apuracao);
  }

  /** Projeção transitória (não persistida) de apuração zerada e aberta para período sem notas. */
  private ApuracaoResponse vaziaAberta(YearMonth mes) {
    BigDecimal zero = coalesce(null);
    return new ApuracaoResponse(mes.toString(), StatusApuracao.ABERTO.name(),
        zero, zero, zero, zero, zero, zero, 0L, null);
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

package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.DashboardResponse.FinanceiroResumo;
import br.com.agrocontabil.dto.DashboardResponse.NotaPorMes;
import br.com.agrocontabil.dto.DashboardResponse.NotaResumo;
import br.com.agrocontabil.mapper.DashboardMapper;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotasPorMesAgregado;
import br.com.agrocontabil.repository.ValorNotasAgregado;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço agregador do dashboard financeiro do produtor (issue Agrocontabil#65 — Sprint 5).
 *
 * <p><strong>Reúso, sem recomputar somas:</strong> os KPIs de IBS/CBS do período vêm do
 * {@link ApuracaoService#consultar(String)} (apuração persistida quando consolidada, projeção ao
 * vivo do mês corrente caso contrário). O volume mensal de notas e as últimas notas vêm de queries
 * do {@link NotaFiscalRepository}. Este serviço apenas <em>compõe</em> essas fontes num único
 * payload — não redefine a semântica de débito/crédito.
 *
 * <p><strong>Recorte de segurança (anti-IDOR):</strong> tanto o {@link ApuracaoService} quanto as
 * queries de nota filtram pelo produtor autenticado ({@link AuthenticatedProdutorProvider}); um
 * produtor nunca enxerga dados de outro. O DTO de saída nunca expõe entidade JPA nem o CPF/CNPJ.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

  /** Fuso de referência do projeto para o recorte mensal, alinhado ao {@link ApuracaoService}. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /** Tamanho da janela da série mensal do dashboard (mês corrente + 5 anteriores). */
  private static final int MESES_SERIE = 6;

  private final ApuracaoService apuracaoService;
  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final Clock clock;

  /**
   * Constrói o serviço com o {@link ApuracaoService} (fonte dos KPIs), o repositório de notas
   * (série mensal e últimas notas), o provedor de produtor autenticado e o {@link Clock} (mês
   * default e janela da série; injetável para determinismo nos testes).
   */
  public DashboardService(
      ApuracaoService apuracaoService,
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      Clock clock) {
    this.apuracaoService = apuracaoService;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.clock = clock;
  }

  /**
   * Monta o dashboard do produtor autenticado para o período informado.
   *
   * @param periodo período no formato {@code YYYY-MM}; quando {@code null}, usa o mês corrente
   *     ({@code America/Sao_Paulo})
   * @return payload agregado do dashboard (KPIs, série mensal, distribuição e últimas notas)
   * @throws IllegalArgumentException se o período informado não estiver no formato {@code YYYY-MM}
   */
  public DashboardResponse montar(String periodo) {
    YearMonth mes = resolverPeriodo(periodo);
    Produtor produtor = produtorProvider.get();

    ApuracaoResponse apuracao = apuracaoService.consultar(mes.toString());

    return new DashboardResponse(
        DashboardMapper.toProdutorResumo(produtor),
        mes.toString(),
        DashboardMapper.toKpis(apuracao),
        montarFinanceiro(produtor.getId(), mes, apuracao),
        montarSerieMensal(produtor.getId(), mes),
        DashboardMapper.toDistribuicaoImpostos(apuracao),
        montarUltimasNotas(produtor.getId()));
  }

  /**
   * Deriva o fluxo de caixa em R$ do período (issue Agrocontabil#71): agrega o valor total das
   * notas autorizadas do produtor no mês por origem (mesma janela {@code [início do mês, início do
   * mês seguinte)} usada pelo {@code ApuracaoService.agregarTotais}, garantindo que o valor em R$
   * se refira exatamente ao mesmo recorte de notas dos KPIs de IBS/CBS) e calcula saldo bruto e
   * lucro estimado.
   *
   * <p><strong>Regra de negócio (não pertence ao mapper):</strong> entrada de caixa = venda
   * (nota {@code PROPRIA}); saída de caixa = compra (nota {@code IMPORTADA}) — o inverso do fluxo
   * de mercadoria já usado na série mensal. O saldo bruto é {@code entradas − saídas}; o lucro
   * estimado desconta desse saldo o IBS/CBS a recolher do mesmo período (já apurado, sem
   * recomputar).
   */
  private FinanceiroResumo montarFinanceiro(
      java.util.UUID produtorId, YearMonth mes, ApuracaoResponse apuracao) {
    OffsetDateTime inicio = mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    ValorNotasAgregado valores =
        notaFiscalRepository.somarValorNotasPorOrigem(produtorId, inicio, fim);

    BigDecimal totalEntradas = coalesce(valores.valorPropria());
    BigDecimal totalSaidas = coalesce(valores.valorImportada());
    BigDecimal saldo = totalEntradas.subtract(totalSaidas);
    BigDecimal tributosARecolher = apuracao.saldoIbs().add(apuracao.saldoCbs());
    BigDecimal lucroEstimado = saldo.subtract(tributosARecolher);

    return DashboardMapper.toFinanceiroResumo(totalEntradas, totalSaidas, saldo, lucroEstimado);
  }

  /** Trata nulo do {@code SUM} (nenhuma nota autorizada no período) como {@code 0.00}. */
  private BigDecimal coalesce(BigDecimal valor) {
    return valor != null ? valor : BigDecimal.ZERO;
  }

  /**
   * Constrói a série de volume dos últimos {@value #MESES_SERIE} meses (inclusive o período),
   * consultando as contagens autorizadas por mês/origem e preenchendo com zero os meses sem notas.
   * Retorna sempre {@value #MESES_SERIE} pontos em ordem cronológica.
   */
  private List<NotaPorMes> montarSerieMensal(java.util.UUID produtorId, YearMonth mes) {
    YearMonth inicioMes = mes.minusMonths(MESES_SERIE - 1L);
    OffsetDateTime inicio = inicioMes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();

    List<NotasPorMesAgregado> agregados =
        notaFiscalRepository.contarNotasPorMesEOrigem(produtorId, inicio, fim);

    Map<YearMonth, long[]> porMes = new HashMap<>();
    for (NotasPorMesAgregado a : agregados) {
      long[] contagens = porMes.computeIfAbsent(YearMonth.of(a.ano(), a.mes()), k -> new long[2]);
      if (a.origem() == OrigemNotaFiscal.IMPORTADA) {
        contagens[0] += a.quantidade();
      } else {
        contagens[1] += a.quantidade();
      }
    }

    List<NotaPorMes> serie = new ArrayList<>(MESES_SERIE);
    for (int i = 0; i < MESES_SERIE; i++) {
      YearMonth m = inicioMes.plusMonths(i);
      long[] contagens = porMes.getOrDefault(m, new long[2]);
      serie.add(new NotaPorMes(m.toString(), contagens[0], contagens[1]));
    }
    return serie;
  }

  /** Converte as últimas notas do produtor em resumos, sem expor a entidade JPA. */
  private List<NotaResumo> montarUltimasNotas(java.util.UUID produtorId) {
    return notaFiscalRepository.findTop5ByProdutorIdOrderByDataEmissaoDesc(produtorId).stream()
        .map(DashboardMapper::toNotaResumo)
        .toList();
  }

  /** Resolve o período: mês corrente quando ausente; parse de {@code YYYY-MM} caso contrário. */
  private YearMonth resolverPeriodo(String periodo) {
    if (periodo == null || periodo.isBlank()) {
      return YearMonth.now(clock);
    }
    try {
      return YearMonth.parse(periodo);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "Periodo invalido: use o formato YYYY-MM (ex.: 2026-07).");
    }
  }
}

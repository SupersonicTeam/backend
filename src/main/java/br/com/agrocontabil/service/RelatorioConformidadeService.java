package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.DebitoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço do relatório de conformidade tributária do produtor autenticado por período
 * (issue Agrocontabil#32 — UC004).
 *
 * <p>Consolida os <strong>débitos</strong> de IBS/CBS das notas de saída autorizadas com os
 * <strong>créditos</strong> das notas de entrada (origem IMPORTADA), apurando o saldo a pagar
 * do mês. Ambas as agregações são feitas direto das notas, sem depender da entidade
 * {@code ApuracaoMensal} (desenvolvida em paralelo em outra issue) — o desacoplamento é
 * intencional.
 *
 * <p><strong>Recorte de segurança (anti-IDOR):</strong> as agregações são sempre recortadas pelo
 * ID do produtor autenticado obtido via {@link AuthenticatedProdutorProvider}, no {@code WHERE}
 * das queries do repositório.
 *
 * <p><strong>Premissa fiscal (LC 214/2025, arts. 47–51):</strong> a apuração é não-cumulativa —
 * do débito das saídas do período deduz-se o crédito das entradas do mesmo período (competência
 * pela {@code dataEmissao}, recorte no fuso {@code America/Sao_Paulo}). Saldo negativo representa
 * saldo credor a transportar ao período seguinte. O escopo aqui é o relatório consolidado; o
 * fechamento formal do período (entidade {@code ApuracaoMensal}) é tratado em issue paralela.
 */
@Service
@Transactional(readOnly = true)
public class RelatorioConformidadeService {

  private static final Logger log = LoggerFactory.getLogger(RelatorioConformidadeService.class);

  /** Fuso de referência do projeto para o recorte mensal do período. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final RelatorioConformidadePdfService pdfService;

  /** Constrói o serviço com o repositório, o provedor de produtor e o gerador de PDF. */
  public RelatorioConformidadeService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      RelatorioConformidadePdfService pdfService) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.pdfService = pdfService;
  }

  /**
   * Gera o relatório de conformidade consolidado do produtor autenticado no período informado.
   *
   * @param periodo período no formato {@code YYYY-MM} (ex.: {@code 2026-07})
   * @return relatório consolidado (débitos × créditos × saldo)
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM} (HTTP 400)
   */
  public RelatorioConformidadeResponse gerar(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    Produtor produtor = produtorProvider.get();

    OffsetDateTime inicio = mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();

    DebitoSaldoAgregado debitos =
        notaFiscalRepository.somarDebitosPorPeriodo(produtor.getId(), inicio, fim);
    CreditoSaldoAgregado creditos =
        notaFiscalRepository.somarCreditosPorPeriodo(produtor.getId(), inicio, fim);

    BigDecimal debitoIbs = coalesce(debitos.debitoIbs());
    BigDecimal debitoCbs = coalesce(debitos.debitoCbs());
    BigDecimal debitoTotal = debitoIbs.add(debitoCbs);

    BigDecimal creditoIbs = coalesce(creditos.creditoIbs());
    BigDecimal creditoCbs = coalesce(creditos.creditoCbs());
    BigDecimal creditoTotal = creditoIbs.add(creditoCbs);

    BigDecimal saldoIbs = debitoIbs.subtract(creditoIbs);
    BigDecimal saldoCbs = debitoCbs.subtract(creditoCbs);
    BigDecimal saldoAPagar = debitoTotal.subtract(creditoTotal);

    log.info("Relatorio de conformidade gerado: produtor={} periodo={} saidas={} entradas={}",
        produtor.getId(), mes, debitos.quantidadeNotas(), creditos.quantidadeNotas());

    return new RelatorioConformidadeResponse(
        mes.toString(),
        debitoIbs, debitoCbs, debitoTotal, debitos.quantidadeNotas(),
        creditoIbs, creditoCbs, creditoTotal, creditos.quantidadeNotas(),
        saldoIbs, saldoCbs, saldoAPagar);
  }

  /**
   * Gera o PDF do relatório de conformidade do produtor autenticado no período informado.
   *
   * @param periodo período no formato {@code YYYY-MM}
   * @return bytes do PDF gerado
   * @throws IllegalArgumentException se o período for inválido (HTTP 400)
   */
  public byte[] gerarPdf(String periodo) {
    RelatorioConformidadeResponse relatorio = gerar(periodo);
    Produtor produtor = produtorProvider.get();
    return pdfService.gerarPdf(relatorio, produtor.getNomeCompleto(), produtor.getEstado());
  }

  /**
   * Faz o parse do período {@code YYYY-MM}. Rede de segurança para chamadas diretas — a validação
   * de formato do parâmetro já ocorre no controller via Bean Validation ({@code @Pattern}).
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

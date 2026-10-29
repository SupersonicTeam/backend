package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de consulta do saldo acumulado de crédito tributário de IBS/CBS do produtor
 * autenticado por período (issue Agrocontabil#30 — RN003).
 *
 * <p>O saldo consolida os créditos aproveitáveis das notas de <strong>entrada</strong>
 * (origem IMPORTADA) do produtor, calculados na importação pelo {@link CreditoTributarioService}
 * e agregados por mês diretamente das notas. Não depende da entidade {@code ApuracaoMensal}
 * (desenvolvida em paralelo em outra issue) — o desacoplamento é intencional.
 *
 * <p><strong>Recorte de segurança (anti-IDOR):</strong> a agregação é sempre feita pelo ID do
 * produtor autenticado obtido via {@link AuthenticatedProdutorProvider}; a query do repositório
 * filtra por esse ID, de modo que um produtor nunca soma créditos de outro.
 *
 * <p><strong>Premissa fiscal (LC 214/2025):</strong> a apropriação do crédito segue a
 * competência da <em>emissão</em> da nota de entrada ({@code dataEmissao}); o recorte mensal usa
 * o fuso {@code America/Sao_Paulo}, alinhado à convenção de datas do projeto.
 */
@Service
@Transactional(readOnly = true)
public class CreditoService {

  private static final Logger log = LoggerFactory.getLogger(CreditoService.class);

  /** Fuso de referência do projeto para o recorte mensal do período. */
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;

  /** Constrói o serviço com o repositório de notas e o provedor de produtor autenticado. */
  public CreditoService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Consulta o saldo de crédito de IBS/CBS do produtor autenticado no período informado.
   *
   * @param periodo período no formato {@code YYYY-MM} (ex.: {@code 2026-07})
   * @return saldo consolidado do período (zerado quando não há notas de entrada no mês)
   * @throws IllegalArgumentException se o período não estiver no formato {@code YYYY-MM} (HTTP 400)
   */
  public CreditoSaldoResponse consultarSaldo(String periodo) {
    YearMonth mes = parsePeriodo(periodo);
    return consultarSaldoDoProdutor(produtorProvider.get().getId(), mes.toString());
  }

  /**
   * Mesma consulta de {@link #consultarSaldo(String)}, para um produtor já autorizado pelo
   * chamador — o saldo de um cliente da carteira (issue #255).
   *
   * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de ter passado pelo
   * {@code ProdutorEmContextoResolver} antes; aqui ele é só o filtro da agregação.
   *
   * @param produtorId produtor já autorizado
   * @param periodo    período no formato {@code YYYY-MM}
   * @return saldo consolidado do período (zerado quando não há notas de entrada no mês)
   */
  public CreditoSaldoResponse consultarSaldoDoProdutor(UUID produtorId, String periodo) {
    YearMonth mes = parsePeriodo(periodo);

    OffsetDateTime inicio = mes.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();

    CreditoSaldoAgregado agregado =
        notaFiscalRepository.somarCreditosPorPeriodo(produtorId, inicio, fim);

    BigDecimal creditoIbs = coalesce(agregado.creditoIbs());
    BigDecimal creditoCbs = coalesce(agregado.creditoCbs());
    BigDecimal total = creditoIbs.add(creditoCbs);

    log.info("Saldo de credito consultado: produtor={} periodo={} notas={}",
        produtorId, mes, agregado.quantidadeNotas());

    return new CreditoSaldoResponse(
        mes.toString(), creditoIbs, creditoCbs, total, agregado.quantidadeNotas());
  }

  /**
   * Créditos de IBS/CBS das notas de entrada de um produtor já identificado num intervalo de datas
   * (issue #142 — função {@code consultarCreditos} da Ceres). Mesma agregação e mesmo recorte de
   * {@link #consultarSaldo(String)} (entradas IMPORTADAS e AUTORIZADAS, competência da emissão em
   * {@code America/Sao_Paulo}); só a janela deixa de ser um mês fechado.
   *
   * <p><strong>Contrato de segurança:</strong> {@code produtorId} tem de vir de identidade já
   * resolvida no servidor, nunca de entrada do cliente ou do modelo (issue #143).
   *
   * @param produtorId produtor da sessão
   * @param inicio     primeiro dia (inclusivo)
   * @param fim        último dia (inclusivo)
   * @return créditos somados (nunca nulos, 2 casas) e quantidade de notas
   */
  public CreditoSaldoAgregado somarCreditosNoIntervalo(
      UUID produtorId, LocalDate inicio, LocalDate fim) {
    CreditoSaldoAgregado agregado = notaFiscalRepository.somarCreditosPorPeriodo(
        produtorId,
        inicio.atStartOfDay(ZONE).toOffsetDateTime(),
        fim.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    return new CreditoSaldoAgregado(
        coalesce(agregado.creditoIbs()),
        coalesce(agregado.creditoCbs()),
        agregado.quantidadeNotas());
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

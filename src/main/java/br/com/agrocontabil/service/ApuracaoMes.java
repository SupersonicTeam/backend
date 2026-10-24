package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.StatusApuracao;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Regra única de como um mês de apuração vira {@link ApuracaoResponse} (issue #179): o persistido
 * e a projeção ao vivo, com o mesmo arredondamento, o mesmo saldo e o mesmo {@code parcial}.
 *
 * <p>Usada pelo {@link ApuracaoService} ({@code ?periodo=}) e pelo {@link ApuracaoFaixaService}
 * ({@code de}/{@code ate}): a faixa promete devolver, para cada mês, exatamente o que o
 * {@code ?periodo=} devolveria, e com a conversão num lugar só as duas não divergem.
 */
final class ApuracaoMes {

  /** Fuso dos limites de mês da apuração. */
  static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

  private ApuracaoMes() {
  }

  /** Apuração consolidada do mês. */
  static ApuracaoResponse daPersistida(ApuracaoMensal a, YearMonth atual) {
    return new ApuracaoResponse(
        a.getPeriodo(), a.getStatus().name(),
        a.getDebitoIbs(), a.getDebitoCbs(),
        a.getCreditoIbs(), a.getCreditoCbs(),
        a.getSaldoIbs(), a.getSaldoCbs(),
        a.getTotalNotas(), a.getDataFechamento(),
        a.getPeriodo(), a.getPeriodo(), naoTerminou(YearMonth.parse(a.getPeriodo()), atual));
  }

  /**
   * Projeção ao vivo (não persistida) do mês: {@code ABERTO}, sem data de fechamento, saldo =
   * débito − crédito. Somas nulas (mês sem nota) viram {@code 0.00}.
   */
  static ApuracaoResponse aoVivo(YearMonth mes, Object debitoIbs, Object debitoCbs,
      Object creditoIbs, Object creditoCbs, long totalNotas, YearMonth atual) {
    BigDecimal dIbs = valor(debitoIbs);
    BigDecimal dCbs = valor(debitoCbs);
    BigDecimal cIbs = valor(creditoIbs);
    BigDecimal cCbs = valor(creditoCbs);
    return new ApuracaoResponse(
        mes.toString(), StatusApuracao.ABERTO.name(),
        dIbs, dCbs, cIbs, cCbs,
        dIbs.subtract(cIbs), dCbs.subtract(cCbs),
        totalNotas, null,
        mes.toString(), mes.toString(), naoTerminou(mes, atual));
  }

  /** Soma do banco como valor monetário: nulo vira {@code 0.00}; sempre 2 casas. */
  static BigDecimal valor(Object soma) {
    if (soma == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal numero = soma instanceof BigDecimal decimal
        ? decimal : new BigDecimal(soma.toString());
    return numero.setScale(2, RoundingMode.HALF_UP);
  }

  /** Mês corrente ou futuro: a apuração ainda pode mudar ({@code parcial}). */
  static boolean naoTerminou(YearMonth mes, YearMonth atual) {
    return !mes.isBefore(atual);
  }
}

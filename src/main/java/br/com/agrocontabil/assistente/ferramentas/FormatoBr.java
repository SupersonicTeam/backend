package br.com.agrocontabil.assistente.ferramentas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatação pt-BR dos valores que as funções devolvem ao modelo (issue #142).
 *
 * <p>Os números saem prontos para leitura ({@code R$ 1.234,56}, {@code 31/07/2026}) para que o
 * modelo os <strong>copie</strong>, em vez de reformatá-los — reformatar é onde ele arredonda e
 * erra. O {@code ValidadorNumeros} confere depois que a resposta só contém números que vieram
 * daqui.
 *
 * <p>{@link DecimalFormat} não é thread-safe: cada chamada cria o seu.
 */
final class FormatoBr {

  private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private FormatoBr() {}

  /** {@code R$ 1.234,56}; nulo vira {@code R$ 0,00}. */
  static String moeda(BigDecimal valor) {
    BigDecimal v = valor == null ? BigDecimal.ZERO : valor.setScale(2, RoundingMode.HALF_UP);
    return "R$ " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR)).format(v);
  }

  /**
   * Preço unitário com 2 a 4 casas ({@code R$ 1.450,00}, {@code R$ 1,4537}, {@code R$ 0,001}):
   * o valor unitário é gravado com 4 casas, e arredondar para 2 mostraria {@code R$ 0,00}.
   */
  static String precoUnitario(BigDecimal valor) {
    BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
    return "R$ " + new DecimalFormat("#,##0.00##", DecimalFormatSymbols.getInstance(PT_BR))
        .format(v);
  }

  /** Quantidade com até 4 casas, sem zeros à direita ({@code 1.500} / {@code 12,5}). */
  static String quantidade(BigDecimal valor) {
    BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
    return new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(PT_BR)).format(v);
  }

  /** {@code 31/07/2026}. */
  static String data(LocalDate data) {
    return data == null ? null : DATA.format(data);
  }

  /** Data da emissão em {@code America/Sao_Paulo}, {@code 31/07/2026}. */
  static String data(OffsetDateTime instante) {
    return instante == null ? null : DATA.format(instante.atZoneSameInstant(ZONE).toLocalDate());
  }
}

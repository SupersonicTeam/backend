package br.com.agrocontabil.model;

import java.time.YearMonth;

/**
 * Agrupamento da faixa de {@code GET /api/apuracao} (issue #179): cinco anos por mês são 60
 * pontos; por ano, cinco.
 */
public enum AgrupamentoApuracao {

  /** Um item por mês ({@code 2026-07}). */
  MES(1),
  /** Um item por trimestre civil ({@code 2026-T3}). */
  TRIMESTRE(3),
  /** Um item por semestre civil ({@code 2026-S2}). */
  SEMESTRE(6),
  /** Um item por ano civil ({@code 2026}). */
  ANO(12);

  private final int meses;

  AgrupamentoApuracao(int meses) {
    this.meses = meses;
  }

  /** Primeiro mês do grupo civil que contém o mês. */
  public YearMonth inicioDoGrupo(YearMonth mes) {
    int indice = (mes.getMonthValue() - 1) / meses;
    return YearMonth.of(mes.getYear(), indice * meses + 1);
  }

  /** Último mês do grupo civil que contém o mês. */
  public YearMonth fimDoGrupo(YearMonth mes) {
    return inicioDoGrupo(mes).plusMonths(meses - 1L);
  }

  /** Rótulo do grupo: {@code 2026-07}, {@code 2026-T3}, {@code 2026-S2} ou {@code 2026}. */
  public String rotulo(YearMonth mes) {
    int ordem = (mes.getMonthValue() - 1) / meses + 1;
    return switch (this) {
      case MES -> mes.toString();
      case TRIMESTRE -> mes.getYear() + "-T" + ordem;
      case SEMESTRE -> mes.getYear() + "-S" + ordem;
      case ANO -> String.valueOf(mes.getYear());
    };
  }
}

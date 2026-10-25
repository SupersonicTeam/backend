package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.Periodicidade;
import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ObrigacaoFiscalService — datas das ocorrencias (#181)")
class ObrigacaoFiscalServiceTest {

  private static final LocalDate MARCO_INICIO = LocalDate.of(2026, 3, 1);
  private static final LocalDate MARCO_FIM = LocalDate.of(2026, 3, 31);

  @Test
  @DisplayName("dia que nao existe no mes cai no ultimo (31 em abril -> 30, em fevereiro -> 28)")
  void diaInexistenteCaiNoUltimo() {
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 31, null,
        AjusteFimDeSemana.NENHUM, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30)))
        .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30));
  }

  @Test
  @DisplayName("sabado e domingo: antecipa para sexta, posterga para segunda, nenhum mantem")
  void fimDeSemana() {
    assertThat(LocalDate.of(2026, 3, 21).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 21, null,
        AjusteFimDeSemana.ANTECIPA, MARCO_INICIO, MARCO_FIM))
        .containsExactly(LocalDate.of(2026, 3, 20));
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 22, null,
        AjusteFimDeSemana.ANTECIPA, MARCO_INICIO, MARCO_FIM))
        .containsExactly(LocalDate.of(2026, 3, 20));
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 21, null,
        AjusteFimDeSemana.POSTERGA, MARCO_INICIO, MARCO_FIM))
        .containsExactly(LocalDate.of(2026, 3, 23));
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 22, null,
        AjusteFimDeSemana.NENHUM, MARCO_INICIO, MARCO_FIM))
        .containsExactly(LocalDate.of(2026, 3, 22));
  }

  @Test
  @DisplayName("ajuste que atravessa a virada do mes entra na janela do mes seguinte")
  void ajusteAtravessaMes() {
    assertThat(LocalDate.of(2026, 1, 31).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 31, null,
        AjusteFimDeSemana.POSTERGA, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 10)))
        .containsExactly(LocalDate.of(2026, 2, 2));
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.MENSAL, 31, null,
        AjusteFimDeSemana.POSTERGA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        .isEmpty();
  }

  @Test
  @DisplayName("anual: so no mes; ultimo dia util de junho = 31 + ANTECIPA")
  void anual() {
    // 2029-06-30 e sabado: o ultimo dia util e a sexta, 29.
    assertThat(LocalDate.of(2029, 6, 30).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    assertThat(ObrigacaoFiscalService.datas(Periodicidade.ANUAL, 31, 6,
        AjusteFimDeSemana.ANTECIPA, LocalDate.of(2028, 1, 1), LocalDate.of(2029, 12, 31)))
        .containsExactly(LocalDate.of(2028, 6, 30), LocalDate.of(2029, 6, 29));
  }
}

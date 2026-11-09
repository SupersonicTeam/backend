package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.StatusApuracao;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #289: a mensagem de topo da reabertura recusada não leva identificador interno. */
class ReaberturaApuracaoMensagemTest {

  private static final String UUID_REGEX =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  @Test
  @DisplayName("409 de apuracao nao reabrivel: mensagem com o status, sem UUID")
  void naoReabrivel() {
    var e = new ApuracaoNaoReabrivelException(StatusApuracao.ABERTO);

    assertThat(e.getMessage()).contains("ABERTO").doesNotContainPattern(UUID_REGEX);
  }

  @Test
  @DisplayName("422 de reabertura fora do prazo: mensagem com os periodos, sem UUID")
  void foraDoPrazo() {
    var e = new ReaberturaForaPrazoException(YearMonth.of(2026, 5), YearMonth.of(2026, 8));

    assertThat(e.getMessage()).contains("2026-05", "2026-08").doesNotContainPattern(UUID_REGEX);
  }
}

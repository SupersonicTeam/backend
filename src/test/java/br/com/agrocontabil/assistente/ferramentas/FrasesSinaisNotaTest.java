package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrasesSinaisNotaTest {

  @Test
  @DisplayName("nota autorizada: 'confira' e o prazo de cancelamento; nunca 'esta errada'")
  void autorizada() {
    String texto = FrasesSinaisNota.texto(116, List.of(new Sinal("PRODUTO_PARECE_SERVICO", 1,
        "Frete", "6949", null, LocalDate.of(2026, 11, 26))));

    assertThat(texto).isEqualTo("Na nota 116, o item 'Frete' saiu com CFOP 6.949 (outra saida nao"
        + " especificada) e parece um servico. Servico normalmente nao sai em nota de produto:"
        + " confira com seu contador. A nota ja foi autorizada: se for preciso, da para"
        + " cancela-la ate 26/11/2026.");
    assertThat(texto.toLowerCase(Locale.ROOT)).doesNotContain("errad");
  }

  @Test
  @DisplayName("NCM fora da referencia, descricao longa cortada, sem sinal = vazio")
  void outros() {
    assertThat(FrasesSinaisNota.texto(9, List.of(new Sinal("PRODUTO_PARECE_SERVICO", 1,
        "Aluguel de trator ".repeat(10), "5101", null, null))))
        .contains("...' saiu com um NCM fora da tabela de produtos agropecuarios")
        .endsWith("antes de emitir.");
    assertThat(FrasesSinaisNota.texto(9, List.of())).isEmpty();
  }
}

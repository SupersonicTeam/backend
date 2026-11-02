package br.com.agrocontabil.assistente.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrasesSinaisNotaTest {

  private static final OffsetDateTime LIMITE =
      OffsetDateTime.of(2026, 11, 26, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

  @Test
  @DisplayName("autorizada no prazo: 'confira' e o limite com a hora; nunca 'esta errada'")
  void autorizadaNoPrazo() {
    String texto = FrasesSinaisNota.texto(116, null, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "CFOP_GENERICO", "AUTORIZADA_NO_PRAZO", 1, "Frete", "6949", LIMITE)));

    assertThat(texto).isEqualTo("Na nota 116, o item 'Frete' saiu com CFOP 6.949 (outra saida nao"
        + " especificada) e parece um servico. Servico normalmente nao sai em nota de produto:"
        + " confira com seu contador. A nota ja foi autorizada: se for preciso, da para"
        + " cancela-la ate 26/11/2026, as 10:00 (horario de Brasilia).");
    assertThat(texto.toLowerCase(Locale.ROOT)).doesNotContain("errad");
  }

  @Test
  @DisplayName("autorizada fora do prazo nao manda 'antes de emitir'; serie quando o numero repete")
  void foraDoPrazoESerie() {
    assertThat(FrasesSinaisNota.texto(116, 2, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "CFOP_GENERICO", "AUTORIZADA_FORA_DO_PRAZO", 1, "Frete", "5949", null))))
        .startsWith("Na nota 116 (serie 2), o item 'Frete'")
        .doesNotContain("antes de emitir")
        .endsWith("o prazo de 30 dias para cancelar passou: veja com seu contador como corrigir.");
  }

  @Test
  @DisplayName("NCM fora da referencia; descricao sem quebra de linha e cortada por code point")
  void outros() {
    assertThat(FrasesSinaisNota.texto(9, null, List.of(new Sinal("PRODUTO_PARECE_SERVICO",
        "NCM_FORA_DA_REFERENCIA", "ANTES_DE_EMITIR", 1, "Aluguel\n\nde trator", "5101", null))))
        .contains("o item 'Aluguel de trator' saiu com um NCM que nao esta na tabela")
        .endsWith("antes de emitir.");
    String emoji = "a".repeat(59) + "\uD83D\uDE9C" + "b".repeat(10);
    assertThat(FrasesSinaisNota.curta(emoji)).endsWith("\uD83D\uDE9C...");
    assertThat(FrasesSinaisNota.texto(9, null, List.of())).isEmpty();
  }
}

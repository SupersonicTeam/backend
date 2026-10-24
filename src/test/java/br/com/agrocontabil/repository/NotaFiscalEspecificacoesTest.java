package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Faixas do prefixo numérico da busca das notas (issue #174). */
class NotaFiscalEspecificacoesTest {

  @Test
  @DisplayName("\"12\" vira uma faixa por tamanho de número, até 9 dígitos")
  void faixasDoPrefixo() {
    assertThat(NotaFiscalEspecificacoes.faixasDoPrefixo("12"))
        .hasSize(8)
        .first().satisfies(f -> assertThat(f).containsExactly(12L, 12L));
    assertThat(NotaFiscalEspecificacoes.faixasDoPrefixo("12").get(1)).containsExactly(120L, 129L);
    assertThat(NotaFiscalEspecificacoes.faixasDoPrefixo("12").get(7))
        .containsExactly(120_000_000L, 129_999_999L);
  }

  @Test
  @DisplayName("Zero à esquerda ou mais de 9 dígitos não casa número nenhum")
  void prefixoImpossivel() {
    assertThat(NotaFiscalEspecificacoes.faixasDoPrefixo("05")).isEmpty();
    assertThat(NotaFiscalEspecificacoes.faixasDoPrefixo("1234567890")).isEmpty();
  }
}

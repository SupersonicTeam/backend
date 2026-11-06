package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regra única de normalização da busca das notas (issue #174). */
class NormalizacaoBuscaTest {

  @Test
  @DisplayName("Tira acento, passa a minúsculas e colapsa espaços")
  void normaliza() {
    assertThat(NormalizacaoBusca.normalizar("  Cooperativa   AGRÍCOLA\tdo Paraná "))
        .isEqualTo("cooperativa agricola do parana");
    assertThat(NormalizacaoBusca.normalizar("Ação Çedilha Ñandu")).isEqualTo("acao cedilha nandu");
    assertThat(NormalizacaoBusca.normalizar(null)).isEmpty();
  }

  @Test
  @DisplayName("Escrita que o NFD decompõe sem acento (coreano) volta ao tamanho original")
  void naoExpandeTexto() {
    String hangul = "한국".repeat(100);

    assertThat(NormalizacaoBusca.normalizar(hangul)).isEqualTo(hangul);
    assertThat(NormalizacaoBusca.normalizar("Kråkeslottet Łódź"))
        .isEqualTo("krakeslottet łodz");
  }

  @Test
  @DisplayName("O texto juntado nunca passa do tamanho da coluna")
  void juntarRespeitaOTamanhoDaColuna() {
    assertThat(NormalizacaoBusca.juntar("a".repeat(5000), "b".repeat(5000)))
        .hasSize(NormalizacaoBusca.TAMANHO_MAXIMO);
  }

  @Test
  @DisplayName("#271: texto da nota leva documento so com digitos e descricao e NCM dos itens")
  void textoDaNotaLevaDocumentoEItens() {
    NormalizacaoBusca.TextoNota nota = NormalizacaoBusca.montarTextoDaNota(7L, "Cooperativa",
        "Venda", "12.345.678/0001-95", List.of(
            new NormalizacaoBusca.ItemBusca("Soja em grãos", "12019000"),
            new NormalizacaoBusca.ItemBusca("Soja em grãos", "12019000"),
            new NormalizacaoBusca.ItemBusca("Milho", "10059010")));

    // Item repetido conta uma vez; o documento entra sem a mascara.
    assertThat(nota.texto()).isEqualTo(
        "7 cooperativa venda 12345678000195 soja em graos 12019000 milho 10059010");
    assertThat(nota.itensForaDaBusca()).isZero();
  }

  @Test
  @DisplayName("#271: o que passa do limite fica de fora por item inteiro e e contado")
  void itensQueNaoCabemFicamDeFora() {
    List<NormalizacaoBusca.ItemBusca> itens = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      itens.add(new NormalizacaoBusca.ItemBusca("produto " + i + " " + "x".repeat(150), "12019000"));
    }

    NormalizacaoBusca.TextoNota nota = NormalizacaoBusca.montarTextoDaNota(1L, "A", "Venda",
        "12345678901", itens);

    assertThat(nota.texto().length()).isLessThanOrEqualTo(NormalizacaoBusca.TAMANHO_MAXIMO);
    assertThat(nota.itensForaDaBusca()).isPositive();
    // Nunca corta no meio de um item: o texto termina no NCM do ultimo item que coube.
    assertThat(nota.texto()).endsWith("12019000");
    assertThat(nota.texto()).contains("produto 0 ").doesNotContain("produto 99 ");
  }

  @Test
  @DisplayName("Junta as partes ignorando as nulas")
  void juntaPartes() {
    assertThat(NormalizacaoBusca.juntar(123L, "José", null, "Venda de Soja"))
        .isEqualTo("123 jose venda de soja");
  }

  @Test
  @DisplayName("Curingas do LIKE e a barra viram texto")
  void escapaCuringas() {
    assertThat(NormalizacaoBusca.escaparLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
  }
}

package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes do {@link ValidadorCitacao} (issue #156): citação só com marca existente. */
class ValidadorCitacaoTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";

  private final List<TrechoRecuperado> trechos = List.of(
      new TrechoRecuperado("T1", "id-1", "LC 214/2025", "art. 138, § 1º", URL,
          "texto do paragrafo", 0.9),
      new TrechoRecuperado("T2", "id-2", "LC 214/2025", "art. 164", URL, "texto do artigo", 0.8));

  private final ValidadorCitacao validador = new ValidadorCitacao();

  @Test
  @DisplayName("Resposta valida: marcas viram referencias montadas dos metadados, com link")
  void respostaValida() {
    ValidadorCitacao.Resultado r = validador.validar(
        "A reducao vale para insumos [T1]. O credito presumido tem regra propria [T2].", trechos);

    assertThat(r.valida()).isTrue();
    assertThat(r.texto()).isEqualTo("A reducao vale para insumos (LC 214/2025, art. 138, § 1º). "
        + "O credito presumido tem regra propria (LC 214/2025, art. 164).");
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 138, § 1º", "art. 164");
    assertThat(r.citacoes()).allSatisfy(c -> assertThat(c.url()).isEqualTo(URL));
    assertThat(r.idsCitados()).containsExactly("id-1", "id-2");
  }

  @Test
  @DisplayName("Marcas agrupadas e repetidas: citacao unica, na ordem da primeira mencao")
  void marcasAgrupadas() {
    ValidadorCitacao.Resultado r = validador.validar("Vale para os dois [T2, T1]. E [T2].", trechos);

    assertThat(r.valida()).isTrue();
    assertThat(r.texto()).contains("(LC 214/2025, art. 164; LC 214/2025, art. 138, § 1º)");
    assertThat(r.citacoes()).extracting(CitacaoLegal::dispositivo)
        .containsExactly("art. 164", "art. 138, § 1º");
  }

  @Test
  @DisplayName("Marca inexistente (citacao inventada) derruba a resposta inteira")
  void marcaInexistente() {
    ValidadorCitacao.Resultado r = validador.validar("Correto [T1]. Tambem [T7].", trechos);

    assertThat(r.valida()).isFalse();
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.citacoes()).isEmpty();
  }

  @Test
  @DisplayName("Resposta sem nenhuma marca (inclusive NAO_ENCONTREI) e descartada")
  void semMarca() {
    assertThat(validador.validar("A reducao e de 60% conforme a LC 214.", trechos).valida())
        .isFalse();
    assertThat(validador.validar("NAO_ENCONTREI", trechos).valida()).isFalse();
    assertThat(validador.validar("Qualquer coisa [T1]", List.of()).valida()).isFalse();
  }

  @Test
  @DisplayName("[T0] tambem e marca inexistente")
  void marcaZero() {
    assertThat(validador.validar("Isso [T0].", trechos).valida()).isFalse();
  }

  @Test
  @DisplayName("#258: marcasValidas devolve so os trechos que existem, sem o texto do modelo")
  void marcasValidasNaRespostaInvalida() {
    ValidadorCitacao.Resultado r =
        validador.marcasValidas("Reducao [T2], isencao [T9] e de novo [T2; T1].", trechos);

    assertThat(r.valida()).isFalse();
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.idsCitados()).containsExactly("id-2", "id-1");
    assertThat(validador.marcasValidas("Sem marca nenhuma.", trechos).citacoes()).isEmpty();
  }
}

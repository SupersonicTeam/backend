package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes do {@link ValidadorNumeros} (issue #142): nenhum número sem fonte passa. */
class ValidadorNumerosTest {

  private static final String RESULTADO = """
      {"insumo":"fertilizante","dataInicio":"01/01/2026","dataFim":"30/06/2026",\
      "valorTotal":"R$ 12.345,67","quantidadeNotas":3,\
      "porUnidade":[{"unidade":"KG","quantidade":"1.500","valor":"R$ 12.345,67"}]}""";

  private final ValidadorNumeros validador = new ValidadorNumeros();

  @Test
  @DisplayName("Valores copiados do resultado da funcao passam")
  void valoresCopiadosPassam() {
    String resposta = "No primeiro semestre voce comprou R$ 12.345,67 de fertilizante, "
        + "em 3 notas, somando 1.500 KG (de 01/01/2026 a 30/06/2026).";

    assertThat(validador.verificar(resposta, List.of(RESULTADO)).rastreavel()).isTrue();
  }

  @Test
  @DisplayName("Mesmo valor em outra grafia (en) passa: a comparacao e pelo valor")
  void mesmoValorEmOutraGrafiaPassa() {
    assertThat(validador.verificar("Total: 12,345.67 reais.", List.of(RESULTADO)).rastreavel())
        .isTrue();
  }

  @Test
  @DisplayName("Valor arredondado pelo modelo NAO passa")
  void valorArredondadoNaoPassa() {
    ValidadorNumeros.Resultado r =
        validador.verificar("Voce comprou cerca de R$ 12.346,00.", List.of(RESULTADO));

    assertThat(r.rastreavel()).isFalse();
    assertThat(r.quantidadeSemFonte()).isEqualTo(1);
  }

  @Test
  @DisplayName("Soma feita pelo modelo NAO passa, mesmo com as parcelas certas")
  void somaDoModeloNaoPassa() {
    String fonte = "{\"creditoIbs\":\"R$ 10,00\",\"creditoCbs\":\"R$ 90,00\"}";

    assertThat(validador.verificar("IBS R$ 10,00 e CBS R$ 90,00.", List.of(fonte)).rastreavel())
        .isTrue();
    assertThat(validador.verificar("Total de R$ 100,00.", List.of(fonte)).rastreavel()).isFalse();
  }

  @Test
  @DisplayName("Numero inventado sem nenhuma funcao chamada NAO passa")
  void numeroInventadoNaoPassa() {
    assertThat(validador.verificar("Voce deve R$ 500,00 de IBS.", List.of("qual meu IBS?"))
        .rastreavel()).isFalse();
  }

  @Test
  @DisplayName("Data que nao esta nas fontes NAO passa; mes/ano de data conhecida passa")
  void datas() {
    assertThat(validador.verificar("Vence em 15/07/2026.", List.of(RESULTADO)).rastreavel())
        .isFalse();
    assertThat(validador.verificar("Periodo de 01/2026 ate junho de 2026.", List.of(RESULTADO))
        .rastreavel()).isTrue();
  }

  @Test
  @DisplayName("Numeros da propria pergunta e marcadores de lista passam")
  void perguntaEMarcadoresDeLista() {
    String pergunta = "quanto comprei nos ultimos 6 meses?";
    String resposta = "Nos ultimos 6 meses:\n1. R$ 12.345,67\n2. 3 notas";

    assertThat(validador.verificar(resposta, List.of(RESULTADO, pergunta)).rastreavel()).isTrue();
  }

  @Test
  @DisplayName("Resposta sem numero passa")
  void semNumeroPassa() {
    assertThat(validador.verificar("Nao tenho essa informacao.", List.of()).rastreavel()).isTrue();
  }

  @Test
  @DisplayName("Leituras de 1.500: mil e quinhentos ou um e meio")
  void leiturasAmbiguas() {
    assertThat(ValidadorNumeros.candidatos("1.500"))
        .anySatisfy(v -> assertThat(v).isEqualByComparingTo("1500"))
        .anySatisfy(v -> assertThat(v).isEqualByComparingTo("1.5"));
  }
}

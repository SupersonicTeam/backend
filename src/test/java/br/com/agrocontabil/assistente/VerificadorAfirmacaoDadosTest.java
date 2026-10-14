package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Detecção de afirmação sobre os dados do produtor na parte de lei do MISTO (issue #170). */
class VerificadorAfirmacaoDadosTest {

  private final VerificadorAfirmacaoDados verificador = new VerificadorAfirmacaoDados();

  @ParameterizedTest
  @ValueSource(strings = {
      "Sim, os fertilizantes que você comprou têm direito à redução de 60%.",
      "Voce tem direito a reducao.",
      "Suas compras de defensivos tem reducao.",
      "No seu caso, a aliquota e reduzida.",
      "O produtor que adquiriu fertilizantes tem reducao.",
      "O senhor pode aproveitar o credito.",
      "Os insumos comprados por voce tem reducao."
  })
  void detectaReferenciaAoUsuario(String texto) {
    assertThat(verificador.afirmaSobreDados(texto)).as(texto).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Os insumos agropecuarios do Anexo IX tem reducao de 60% (LC 214/2025, art. 138).",
      "A pessoa juridica e suas filiais respondem solidariamente.",
      "O contribuinte pode optar pelo regime em sua escrituracao.",
      "Ficam reduzidas as aliquotas do IBS e da CBS para fertilizantes."
  })
  void regraImpessoalPassa(String texto) {
    assertThat(verificador.afirmaSobreDados(texto)).as(texto).isFalse();
  }
}

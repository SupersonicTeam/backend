package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParametrosDiagnosticoTest {

  private String perguntasAntes;
  private String saidaAntes;

  @BeforeEach
  void guardar() {
    perguntasAntes = System.getProperty(ParametrosDiagnostico.PERGUNTAS);
    saidaAntes = System.getProperty(ParametrosDiagnostico.SAIDA);
    System.clearProperty(ParametrosDiagnostico.PERGUNTAS);
    System.clearProperty(ParametrosDiagnostico.SAIDA);
  }

  @AfterEach
  void restaurar() {
    restaurar(ParametrosDiagnostico.PERGUNTAS, perguntasAntes);
    restaurar(ParametrosDiagnostico.SAIDA, saidaAntes);
  }

  private static void restaurar(String chave, String valor) {
    if (valor == null) {
      System.clearProperty(chave);
    } else {
      System.setProperty(chave, valor);
    }
  }

  @Test
  void semPerguntasFalhaComMensagem() {
    System.setProperty(ParametrosDiagnostico.SAIDA, "saida.json");

    assertThatThrownBy(ParametrosDiagnostico::exigir)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("-Ddiagnostico.perguntas=");
  }

  @Test
  void semSaidaFalhaComMensagem() {
    System.setProperty(ParametrosDiagnostico.PERGUNTAS, "perguntas.json");

    assertThatThrownBy(ParametrosDiagnostico::exigir)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("-Ddiagnostico.saida=");
  }

  @Test
  void valorEmBrancoContaComoAusente() {
    System.setProperty(ParametrosDiagnostico.PERGUNTAS, "  ");
    System.setProperty(ParametrosDiagnostico.SAIDA, "saida.json");

    assertThatThrownBy(ParametrosDiagnostico::exigir).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void comOsDoisDevolveOsCaminhos() {
    System.setProperty(ParametrosDiagnostico.PERGUNTAS, "conjunto.json");
    System.setProperty(ParametrosDiagnostico.SAIDA, " saida.json ");

    ParametrosDiagnostico.exigir();

    assertThat(ParametrosDiagnostico.perguntas()).isEqualTo(Path.of("conjunto.json"));
    assertThat(ParametrosDiagnostico.saida()).isEqualTo(Path.of("saida.json"));
  }
}

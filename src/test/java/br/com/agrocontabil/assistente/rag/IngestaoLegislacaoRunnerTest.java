package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ingestão parcial de uma lei de alteração (issue #145: só o art. 6º da Lei 9.528/1997). */
class IngestaoLegislacaoRunnerTest {

  private static final List<DispositivoLegal> LEI_9528 = List.of(
      new DispositivoLegal("art. 1º (redação do art. 25, inciso I)", "2% (redação de 1997)"),
      new DispositivoLegal("art. 6º", "SENAR 0,2%"),
      new DispositivoLegal("art. 6º, parágrafo único", "Parágrafo único."),
      new DispositivoLegal("art. 6º-A", "outro artigo"),
      new DispositivoLegal("art. 60", "outro artigo"));

  @Test
  @DisplayName("Só o dispositivo pedido e o que ele contém; art. 6º-A e art. 60 ficam fora")
  void selecionaODispositivoESeusFilhos() {
    assertThat(IngestaoLegislacaoRunner.selecionar(LEI_9528, List.of("art. 6º")))
        .extracting(DispositivoLegal::dispositivo)
        .containsExactly("art. 6º", "art. 6º, parágrafo único");
  }

  @Test
  @DisplayName("Sem seleção, a norma inteira")
  void semSelecaoTudo() {
    assertThat(IngestaoLegislacaoRunner.selecionar(LEI_9528, List.of())).hasSize(5);
  }

  @Test
  @DisplayName("Dispositivo pedido que não existe é erro, não ingestão vazia")
  void pedidoInexistenteFalha() {
    assertThatThrownBy(() -> IngestaoLegislacaoRunner.selecionar(LEI_9528, List.of("art. 7º")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("art. 7º");
  }
}

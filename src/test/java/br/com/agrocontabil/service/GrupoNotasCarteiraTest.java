package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A partição dos status em caixas de triagem (issue #341): cada status está em exatamente um
 * grupo ou entre os que só a "Todas" mostra. Status novo em {@link StatusNotaFiscal} faz este teste
 * falhar até alguém decidir onde ele aparece.
 */
class GrupoNotasCarteiraTest {

  @Test
  @DisplayName("todo status esta em um grupo ou so na caixa Todas, e nunca em dois grupos")
  void particaoDosStatus() {
    List<StatusNotaFiscal> contados = new ArrayList<>();
    for (GrupoNotasCarteira grupo : GrupoNotasCarteira.values()) {
      contados.addAll(grupo.status());
    }
    contados.addAll(GrupoNotasCarteira.SOMENTE_EM_TODAS);

    // Sem repetidos (nenhum status em dois lugares) e sem faltar nenhum.
    assertThat(contados).doesNotHaveDuplicates();
    assertThat(EnumSet.copyOf(contados)).isEqualTo(EnumSet.allOf(StatusNotaFiscal.class));
  }

  @Test
  @DisplayName("os grupos sao as caixas de triagem da tela (CAIXAS_TRIAGEM do frontend)")
  void gruposDaTela() {
    assertThat(GrupoNotasCarteira.REVISAO.status())
        .containsExactly(StatusNotaFiscal.AGUARDANDO_APROVACAO);
    assertThat(GrupoNotasCarteira.TRAVADAS.status())
        .containsExactlyInAnyOrder(StatusNotaFiscal.COM_INCONSISTENCIAS,
            StatusNotaFiscal.REJEITADA);
    assertThat(GrupoNotasCarteira.RASCUNHOS.status()).containsExactly(StatusNotaFiscal.RASCUNHO);
    assertThat(GrupoNotasCarteira.DEVOLVIDAS.status()).containsExactly(StatusNotaFiscal.DEVOLVIDA);
    assertThat(GrupoNotasCarteira.AUTORIZADAS.status())
        .containsExactly(StatusNotaFiscal.AUTORIZADA);
    assertThat(GrupoNotasCarteira.CANCELADAS.status())
        .containsExactly(StatusNotaFiscal.CANCELADA);
    Set<StatusNotaFiscal> soEmTodas = GrupoNotasCarteira.SOMENTE_EM_TODAS;
    assertThat(soEmTodas).containsExactlyInAnyOrder(StatusNotaFiscal.APROVADA,
        StatusNotaFiscal.XML_GERADO, StatusNotaFiscal.ENVIANDO, StatusNotaFiscal.PENDENTE_SEFAZ,
        StatusNotaFiscal.CANCELANDO);
  }
}

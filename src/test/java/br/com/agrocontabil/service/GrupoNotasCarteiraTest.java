package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.dto.FiltroNotasCarteiraRequest;
import br.com.agrocontabil.dto.FiltroNotasRequest;
import br.com.agrocontabil.dto.ResumoNotasCarteiraResponse;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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
  @DisplayName("o resumo tem um campo por grupo mais 'todas' (grupo novo nao some do DTO)")
  void resumoTemUmCampoPorGrupo() {
    Set<String> campos = Arrays.stream(ResumoNotasCarteiraResponse.class.getRecordComponents())
        .map(RecordComponent::getName).collect(Collectors.toSet());
    Set<String> esperados = Arrays.stream(GrupoNotasCarteira.values())
        .map(g -> g.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    esperados.add("todas");

    assertThat(campos).isEqualTo(esperados);
  }

  @Test
  @DisplayName("o filtro da carteira repete todos os filtros de GET /api/notas (filtro novo nao some)")
  void filtroDaCarteiraTemOsFiltrosDeNotas() {
    Set<String> daCarteira = Arrays.stream(FiltroNotasCarteiraRequest.class.getRecordComponents())
        .map(RecordComponent::getName).collect(Collectors.toSet());
    Set<String> deNotas = Arrays.stream(FiltroNotasRequest.class.getRecordComponents())
        .map(RecordComponent::getName).collect(Collectors.toSet());

    assertThat(daCarteira).containsAll(deNotas).contains("produtorId", "responsavel");
  }

  @Test
  @DisplayName("os conjuntos de status expostos nao podem ser alterados de fora")
  void conjuntosImutaveis() {
    assertThatThrownBy(() -> GrupoNotasCarteira.SOMENTE_EM_TODAS.add(StatusNotaFiscal.RASCUNHO))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> GrupoNotasCarteira.REVISAO.status().clear())
        .isInstanceOf(UnsupportedOperationException.class);
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

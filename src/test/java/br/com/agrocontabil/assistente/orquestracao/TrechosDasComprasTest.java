package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Itens de anexo pelo NCM das compras, para a lei do MISTO (issue #170). */
@ExtendWith(MockitoExtension.class)
class TrechosDasComprasTest {

  private static final UUID PRODUTOR = UUID.randomUUID();
  private static final LocalDate HOJE = LocalDate.of(2026, 10, 14);
  private static final TrechoRecuperado ANEXO = new TrechoRecuperado("T1", "id", "LC 214/2025",
      "Anexo IX, item 4", "url", "Item 4: Adubos - 3105.20.00", 0);

  @Mock private DadosProdutorPorta dados;
  @Mock private BuscaTextualLegislacaoService busca;

  private TrechosDasCompras trechos;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    trechos = new TrechosDasCompras(dados, busca);
    contexto = new ContextoAssistente(PRODUTOR, HOJE);
  }

  @Test
  @DisplayName("Com compras consultadas: NCMs do produtor do contexto viram itens de anexo")
  void comprasViramItensDeAnexo() {
    registrarCompras();
    when(dados.ncmsComprados(PRODUTOR, "adubo", LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 6, 30), TrechosDasCompras.MAX_NCMS)).thenReturn(List.of("31052000"));
    when(busca.buscarAnexosPorNcm(List.of("31052000"), HOJE, TrechosDasCompras.MAX_TRECHOS))
        .thenReturn(List.of(ANEXO));

    assertThat(trechos.buscar(contexto)).containsExactly(ANEXO);
  }

  @Test
  @DisplayName("Sem consulta de compras, nada e buscado")
  void semConsultaDeCompras() {
    contexto.registrarFonte("listarNotas", Map.of(), false);

    assertThat(trechos.buscar(contexto)).isEmpty();
    verifyNoInteractions(dados, busca);
  }

  @Test
  @DisplayName("Compras sem NCM encontrado: nao vai a base de legislacao")
  void semNcm() {
    registrarCompras();
    when(dados.ncmsComprados(any(), anyString(), any(), any(), anyInt())).thenReturn(List.of());

    assertThat(trechos.buscar(contexto)).isEmpty();
    verifyNoInteractions(busca);
  }

  @Test
  @DisplayName("Falha e complemento: lei segue sem os itens, sem erro")
  void falhaNaoDerrubaALei() {
    registrarCompras();
    when(dados.ncmsComprados(any(), anyString(), any(), any(), anyInt()))
        .thenReturn(List.of("31052000"));
    when(busca.buscarAnexosPorNcm(any(), any(), anyInt()))
        .thenThrow(new AssistenteIndisponivelException("banco", null));

    assertThat(trechos.buscar(contexto)).isEmpty();
  }

  @Test
  @DisplayName("Pergunta interrompida para tudo")
  void interrupcaoPropaga() {
    registrarCompras();
    contexto.interromper(DesfechoInteracao.CANCELADA);

    assertThatThrownBy(() -> trechos.buscar(contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);
    verifyNoInteractions(dados, busca);
  }

  private void registrarCompras() {
    contexto.registrarFonte(TrechosDasCompras.FUNCAO_COMPRAS, Map.of(
        "descricaoOuNcm", "adubo", "dataInicio", "2026-01-01", "dataFim", "2026-06-30"), false);
  }
}

package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.support.ChatModelSimulado;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Testes da {@link RotaLegislacao} (issue #156) com o modelo simulado: citação válida vira
 * referência com link; citação inventada, resposta sem marca e número fora dos trechos são
 * descartados; sem trecho, o modelo nem é chamado.
 */
class RotaLegislacaoTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final TrechoRecuperado TRECHO = new TrechoRecuperado("T1", "id-1", "LC 214/2025",
      "art. 138, § 1º", URL, "Ficam reduzidas em 60% as aliquotas dos insumos agropecuarios.", 0.9);

  private BuscaLegislacaoService busca;
  private ChatModelSimulado chatModel;
  private RotaLegislacao rota;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    busca = mock(BuscaLegislacaoService.class);
    chatModel = new ChatModelSimulado();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("chatModel", chatModel);
    rota = new RotaLegislacao(
        new ModeloLinguagem(beans.getBeanProvider(ChatModel.class), "ollama",
            new br.com.agrocontabil.assistente.llm.DisponibilidadeModelo(
                java.time.Duration.ofSeconds(30), System::nanoTime)),
        busca, new ValidadorCitacao(), new ValidadorNumeros());
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 9, 29));
  }

  @Test
  @DisplayName("DoD #156: resposta com citacao valida e link oficial; trechos na auditoria")
  void respostaComCitacaoValida() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao para insumos agropecuarios e de 60% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao para insumos?", contexto);

    assertThat(r.aceita()).isTrue();
    assertThat(r.texto()).isEqualTo(
        "A reducao para insumos agropecuarios e de 60% (LC 214/2025, art. 138, § 1º).");
    assertThat(r.citacoes()).singleElement().satisfies(c -> {
      assertThat(c.url()).isEqualTo(URL);
      assertThat(c.trecho()).isEqualTo(TRECHO.texto());
    });
    assertThat(contexto.trechosCitados()).containsExactly("id-1");
  }

  @Test
  @DisplayName("O modelo ve so a marca e o texto do trecho, nunca a referencia legal")
  void modeloNaoVeReferencia() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("Resposta [T1].");

    rota.responder("Qual a reducao?", contexto);

    String sistema = chatModel.promptsRecebidos().get(0).getSystemMessage().getText();
    assertThat(sistema).contains("[T1] Ficam reduzidas em 60%")
        .doesNotContain("art. 138, § 1º", "LC 214/2025");
  }

  @Test
  @DisplayName("DoD #156: citacao inventada pelo modelo e barrada")
  void citacaoInventadaBarrada() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao e de 60% [T1], e ha isencao total [T4].");

    ResultadoRota r = rota.responder("Qual a reducao?", contexto);

    assertThat(r.aceita()).isFalse();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.citacoes()).isEmpty();
    assertThat(contexto.trechosCitados()).isEmpty();
  }

  @Test
  @DisplayName("Percentual que a lei nao diz e barrado, mesmo com marca valida")
  void numeroForaDosTrechos() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto("A reducao e de 70% [T1].");

    ResultadoRota r = rota.responder("Qual a reducao?", contexto);

    assertThat(r.aceita()).isFalse();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.NUMERO_SEM_FONTE);
    List<CitacaoLegal> citacoes = r.citacoes();
    assertThat(citacoes).isEmpty();
  }

  @Test
  @DisplayName("NAO_ENCONTREI do modelo vira 'nao encontrei fundamento legal'")
  void modeloNaoEncontrou() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of(TRECHO));
    chatModel.responderTexto(PromptsCeres.NAO_ENCONTREI);

    ResultadoRota r = rota.responder("Como declarar o imposto de renda?", contexto);

    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
  }

  @Test
  @DisplayName("Sem trecho acima do limiar: resposta fixa sem chamar o modelo")
  void semTrechoNaoChamaModelo() {
    when(busca.buscar(anyString(), any())).thenReturn(List.of());

    ResultadoRota r = rota.responder("Qual a capital da Franca?", contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.SEM_FUNDAMENTO_LEGAL);
    assertThat(r.texto()).isEqualTo(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(chatModel.promptsRecebidos()).isEmpty();
  }
}

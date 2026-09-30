package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Roteamento do {@link OrquestradorService} (issue #157). */
@ExtendWith(MockitoExtension.class)
class OrquestradorServiceTest {

  private static final CitacaoLegal CITACAO = new CitacaoLegal("LC 214/2025", "art. 138",
      "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm", "trecho");

  @Mock private ClassificadorIntencao classificador;
  @Mock private RotaDados rotaDados;
  @Mock private RotaLegislacao rotaLegislacao;

  private OrquestradorService orquestrador;
  private ContextoAssistente contexto;
  private final List<Intencao> rotasAvisadas = new ArrayList<>();

  @BeforeEach
  void setUp() {
    orquestrador = new OrquestradorService(
        classificador, rotaDados, rotaLegislacao, new AssistenteProperties());
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 9, 29));
  }

  @Test
  @DisplayName("DADOS vai so para a rota de dados")
  void dados() {
    classificarComo(Intencao.DADOS, 0.9);
    when(rotaDados.responder(anyString(), any())).thenReturn(ResultadoRota.aceita("R$ 10,00"));

    ResultadoOrquestracao r = responder();

    assertThat(r.rota()).isEqualTo(Intencao.DADOS);
    assertThat(r.texto()).isEqualTo("R$ 10,00");
    assertThat(r.exibirFontes()).isTrue();
    verifyNoInteractions(rotaLegislacao);
    assertThat(rotasAvisadas).containsExactly(Intencao.DADOS);
  }

  @Test
  @DisplayName("LEGISLACAO vai so para o RAG e traz as citacoes")
  void legislacao() {
    classificarComo(Intencao.LEGISLACAO, 0.8);
    when(rotaLegislacao.responder(anyString(), any()))
        .thenReturn(ResultadoRota.aceita("Reducao (LC 214/2025, art. 138).", List.of(CITACAO)));

    ResultadoOrquestracao r = responder();

    assertThat(r.rota()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(r.citacoes()).containsExactly(CITACAO);
    assertThat(r.exibirFontes()).isFalse();
    verifyNoInteractions(rotaDados);
  }

  @Test
  @DisplayName("MISTO aciona as duas camadas e junta dados + lei, fontes e citacoes")
  void misto() {
    classificarComo(Intencao.MISTO, 0.9);
    when(rotaDados.responder(anyString(), any())).thenReturn(ResultadoRota.aceita("Voce comprou X."));
    when(rotaLegislacao.responder(anyString(), any()))
        .thenReturn(ResultadoRota.aceita("Ha credito (LC 214/2025, art. 138).", List.of(CITACAO)));

    ResultadoOrquestracao r = responder();

    assertThat(r.rota()).isEqualTo(Intencao.MISTO);
    assertThat(r.texto()).isEqualTo("Voce comprou X.\n\nHa credito (LC 214/2025, art. 138).");
    assertThat(r.citacoes()).containsExactly(CITACAO);
    assertThat(r.exibirFontes()).isTrue();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    verify(rotaDados).responder(anyString(), any());
    verify(rotaLegislacao).responder(anyString(), any());
  }

  @Test
  @DisplayName("MISTO com a parte de lei sem fundamento: dados + mensagem fixa, sem citacao")
  void mistoSemFundamento() {
    classificarComo(Intencao.MISTO, 0.9);
    when(rotaDados.responder(anyString(), any())).thenReturn(ResultadoRota.aceita("Voce comprou X."));
    when(rotaLegislacao.responder(anyString(), any())).thenReturn(ResultadoRota.descartada(
        ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.CITACAO_INVALIDA));

    ResultadoOrquestracao r = responder();

    assertThat(r.texto()).endsWith(ValidadorCitacao.SEM_FUNDAMENTO);
    assertThat(r.citacoes()).isEmpty();
  }

  @Test
  @DisplayName("FORA_DE_ESCOPO: resposta fixa, nenhuma rota, nenhuma funcao")
  void foraDeEscopo() {
    classificarComo(Intencao.FORA_DE_ESCOPO, 0.99);

    ResultadoOrquestracao r = responder();

    assertThat(r.texto()).isEqualTo(PromptsCeres.FORA_DE_ESCOPO);
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FORA_DE_ESCOPO);
    verifyNoInteractions(rotaDados, rotaLegislacao);
  }

  @Test
  @DisplayName("Confianca abaixo do minimo vira fora de escopo, mesmo com intencao DADOS")
  void confiancaBaixa() {
    classificarComo(Intencao.DADOS, 0.3);

    ResultadoOrquestracao r = responder();

    assertThat(r.rota()).isEqualTo(Intencao.FORA_DE_ESCOPO);
    verifyNoInteractions(rotaDados, rotaLegislacao);
  }

  private void classificarComo(Intencao intencao, double confianca) {
    when(classificador.classificar(anyString(), any()))
        .thenReturn(new ClassificacaoIntencao(intencao, confianca));
  }

  private ResultadoOrquestracao responder() {
    return orquestrador.responder("pergunta", contexto, rotasAvisadas::add);
  }
}

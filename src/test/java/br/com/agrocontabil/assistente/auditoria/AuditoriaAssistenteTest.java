package br.com.agrocontabil.assistente.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta;
import br.com.agrocontabil.assistente.porta.AuditoriaPorta.RegistroInteracao;
import br.com.agrocontabil.model.DesfechoInteracao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Registro de auditoria montado pela {@link AuditoriaAssistente}. */
@ExtendWith(MockitoExtension.class)
class AuditoriaAssistenteTest {

  @Mock private AuditoriaPorta porta;

  private AuditoriaAssistente auditoria;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    auditoria = new AuditoriaAssistente(porta, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
    contexto = new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 10, 14));
  }

  @Test
  @DisplayName("MISTO: o desfecho de cada parte e o uso da pergunta reescrita vao ao registro")
  void mistoRegistraPartes() {
    contexto.registrarDesfechoDados(DesfechoInteracao.RESPONDIDA);
    contexto.registrarDesfechoLegislacao(DesfechoInteracao.CITACAO_INVALIDA, true);

    auditoria.registrar(contexto, "MISTO", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.desfechoDados()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.desfechoLegislacao()).isEqualTo(DesfechoInteracao.CITACAO_INVALIDA);
    assertThat(r.perguntaLeiReescrita()).isTrue();
  }

  @Test
  @DisplayName("Rota de uma parte so: campos das partes nulos")
  void rotaSimplesSemPartes() {
    auditoria.registrar(contexto, "DADOS", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.desfechoDados()).isNull();
    assertThat(r.desfechoLegislacao()).isNull();
    assertThat(r.perguntaLeiReescrita()).isNull();
    assertThat(r.fidelidadeLei()).isNull();
  }

  @Test
  @DisplayName("#269: o desfecho da conferencia de fidelidade da lei vai ao registro, so o enum")
  void fidelidadeDaLeiVaiAoRegistro() {
    contexto.registrarFidelidadeLei(
        br.com.agrocontabil.model.DesfechoFidelidadeLei.REPROVADA_COBERTURA);

    auditoria.registrar(contexto, "LEGISLACAO", DesfechoInteracao.FIDELIDADE_REPROVADA,
        "anthropic/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.FIDELIDADE_REPROVADA);
    assertThat(r.fidelidadeLei())
        .isEqualTo(br.com.agrocontabil.model.DesfechoFidelidadeLei.REPROVADA_COBERTURA);
  }

  @Test
  @DisplayName("#269: com o juiz, tokens e tempo dele vao a colunas proprias e saem dos da pergunta")
  void juizDaLeiSaiDoTotalDaPergunta() {
    contexto.registrarUso(new org.springframework.ai.chat.prompt.Prompt("x".repeat(4000)),
        null);
    int entradaTotal = contexto.tokensEntrada();
    int saidaTotal = contexto.tokensSaida();
    contexto.registrarJuizLei(300, 400, 0);

    auditoria.registrar(contexto, "LEGISLACAO", DesfechoInteracao.RESPONDIDA,
        "anthropic/simulado", 1000, 80);

    RegistroInteracao r = capturar();
    assertThat(r.latenciaJuizMs()).isEqualTo(300L);
    assertThat(r.tokensEntradaJuiz()).isEqualTo(400);
    assertThat(r.tokensSaidaJuiz()).isZero();
    assertThat(r.latenciaMs()).isEqualTo(700L);
    assertThat(r.tokensEntrada()).isEqualTo(entradaTotal - 400);
    assertThat(r.tokensSaida()).isEqualTo(saidaTotal);
    // A soma das colunas e o total da pergunta.
    assertThat(r.tokensEntrada() + r.tokensEntradaJuiz()).isEqualTo(entradaTotal);
  }

  @Test
  @DisplayName("#269: sem o juiz, as colunas dele ficam nulas e os totais nao mudam")
  void semJuizColunasNulas() {
    auditoria.registrar(contexto, "LEGISLACAO", DesfechoInteracao.RESPONDIDA,
        "anthropic/simulado", 1000, 80);

    RegistroInteracao r = capturar();
    assertThat(r.latenciaJuizMs()).isNull();
    assertThat(r.tokensEntradaJuiz()).isNull();
    assertThat(r.tokensSaidaJuiz()).isNull();
    assertThat(r.latenciaMs()).isEqualTo(1000L);
  }

  @Test
  @DisplayName("#584: sem reconferencia de lei, o registro dela vai nulo a porta")
  void reconferenciaDeLei() {
    auditoria.registrar(contexto, "LEGISLACAO", DesfechoInteracao.RECONFERENCIA_DESCARTADA,
        "anthropic/simulado", 1000, 80);
    assertThat(capturar().reconferenciaLei()).isNull();
  }

  @Test
  @DisplayName("#584: a reconferencia de lei grava motivo e contagens, sem texto")
  void reconferenciaDeLeiGravada() {
    contexto.atualizarReconferenciaLei(r -> r.comCitados(2, 1));
    contexto.atualizarReconferenciaLei(
        r -> r.comBusca(br.com.agrocontabil.model.BuscaReconferenciaLei.OK, 5, null));
    contexto.atualizarReconferenciaLei(r -> r.comPrompt(6, 1, 9000));
    contexto.atualizarReconferenciaLei(r -> r.comConcordancia(1100,
        DesfechoInteracao.RESPONDIDA));
    contexto.atualizarReconferenciaLei(
        r -> r.comMotivo(br.com.agrocontabil.model.MotivoReconferenciaLei.CORRIGIU));

    auditoria.registrar(contexto, "LEGISLACAO", DesfechoInteracao.RESPONDIDA,
        "anthropic/simulado", 1000, 80);

    AuditoriaPorta.RegistroReconferenciaLei r = capturar().reconferenciaLei();
    assertThat(r.motivo()).isEqualTo(br.com.agrocontabil.model.MotivoReconferenciaLei.CORRIGIU);
    assertThat(r.dispositivos()).isEqualTo(2);
    assertThat(r.trechosCitadosAchados()).isEqualTo(1);
    assertThat(r.busca()).isEqualTo(br.com.agrocontabil.model.BuscaReconferenciaLei.OK);
    assertThat(r.trechosBusca()).isEqualTo(5);
    assertThat(r.trechosCompras()).isNull();
    assertThat(r.trechosPrompt()).isEqualTo(6);
    assertThat(r.trechosPromptCitados()).isEqualTo(1);
    assertThat(r.caracteresTrechos()).isEqualTo(9000);
    assertThat(r.tokensEntradaConcordancia()).isEqualTo(1100);
    assertThat(r.desfechoConcordancia()).isEqualTo(DesfechoInteracao.RESPONDIDA);
  }

  @Test
  @DisplayName("#624: o estado e a latencia do embedding da classificacao vao ao registro")
  void embeddingDaClassificacao() {
    contexto.registrarEmbeddingClassificacao(
        br.com.agrocontabil.model.EstadoEmbeddingClassificacao.PRAZO_VENCIDO, 512);

    auditoria.registrar(contexto, "DADOS", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.embeddingClassificacao())
        .isEqualTo(br.com.agrocontabil.model.EstadoEmbeddingClassificacao.PRAZO_VENCIDO);
    assertThat(r.latenciaEmbeddingClassificacaoMs()).isEqualTo(512);
  }

  @Test
  @DisplayName("#624: sem consulta ao embedding (regra, atalho), os dois campos vao nulos")
  void semEmbeddingDaClassificacao() {
    auditoria.registrar(contexto, "DADOS", DesfechoInteracao.RESPONDIDA, "none/simulado", 10, 80);

    RegistroInteracao r = capturar();
    assertThat(r.embeddingClassificacao()).isNull();
    assertThat(r.latenciaEmbeddingClassificacaoMs()).isNull();
  }

  private RegistroInteracao capturar() {
    ArgumentCaptor<RegistroInteracao> captor = ArgumentCaptor.forClass(RegistroInteracao.class);
    verify(porta).registrar(captor.capture());
    return captor.getValue();
  }
}

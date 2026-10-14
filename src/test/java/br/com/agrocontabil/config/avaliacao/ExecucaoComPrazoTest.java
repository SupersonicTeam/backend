package br.com.agrocontabil.config.avaliacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.orquestracao.ResultadoOrquestracao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Execução das perguntas da avaliação e as guardas do comando (issue #158), sem modelo. */
class ExecucaoComPrazoTest {

  private static final Duration PRAZO = Duration.ofMillis(300);

  private static ContextoAssistente contexto() {
    return new ContextoAssistente(UUID.randomUUID(), LocalDate.of(2026, 9, 15));
  }

  private static ResultadoOrquestracao respondida(Intencao rota) {
    return new ResultadoOrquestracao(rota, "R$ 10,00", DesfechoInteracao.RESPONDIDA, List.of(),
        true);
  }

  @Test
  @DisplayName("Resposta do orquestrador vira a execução, com as fontes do contexto")
  void respostaNormal() {
    try (ExecucaoComPrazo execucao = new ExecucaoComPrazo((p, c, rota) -> {
      rota.accept(Intencao.DADOS);
      c.registrarFonte("listarNotas", Map.of("limite", "5"), false);
      return respondida(Intencao.DADOS);
    }, PRAZO)) {
      ExecucaoPergunta r = execucao.executar("quanto vendi?", contexto());

      assertThat(r.rota()).isEqualTo(Intencao.DADOS);
      assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
      assertThat(r.fontes()).extracting(f -> f.funcao()).containsExactly("listarNotas");
      assertThat(r.erro()).isNull();
    }
  }

  @Test
  @DisplayName("Modelo fora vira MODELO_INDISPONIVEL; outra falha vira ERRO, sem a mensagem")
  void falhas() {
    try (ExecucaoComPrazo indisponivel = new ExecucaoComPrazo((p, c, rota) -> {
      throw new AssistenteIndisponivelException("ollama recusou", null);
    }, PRAZO);
        ExecucaoComPrazo erro = new ExecucaoComPrazo((p, c, rota) -> {
          rota.accept(Intencao.LEGISLACAO);
          throw new IllegalStateException("detalhe interno");
        }, PRAZO)) {
      ExecucaoPergunta fora = indisponivel.executar("?", contexto());
      ExecucaoPergunta falhou = erro.executar("?", contexto());

      assertThat(fora.desfecho()).isEqualTo(DesfechoInteracao.MODELO_INDISPONIVEL);
      assertThat(falhou.desfecho()).isEqualTo(DesfechoInteracao.ERRO);
      assertThat(falhou.rota()).isEqualTo(Intencao.LEGISLACAO);
      assertThat(falhou.erro()).isEqualTo("IllegalStateException");
    }
  }

  @Test
  @DisplayName("Tempo esgotado interrompe o contexto e não trava a pergunta seguinte")
  void tempoEsgotadoNaoTravaAProxima() throws InterruptedException {
    CountDownLatch solta = new CountDownLatch(1);
    try (ExecucaoComPrazo execucao = new ExecucaoComPrazo((p, c, rota) -> {
      if (p.equals("presa")) {
        // Ignora a interrupcao, como uma leitura de socket bloqueada.
        while (solta.getCount() > 0) {
          try {
            solta.await();
          } catch (InterruptedException ignorada) {
            // segue presa
          }
        }
      }
      return respondida(Intencao.FORA_DE_ESCOPO);
    }, PRAZO)) {
      ContextoAssistente presa = contexto();
      ExecucaoPergunta r1 = execucao.executar("presa", presa);
      ExecucaoPergunta r2 = execucao.executar("seguinte", contexto());

      assertThat(r1.desfecho()).isEqualTo(DesfechoInteracao.TEMPO_ESGOTADO);
      assertThat(presa.interrupcao()).contains(DesfechoInteracao.TEMPO_ESGOTADO);
      assertThat(r2.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
      assertThat(r2.latenciaMs()).isLessThan(PRAZO.toMillis());
    } finally {
      solta.countDown();
    }
  }

  @Test
  @DisplayName("Seed só com dev, e nunca junto de prod ou homolog")
  void ambienteDeDesenvolvimento() {
    assertThatCode(() -> AvaliacaoCeresRunner.exigirAmbienteDeDesenvolvimento(
        perfis("dev", "avaliacao"))).doesNotThrowAnyException();
    for (String[] perfis : List.of(new String[] {"avaliacao"},
        new String[] {"prod", "dev", "avaliacao"}, new String[] {"homolog", "dev", "avaliacao"})) {
      assertThatThrownBy(() -> AvaliacaoCeresRunner.exigirAmbienteDeDesenvolvimento(
          perfis(perfis))).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("Aquecimento sem o modelo para a avaliação em vez de gravar relatório")
  void aquecimentoExigeModelo() {
    ExecucaoPergunta noAr = new ExecucaoPergunta(Intencao.LEGISLACAO, "texto",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), false, 10, null);
    ExecucaoPergunta fallback = new ExecucaoPergunta(Intencao.LEGISLACAO, "trechos",
        DesfechoInteracao.RESPONDIDA, List.of(), List.of(), true, 10, null);
    ExecucaoPergunta fora = new ExecucaoPergunta(null, null,
        DesfechoInteracao.MODELO_INDISPONIVEL, List.of(), List.of(), false, 10, "X");

    assertThatCode(() -> AvaliacaoCeresRunner.exigirModeloNoAr(noAr)).doesNotThrowAnyException();
    assertThatThrownBy(() -> AvaliacaoCeresRunner.exigirModeloNoAr(fallback))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AvaliacaoCeresRunner.exigirModeloNoAr(fora))
        .isInstanceOf(IllegalStateException.class);
  }

  private static MockEnvironment perfis(String... perfis) {
    MockEnvironment ambiente = new MockEnvironment();
    ambiente.setActiveProfiles(perfis);
    return ambiente;
  }
}

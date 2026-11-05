package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Validação das propriedades da Ceres na subida (timeouts da issue #157). */
class AssistentePropertiesTest {

  @Test
  @DisplayName("Padroes sobem")
  void padroesValidos() {
    assertThatCode(() -> new AssistenteProperties().afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("timeout-funcao abaixo de 1s e recusado (resolucao do timeout de transacao)")
  void timeoutFuncaoSubSegundo() {
    AssistenteProperties p = new AssistenteProperties();
    p.setTimeoutFuncao(Duration.ofMillis(500));

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.timeout-funcao");
  }

  @Test
  @DisplayName("timeout-funcao nao pode alcancar o timeout-pergunta")
  void timeoutFuncaoMaiorQuePergunta() {
    AssistenteProperties p = new AssistenteProperties();
    p.setTimeoutPergunta(Duration.ofSeconds(10));
    p.setTimeoutFuncao(Duration.ofSeconds(10));

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("deve ser menor");
  }

  @Test
  @DisplayName("timeout-pergunta zero ou nulo e recusado")
  void timeoutPerguntaInvalido() {
    AssistenteProperties zero = new AssistenteProperties();
    zero.setTimeoutPergunta(Duration.ZERO);
    AssistenteProperties nulo = new AssistenteProperties();
    nulo.setTimeoutPergunta(null);

    assertThatThrownBy(zero::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(nulo::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("#166: teto de uma pergunta maior que o plano e recusado na subida")
  void tetoMaiorQueOPlano() {
    AssistenteProperties p = new AssistenteProperties();
    // Modelo maior: AI_MAX_TOKENS 8192 -> teto de ~245 creditos, plano de 200.
    p.getCota().setTetoTokensSaidaPorChamada(8192);
    p.getCota().setCreditosPorSemanaProdutor(200);

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("teto de uma pergunta");

    // Com a cota desligada (demonstracao), a mesma configuracao sobe.
    p.getCota().setHabilitada(false);
    p.afterPropertiesSet();
  }

  @Test
  @DisplayName("#270: teto por chamada zero, abaixo do minimo ou acima da metade do contexto")
  void tetoForaDeFaixa() {
    AssistenteProperties zero = new AssistenteProperties();
    zero.getTetos().setFuncoes(0);
    AssistenteProperties minusculo = new AssistenteProperties();
    minusculo.getTetos().setClassificacao(AssistenteProperties.Tetos.MINIMO - 1);
    AssistenteProperties grande = new AssistenteProperties();
    grande.getTetos().setContexto(6144);
    grande.getTetos().setLegislacao(3073);

    assertThatThrownBy(zero::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.funcoes");
    assertThatThrownBy(minusculo::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.classificacao");
    assertThatThrownBy(grande::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.legislacao")
        .hasMessageContaining("metade do contexto (3072)");
  }

  @Test
  @DisplayName("#270: teto por chamada acima do AI_MAX_TOKENS quebraria a reserva da cota")
  void tetoAcimaDoTetoDaCota() {
    AssistenteProperties p = new AssistenteProperties();
    p.getCota().setTetoTokensSaidaPorChamada(400);
    p.getTetos().setFuncoes(400);

    // Padrao da lei = 1024 > 400.
    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.legislacao")
        .hasMessageContaining("AI_MAX_TOKENS");

    p.getTetos().setLegislacao(400);
    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("#270: contexto abaixo do minimo e recusado")
  void contextoPequeno() {
    AssistenteProperties p = new AssistenteProperties();
    // Abaixo do maior prompt estimado da rota de dados mais o teto das funcoes.
    p.getTetos().setContexto(AssistenteProperties.Tetos.CONTEXTO_MINIMO - 1);

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.contexto");
  }
}

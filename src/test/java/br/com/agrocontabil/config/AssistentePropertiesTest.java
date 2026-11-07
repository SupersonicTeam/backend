package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Validação das propriedades da Ceres na subida (timeouts da issue #157). */
class AssistentePropertiesTest {

  @Test
  @DisplayName("Padroes sobem")
  void padroesValidos() {
    assertThatCode(() -> new AssistenteProperties().afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("#436: particao de caso especial desligada por padrao; nula e recusada")
  void particaoCasoEspecial() {
    assertThat(new AssistenteProperties().getRag().getParticaoCasoEspecial())
        .isEqualTo(AssistenteProperties.ParticaoCasoEspecial.DESLIGADO);
    AssistenteProperties nula = new AssistenteProperties();
    nula.getRag().setParticaoCasoEspecial(null);

    assertThatThrownBy(nula::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.rag.particao-caso-especial");
  }

  @Test
  @DisplayName("#436: os valores do yml e da variavel de ambiente ligam no enum da particao")
  void particaoLigaOsValoresDoYml() {
    Map<String, AssistenteProperties.ParticaoCasoEspecial> valores = Map.of(
        "desligado", AssistenteProperties.ParticaoCasoEspecial.DESLIGADO,
        "regimes-especificos", AssistenteProperties.ParticaoCasoEspecial.REGIMES_ESPECIFICOS,
        "ampliada", AssistenteProperties.ParticaoCasoEspecial.AMPLIADA);
    valores.forEach((texto, esperado) -> {
      Binder binder = new Binder(new MapConfigurationPropertySource(
          Map.of("app.assistente.rag.particao-caso-especial", texto)));

      AssistenteProperties p = binder.bind("app.assistente", AssistenteProperties.class).get();

      assertThat(p.getRag().getParticaoCasoEspecial()).as(texto).isEqualTo(esperado);
    });
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
    // Lei com teto de 4.096: 3 x 8.192 + (256 + 512 + 4.096) x 4 = 44.032 -> 45 creditos, plano
    // de 40.
    p.getTetos().setLegislacao(4096);
    p.getCota().setCreditosPorSemanaProdutor(40);

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
  @DisplayName("#166/#270: o teto da pergunta soma as 3 chamadas reais, cada uma com o seu teto")
  void tetoDaPerguntaSegueAsChamadasReais() {
    AssistenteProperties p = new AssistenteProperties();
    AssistenteProperties.Cota cota = p.getCota();

    // 3 x 8.192 de entrada + (256 + 512 + 1.024) de saida x 4 = 31.744 ponderados -> 32.
    // A formula antiga (2 + 1 + 3 rodadas = 6 chamadas x 1.024 de saida) dava 73.
    assertThat(AssistenteProperties.Cota.CHAMADAS_POR_PERGUNTA).isEqualTo(3);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(32);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 2.0)).isEqualTo(64);

    // Cada teto entra uma vez, com o peso da saida: +250 tokens na lei = +1.000 ponderados.
    p.getTetos().setLegislacao(1274);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(33);
    p.getTetos().setClassificacao(506);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(34);
    p.getTetos().setFuncoes(762);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(35);
  }

  @Test
  @DisplayName("#270: teto por chamada acima do AI_MAX_TOKENS sobe: a reserva usa o proprio teto")
  void tetoAcimaDoAiMaxTokensSobe() {
    AssistenteProperties p = new AssistenteProperties();
    // AI_MAX_TOKENS (padrao do provedor) nao entra mais na reserva: cada chamada manda o seu
    // maxTokens, e e esse que a reserva soma.
    p.getTetos().setLegislacao(2048);

    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
    assertThat(p.getCota().tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(36);
  }

  @Test
  @DisplayName("#166: teto de entrada abaixo da janela do Ollama deixaria a reserva abaixo do real")
  void tetoEntradaAbaixoDoContexto() {
    AssistenteProperties p = new AssistenteProperties();
    p.getCota().setTetoTokensEntradaPorChamada(8000);

    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.cota.teto-tokens-entrada-por-chamada");

    // Contexto menor (o minimo aceito) volta a caber.
    p.getTetos().setContexto(AssistenteProperties.Tetos.CONTEXTO_MINIMO);
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

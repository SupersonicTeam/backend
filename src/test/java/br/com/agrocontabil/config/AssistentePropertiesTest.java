package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
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
  @DisplayName("#511: 1 credito por token, planos de 1.000.000 e 5.000.000, sempre inteiro")
  void umCreditoPorToken() {
    AssistenteProperties.Cota cota = new AssistenteProperties().getCota();

    assertThat(cota.getTokensPorCredito()).isEqualTo(1);
    assertThat(cota.getCreditosPorSemanaProdutor()).isEqualTo(1_000_000);
    assertThat(cota.getCreditosPorSemanaEscritorio()).isEqualTo(5_000_000);
    // Leitura de um "ola" (1 token) e de "quanto imposto devo?" (5 tokens): os dois custam.
    assertThat(cota.creditos(1, 0, 1.0)).isEqualTo(1);
    assertThat(cota.creditos(5, 0, 1.0)).isEqualTo(5);
    // Fator fracionario arredonda para cima, no total: 3 x 1,5 = 4,5 -> 5.
    assertThat(cota.creditos(3, 0, 1.5)).isEqualTo(5);
    // Saida pesa 4.
    assertThat(cota.creditos(10, 2, 1.0)).isEqualTo(18);
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
    // Lei com teto de 4.096: 3 x 8.192 + (256 + 512 + 4.096) x 4 = 44.032 creditos (1 por token,
    // #511), plano de 40.000.
    p.getTetos().setLegislacao(4096);
    p.getCota().setCreditosPorSemanaProdutor(40_000);

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

    // 3 x 8.192 de entrada + (256 + 512 + 1.024) de saida x 4 = 31.744 ponderados = 31.744
    // creditos (1 por token desde a #511; eram 32 com 1.000 tokens por credito).
    assertThat(AssistenteProperties.Cota.CHAMADAS_POR_PERGUNTA).isEqualTo(3);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(31_744);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 2.0)).isEqualTo(63_488);

    // Cada teto entra uma vez, com o peso da saida: +250 tokens na lei = +1.000 ponderados.
    p.getTetos().setLegislacao(1274);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(32_744);
    p.getTetos().setClassificacao(506);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(33_744);
    p.getTetos().setFuncoes(762);
    assertThat(cota.tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(34_744);
  }

  @Test
  @DisplayName("#270: teto por chamada acima do AI_MAX_TOKENS sobe: a reserva usa o proprio teto")
  void tetoAcimaDoAiMaxTokensSobe() {
    AssistenteProperties p = new AssistenteProperties();
    // AI_MAX_TOKENS (padrao do provedor) nao entra mais na reserva: cada chamada manda o seu
    // maxTokens, e e esse que a reserva soma.
    p.getTetos().setLegislacao(2048);

    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
    // 3 x 8.192 + (256 + 512 + 2.048) x 4 = 35.840.
    assertThat(p.getCota().tetoDaPergunta(p.getTetos(), 1.0)).isEqualTo(35_840);
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

  @Test
  @DisplayName("#269: conferencia de fidelidade da lei desligada por padrao, cobertura entre 0 e 1")
  void fidelidadeLei() {
    AssistenteProperties p = new AssistenteProperties();
    assertThat(p.getFidelidadeLei().isEnabled()).isFalse();
    assertThat(p.getFidelidadeLei().getCoberturaMinima()).isEqualTo(0.7);
    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();

    p.getFidelidadeLei().setCoberturaMinima(1.0);
    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();
    for (double invalida : new double[] {0.0, -0.1, 1.01, Double.NaN}) {
      p.getFidelidadeLei().setCoberturaMinima(invalida);
      assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.assistente.fidelidade-lei.cobertura-minima");
    }
  }

  @Test
  @DisplayName("#269: juiz desligado por padrao, cobertura com juiz 0,3, teto do juiz 128")
  void juizDaFidelidadeLei() {
    AssistenteProperties p = new AssistenteProperties();
    assertThat(p.getFidelidadeLei().getJuiz().isEnabled()).isFalse();
    assertThat(p.juizDaLeiAtivo()).isFalse();
    assertThat(p.getFidelidadeLei().getCoberturaMinimaComJuiz()).isEqualTo(0.3);
    assertThat(p.getTetos().getJuiz()).isEqualTo(128);
    p.getFidelidadeLei().getJuiz().setEnabled(true);
    // O juiz so roda com a camada 1.
    assertThat(p.juizDaLeiAtivo()).isFalse();
    p.getFidelidadeLei().setEnabled(true);
    assertThat(p.juizDaLeiAtivo()).isTrue();
    assertThatCode(p::afterPropertiesSet).doesNotThrowAnyException();

    p.getFidelidadeLei().setCoberturaMinimaComJuiz(0);
    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cobertura-minima-com-juiz");
    p.getFidelidadeLei().setCoberturaMinimaComJuiz(0.3);
    p.getTetos().setJuiz(AssistenteProperties.Tetos.MINIMO - 1);
    assertThatThrownBy(p::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.assistente.tetos.juiz");
  }
  @Test
  void resgateDaFidelidadeLei() {
    AssistenteProperties p = new AssistenteProperties();
    AssistenteProperties.FidelidadeLei.Juiz.Resgate resgate =
        p.getFidelidadeLei().getJuiz().getResgate();
    assertThat(resgate.isEnabled()).isFalse();
    assertThat(resgate.isCobertura()).isFalse();
    assertThat(resgate.isPolaridade()).isFalse();
    // O veto vem ligado: o comportamento de antes do modo so resgate.
    assertThat(p.getFidelidadeLei().getJuiz().isVeto()).isTrue();
    resgate.setEnabled(true);
    // O resgate so roda com o juiz, que so roda com a camada 1.
    assertThat(p.resgateDaLeiAtivo()).isFalse();
    p.getFidelidadeLei().getJuiz().setEnabled(true);
    assertThat(p.resgateDaLeiAtivo()).isFalse();
    p.getFidelidadeLei().setEnabled(true);
    assertThat(p.resgateDaLeiAtivo()).isTrue();
    resgate.setEnabled(false);
    assertThat(p.resgateDaLeiAtivo()).isFalse();
  }
  @Test
  void configuracaoDoJuizPeloNome() {
    AssistenteProperties p = new AssistenteProperties();
    assertThat(p.getFidelidadeLei().getJuiz().getConfiguracao())
        .isEqualTo(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.ATUAL);
    assertThat(p.tetoSaidaDoJuiz()).isEqualTo(128);
    for (AssistenteProperties.FidelidadeLei.Juiz.Configuracao c
        : AssistenteProperties.FidelidadeLei.Juiz.Configuracao.values()) {
      assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.deNome(c.nome())).isEqualTo(c);
    }
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA.nome())
        .isEqualTo("justificativa");
    assertThatThrownBy(() -> AssistenteProperties.FidelidadeLei.Juiz.Configuracao.deNome("veredito"))
        .isInstanceOf(IllegalArgumentException.class);
    p.getFidelidadeLei().getJuiz()
        .setConfiguracao(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA);
    assertThat(p.tetoSaidaDoJuiz()).isEqualTo(1024);
  }
  @Test
  void configuracoesAdaptativas() {
    AssistenteProperties p = new AssistenteProperties();
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.deNome("adaptativo-baixo")
        .esforco()).isEqualTo("low");
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.deNome("adaptativo-medio")
        .esforco()).isEqualTo("medium");
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.deNome("adaptativo-alto")
        .esforco()).isEqualTo("high");
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.ATUAL.raciocina()).isFalse();
    assertThat(AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA.raciocina())
        .isFalse();
    assertThat(p.getTetos().getJuizComRaciocinio()).isEqualTo(3072);
    assertThat(p.tetoSaidaDoJuiz(AssistenteProperties.FidelidadeLei.Juiz.Configuracao
        .ADAPTATIVO_MEDIO)).isEqualTo(3072);
  }
}

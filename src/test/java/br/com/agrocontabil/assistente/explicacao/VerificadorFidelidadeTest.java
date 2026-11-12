package br.com.agrocontabil.assistente.explicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.ValidadorNumeros;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoExplicacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conferências da explicação do modelo (#268, critérios da #415): cada falha descarta, e a
 * primeira que falhar é o motivo na auditoria.
 */
class VerificadorFidelidadeTest {

  /** Resposta de dados como o template monta: siglas explicadas na primeira vez. */
  private static final String GUIAS = "Você tem 2 guias em atraso, com vencimento até 11/11/2026:"
      + " Funrural (contribuição previdenciária do produtor rural) de R$ 1.250,00, vencida em"
      + " 20/10/2026, e IBS (imposto sobre bens e serviços) de R$ 320,50, vencida em 05/11/2026.";

  private static final String CREDITOS = "Em outubro de 2026, seus créditos de IBS e CBS somam"
      + " R$ 500,00.";

  private final VerificadorFidelidade verificador = new VerificadorFidelidade(
      new ValidadorNumeros(), GlossarioProdutor.padrao(), new AssistenteProperties());

  private DesfechoExplicacao verificar(String explicacao) {
    return verificador.verificar(explicacao, GUIAS);
  }

  @Test
  @DisplayName("Explicação curta, sem número novo, sigla explicada no template e sem lei: aceita")
  void aceita() {
    assertThat(verificar("Isso quer dizer que duas cobranças já passaram do vencimento e ainda"
        + " aparecem como não pagas. A maior é a do Funrural, de R$ 1.250,00. Vale conferir com o"
        + " seu escritório como colocar as duas em dia."))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_ACEITA);
  }

  @Test
  @DisplayName("Número que não está no template: descartada (NUMERO)")
  void numeroNovo() {
    assertThat(verificar("Somadas, as duas guias dão R$ 1.570,50. Elas já passaram do"
        + " vencimento e aparecem como não pagas."))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
  }

  @Test
  @DisplayName("Número do template alterado: descartada (NUMERO)")
  void numeroAlterado() {
    assertThat(verificar("A maior cobrança é a do Funrural, de R$ 1.250,50. Ela já passou do"
        + " vencimento e aparece como não paga."))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
  }

  @Test
  @DisplayName("Data que não está no template: descartada (NUMERO)")
  void dataNova() {
    assertThat(verificar("As duas guias venceram antes de 01/11/2026. Elas ainda aparecem como"
        + " não pagas no sistema."))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_NUMERO);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // Uma frase só.
      "Você tem duas cobranças vencidas que ainda aparecem como não pagas no sistema.",
      // Quatro frases.
      "Você tem duas cobranças vencidas. Elas aparecem como não pagas. A maior é a do Funrural."
          + " Vale conferir com o escritório.",
      // Curta demais.
      "Duas guias. Vencidas.",
      // Lista.
      "- Você tem duas cobranças vencidas.\n- A maior é a do Funrural.",
      // Dois parágrafos.
      "Você tem duas cobranças vencidas.\n\nA maior é a do Funrural.",
      // Ênfase em markdown.
      "Você tem **duas** cobranças vencidas. A maior é a do Funrural.",
      ""})
  @DisplayName("Fora do tamanho ou do formato (frases, caracteres, parágrafo): descartada")
  void foraDoTamanho(String explicacao) {
    assertThat(verificar(explicacao)).isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TAMANHO);
  }

  @Test
  @DisplayName("Nula ou longa demais: descartada (TAMANHO)")
  void nulaOuLonga() {
    assertThat(verificar(null)).isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TAMANHO);
    String longa = ("Você tem duas cobranças vencidas que ainda aparecem como não pagas no sistema e"
        + " que pedem atenção, porque o vencimento delas já passou faz alguns dias e o escritório"
        + " pode ajudar. ").repeat(3);
    assertThat(longa.length()).isGreaterThan(new AssistenteProperties().getExplicacao()
        .getMaxCaracteres());
    assertThat(verificar(longa)).isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_TAMANHO);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Segundo a RESPOSTA DO SISTEMA, você tem duas cobranças vencidas. Confira com o escritório.",
      "A consulta guiasEmAtraso mostrou duas cobranças vencidas. Confira com o seu escritório.",
      "Você tem duas cobranças vencidas. Fui orientada pelo prompt a não repetir os valores.",
      "Você tem duas cobranças vencidas. Pelos termos do glossário, guia é o documento de pagar.",
      // Oito palavras seguidas da instrução de sistema.
      "Você tem duas cobranças vencidas. Não escreva número, valor, data ou percentual que não"
          + " esteja escrito."})
  @DisplayName("Rótulo do prompt, nome de função, fala sobre o prompt ou trecho dele: VAZAMENTO")
  void vazamento(String explicacao) {
    assertThat(verificar(explicacao))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_VAZAMENTO);
  }

  @Test
  @DisplayName("Sigla sem explicação na explicação nem no template: SIGLA; explicada, aceita")
  void sigla() {
    assertThat(verificador.verificar("O IBS e a CBS das suas compras de outubro de 2026 somam"
        + " R$ 500,00. Esse valor aparece como crédito no mês.", CREDITOS))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_SIGLA);
    assertThat(verificador.verificar("O IBS (imposto sobre bens e serviços) e a CBS"
        + " (contribuição sobre bens e serviços) das suas compras de outubro de 2026 somam"
        + " R$ 500,00. Esse valor aparece como crédito no mês.", CREDITOS))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_ACEITA);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Você tem direito a descontar essas guias. As duas já passaram do vencimento.",
      "As duas guias já venceram. Uma delas é isenta para produtor pessoa física.",
      "As duas guias já venceram. Você não precisa pagar a guia do IBS agora.",
      "As duas guias já venceram. Segundo a lei, o prazo para pagar é curto.",
      "As duas guias já venceram. Pagar atrasado gera multa e juros sobre o valor.",
      "As duas guias já venceram. Você é obrigado a pagar as duas ainda este mês.",
      "As duas guias já venceram. Você pode abater o IBS pago do imposto das suas vendas.",
      "As duas guias já venceram. O artigo da reforma trata do Funrural retido pelo comprador.",
      "As duas guias já venceram. Você não paga o Funrural quando vende para outro produtor."})
  @DisplayName("Conclusão legal sem fonte (direito, isenção, não paga, lei, multa...): descartada")
  void conclusaoLegal(String explicacao) {
    assertThat(verificar(explicacao))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CONCLUSAO_LEGAL);
  }

  @Test
  @DisplayName("A definição do glossário não conta como conclusão legal ('você pode descontar')")
  void definicaoDoGlossarioPassa() {
    assertThat(verificador.verificar("Crédito é a parte do imposto das suas compras que você pode"
        + " descontar do imposto das suas vendas. Em outubro de 2026, o seu crédito de IBS"
        + " (imposto sobre bens e serviços) e CBS (contribuição sobre bens e serviços) ficou em"
        + " R$ 500,00.", CREDITOS))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_ACEITA);
    // A mesma ideia com outras palavras é conclusão legal: o produtor leria uma garantia.
    assertThat(verificador.verificar("Você pode abater esse crédito do imposto das suas vendas."
        + " Em outubro de 2026, ele ficou em R$ 500,00.", CREDITOS))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_DESCARTADA_CONCLUSAO_LEGAL);
  }

  @Test
  @DisplayName("'Pode usar a tela' não é aproveitamento de crédito")
  void usarATela() {
    assertThat(verificar("As duas cobranças já passaram do vencimento. Você pode usar a tela de"
        + " guias para ver os detalhes de cada uma."))
        .isEqualTo(DesfechoExplicacao.EXPLICACAO_ACEITA);
  }

  @Test
  @DisplayName("O texto entregue é um parágrafo só, com os espaços colapsados")
  void paragrafo() {
    assertThat(VerificadorFidelidade.paragrafo("  Uma frase.\n Outra   frase.  "))
        .isEqualTo("Uma frase. Outra frase.");
    assertThat(VerificadorFidelidade.paragrafo(null)).isEmpty();
  }
}

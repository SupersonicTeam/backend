package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.LinhaGlossario;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Atalho da pergunta de definição (#269): o que ele reconhece, o que ele deixa passar para o fluxo
 * normal e a resposta que monta.
 */
class AtalhoDefinicaoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 13);

  private final BuscaTextualLegislacaoService busca = mock(BuscaTextualLegislacaoService.class);
  private final AtalhoDefinicao atalho = new AtalhoDefinicao(busca);
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    when(busca.buscarDispositivos(anyList(), any())).thenAnswer(inv -> {
      List<String> chaves = inv.getArgument(0);
      return chaves.stream().map(AtalhoDefinicaoTest::trecho).toList();
    });
  }

  @Test
  @DisplayName("#269: reconhece as formas que o produtor digita, com e sem acento")
  void reconheceAsFormasDoProdutor() {
    // As três telas que o Murillo mandou da VPS em 2026-11-13.
    assertThat(termoDe("oq é o imposto IBS?")).isEqualTo("IBS");
    assertThat(termoDe("o que é o ibs?")).isEqualTo("IBS");
    assertThat(termoDe("oq é o imposto CBS?")).isEqualTo("CBS");
    // Outras formas da lista fechada.
    assertThat(termoDe("O QUE É O IBS")).isEqualTo("IBS");
    assertThat(termoDe("o que significa CBS?")).isEqualTo("CBS");
    assertThat(termoDe("o que quer dizer split payment?")).isEqualTo("split payment");
    assertThat(termoDe("defina alíquota")).isEqualTo("alíquota");
    assertThat(termoDe("o que é base de cálculo?")).isEqualTo("base de cálculo");
    assertThat(termoDe("o que é fato gerador")).isEqualTo("fato gerador");
    assertThat(termoDe("me explica o que é o Funrural")).isEqualTo("Funrural");
    // Plural: o padrão do glossário vale, a contagem de palavras não muda.
    assertThat(termoDe("o que são créditos?")).isEqualTo("crédito");
    // O crédito presumido é outro conceito e tem termo próprio.
    assertThat(termoDe("o que é crédito presumido?")).isEqualTo("crédito presumido");
    assertThat(termoDe("o que é não cumulatividade?")).isEqualTo("não cumulatividade");
    // "oque" junto: erro de digitação comum, e a rota de lei é onde a pergunta falha hoje.
    assertThat(termoDe("oque é o IBS?")).isEqualTo("IBS");
    assertThat(termoDe("oque sao creditos presumidos?")).isEqualTo("crédito presumido");
    assertThat(termoDe("me explique o que são créditos")).isEqualTo("crédito");
  }

  @Test
  @DisplayName("#269: pergunta que só parece definição segue o fluxo normal")
  void naoEngolePerguntaDeLeiNemDeDados() {
    // Lei: o termo não está no glossário, e "para a lei" é palavra a mais.
    assertThat(AtalhoDefinicao.reconhecer("o que é insumo agropecuário para a lei?")).isEmpty();
    // Quer o número, não a definição.
    assertThat(AtalhoDefinicao.reconhecer("o que é a alíquota do IBS?")).isEmpty();
    // Quer o tratamento do produto (#283).
    assertThat(AtalhoDefinicao.reconhecer("o que é o NCM da soja?")).isEmpty();
    // Dado do produtor: o possessivo não é palavra ignorável.
    assertThat(AtalhoDefinicao.reconhecer("o que é a minha apuração?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o que é o meu crédito?")).isEmpty();
    // Sem fórmula de definição.
    assertThat(AtalhoDefinicao.reconhecer("quanto é a alíquota do IBS?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o IBS incide na exportação?")).isEmpty();
    // Fórmula sem termo, e termo sem dispositivo curado (conceito do sistema, não da lei).
    assertThat(AtalhoDefinicao.reconhecer("o que é?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o que é imposto?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o que é NF-e?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o que é o NCM?")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("o que é a SEFAZ?")).isEmpty();
    // Fórmula no meio da frase não vale: a mensagem tem de começar por ela.
    assertThat(AtalhoDefinicao.reconhecer("me diga o que é o IBS e quanto eu devo")).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer(null)).isEmpty();
    assertThat(AtalhoDefinicao.reconhecer("   ")).isEmpty();
  }

  @Test
  @DisplayName("#269: a resposta é a definição do glossário, com a citação do dispositivo")
  void respostaComDefinicaoECitacao() {
    GlossarioProdutor.Termo ibs = termo("IBS");

    ResultadoOrquestracao r = atalho.responder(ibs, contexto);

    assertThat(r.rota()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.DEFINICAO_DO_GLOSSARIO);
    assertThat(r.exibirFontes()).isFalse();
    assertThat(r.modoFallback()).isFalse();
    assertThat(r.texto())
        .startsWith("IBS (imposto sobre bens e serviços): " + ibs.explicacao() + ".")
        .contains(AtalhoDefinicao.COM_FONTE)
        // Os termos que a definição do IBS usa ganham a linha do backend.
        .contains(LinhaGlossario.TITULO, "- ICMS (imposto sobre circulação de mercadorias e"
            + " serviços): ", "- ISS (imposto sobre serviços): ");
    assertThat(r.citacoes()).singleElement().satisfies(c ->
        assertThat(c.referencia()).isEqualTo("LC 214/2025, art. 1º, inciso I"));
    assertThat(contexto.trechosCitados()).containsExactly("LC 214/2025|art. 1º, inciso I");
  }

  @Test
  @DisplayName("#269: a definição não é citada como se estivesse na lei nem traz número")
  void respostaSemNumero() {
    ResultadoOrquestracao r = atalho.responder(termo("alíquota"), contexto);

    assertThat(r.texto()).startsWith("alíquota: ").doesNotContainPattern("\\d")
        .doesNotContainIgnoringCase("art.");
    assertThat(r.citacoes()).singleElement().satisfies(c ->
        assertThat(c.referencia()).isEqualTo("LC 214/2025, art. 14"));
  }

  @Test
  @DisplayName("#269: base de legislacao fora: a definicao sai sozinha, sem o fecho da fonte")
  void baseFora() {
    when(busca.buscarDispositivos(anyList(), any()))
        .thenThrow(new AssistenteIndisponivelException("base fora", null));

    ResultadoOrquestracao r = atalho.responder(termo("CBS"), contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.DEFINICAO_DO_GLOSSARIO);
    assertThat(r.texto()).startsWith("CBS (contribuição sobre bens e serviços): ")
        .doesNotContain(AtalhoDefinicao.COM_FONTE);
    assertThat(r.citacoes()).isEmpty();
  }

  @Test
  @DisplayName("#269: termo sem dispositivo curado não é respondido pelo atalho")
  void termoSemDispositivoRecusado() {
    GlossarioProdutor.Termo nfe = termo("NF-e");

    assertThatThrownBy(() -> atalho.responder(nfe, contexto))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NF-e");
    verify(busca, never()).buscarDispositivos(anyList(), any());
  }

  @Test
  @DisplayName("#269: todo termo do mapa existe no glossário, com definição e sem número")
  void mapaCoerenteComOGlossario() {
    assertThat(AtalhoDefinicao.termosComDispositivo())
        .hasSameSizeAs(AtalhoDefinicao.DISPOSITIVOS.keySet());
    assertThat(AtalhoDefinicao.dispositivosNaOrdemDoGlossario().keySet())
        .containsExactly("IBS", "CBS", "Funrural", "crédito", "apuração", "split payment",
            "alíquota", "base de cálculo", "fato gerador", "contribuinte", "crédito presumido",
            "imunidade", "não cumulatividade", "regime regular");
    assertThat(AtalhoDefinicao.DISPOSITIVOS.values())
        .allSatisfy(d -> assertThat(d).contains("|art. "));
  }

  @Test
  @DisplayName("#269: a pergunta de definição responde sem o modelo, então paga só a leitura")
  void respondeSemModelo() {
    assertThat(OrquestradorService.respondeSemModelo("o que é o IBS?")).isTrue();
    assertThat(OrquestradorService.respondeSemModelo("o que é a alíquota do IBS?")).isFalse();
  }

  private static String termoDe(String pergunta) {
    return AtalhoDefinicao.reconhecer(pergunta).orElseThrow().termo();
  }

  private static GlossarioProdutor.Termo termo(String nome) {
    return GlossarioProdutor.padrao().termos().stream().filter(t -> t.termo().equals(nome))
        .findFirst().orElseThrow();
  }

  private static TrechoRecuperado trecho(String chave) {
    String[] partes = chave.split("\\|");
    return new TrechoRecuperado(null, chave, partes[0], partes[1],
        "https://www.planalto.gov.br/", "Texto do " + partes[1] + ".", 0);
  }
}

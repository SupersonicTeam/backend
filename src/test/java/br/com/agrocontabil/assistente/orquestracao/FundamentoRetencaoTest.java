package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaLegislacao;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fundamento obrigatório das respostas sobre retenção (RN009, issue #145). */
class FundamentoRetencaoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 9, 15);

  private BuscaTextualLegislacaoService busca;
  private FundamentoRetencao fundamento;
  private ContextoAssistente contexto;

  @BeforeEach
  void setUp() {
    busca = mock(BuscaTextualLegislacaoService.class);
    fundamento = new FundamentoRetencao(busca);
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
  }

  private static ResultadoOrquestracao resposta(Intencao rota, String texto,
      List<CitacaoLegal> citacoes) {
    return new ResultadoOrquestracao(rota, texto, DesfechoInteracao.RESPONDIDA, citacoes, true);
  }

  private static TrechoRecuperado trecho(String norma, String dispositivo) {
    return new TrechoRecuperado(null, "id", norma, dispositivo, "https://planalto", "texto", 0);
  }

  @Test
  @DisplayName("Pergunta sobre retencao recebe o paragrafo e as citacoes das duas regras")
  void acrescentaAsDuasRegras() {
    when(busca.buscarDispositivos(eq(FundamentoRetencao.DISPOSITIVOS), eq(HOJE)))
        .thenReturn(List.of(trecho("Lei 8.212/1991", "art. 30, inciso IV"),
            trecho("LC 214/2025", "art. 31")));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.DADOS, "Em agosto foram retidos R$ 1.134,00.", List.of()),
        "Quanto de Funrural foi retido em agosto?", contexto);

    assertThat(r.texto()).startsWith("Em agosto foram retidos R$ 1.134,00.")
        .endsWith(FundamentoRetencao.TEXTO)
        .contains("Lei 8.212/1991", "LC 214/2025", "split payment");
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("Lei 8.212/1991, art. 30, inciso IV", "LC 214/2025, art. 31");
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    // Auditoria: os trechos citados pelo backend entram como os do RAG.
    assertThat(contexto.trechosCitados()).hasSize(2);
  }

  @Test
  @DisplayName("A funcao consultarRetencoes basta, mesmo sem a palavra na pergunta")
  void funcaoUsadaBasta() {
    contexto.registrarFonte("consultarRetencoes", Map.of("dataInicio", "2026-08-01"), false);

    assertThat(FundamentoRetencao.trataDeRetencao("Quanto fica no meu pagamento?", contexto))
        .isTrue();
  }

  @Test
  @DisplayName("Assunto reconhecido sem acento e em qualquer caixa; outros assuntos ficam de fora")
  void reconheceOAssunto() {
    for (String pergunta : List.of("Tenho RETENÇÃO nas vendas?", "o comprador retém algo?",
        "como funciona o split payment?", "quanto paguei de SENAR?", "valor retido em julho")) {
      assertThat(FundamentoRetencao.trataDeRetencao(pergunta, contexto)).as(pergunta).isTrue();
    }
    // Outra retencao que a RN009 nao cobre nao recebe o paragrafo do Funrural.
    for (String pergunta : List.of("Quanto vendi em agosto?", "qual o credito de IBS?",
        "posso retirar a nota?", "quanto de ICMS foi retido por substituicao?",
        "tenho IR retido na fonte?")) {
      assertThat(FundamentoRetencao.trataDeRetencao(pergunta, contexto)).as(pergunta).isFalse();
    }
  }

  @Test
  @DisplayName("'Nao encontrei fundamento' sai: ao lado do fundamento seria contraditorio")
  void substituiSemFundamento() {
    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.LEGISLACAO, ValidadorCitacao.SEM_FUNDAMENTO, List.of()),
        "Como funciona a retencao do Funrural?", contexto);

    assertThat(r.texto()).isEqualTo(FundamentoRetencao.TEXTO);
  }

  @Test
  @DisplayName("#343: no MISTO, 'sobre a parte de legislacao, nao encontrei fundamento' sai: o "
      + "fundamento das duas regras entra logo depois, com as citacoes")
  void substituiSemFundamentoDaParteLegal() {
    when(busca.buscarDispositivos(any(), any()))
        .thenReturn(List.of(trecho("Lei 8.212/1991", "art. 30, inciso IV")));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.MISTO, "Em 10/2026: 1 venda.\n\n"
            + OrquestradorService.SEM_FUNDAMENTO_PARTE_LEGAL, List.of()),
        "O comprador reteve Funrural na minha venda, esta certo?", contexto);

    assertThat(r.texto()).isEqualTo("Em 10/2026: 1 venda.\n\n" + FundamentoRetencao.TEXTO)
        .doesNotContain(OrquestradorService.SEM_FUNDAMENTO_PARTE_LEGAL);
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("Lei 8.212/1991, art. 30, inciso IV");
  }

  @Test
  @DisplayName("#343: 'nao consegui confirmar' sai com os trechos que ele apresentava; ficam so "
      + "as citacoes fixas das duas regras")
  void substituiTrechosSemConfirmacao() {
    CitacaoLegal relacionado = new CitacaoLegal("LC 214/2025", "art. 99", "u", "t");
    contexto.registrarTrechosRelacionados();
    when(busca.buscarDispositivos(any(), any()))
        .thenReturn(List.of(trecho("LC 214/2025", "art. 31")));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.LEGISLACAO, RotaLegislacao.TRECHOS_SEM_CONFIRMACAO,
            List.of(relacionado)),
        "Como funciona a retencao do Funrural?", contexto);

    assertThat(r.texto()).isEqualTo(FundamentoRetencao.TEXTO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 31");
  }

  @Test
  @DisplayName("#343 review: no MISTO com tratamento do produto e trechos relacionados, o "
      + "TextosDoBackend tira os relacionados e o FundamentoRetencao mantem a citacao do tratamento")
  void encadeadoComTextosDoBackend() {
    contexto.registrarFonte("totalComprasPorInsumo", Map.of(), false);
    contexto.registrarConsultaDeTabela("consultarTratamentoProduto", Map.of(), true);
    contexto.registrarTextoDoBackend("Soja: reducao de 60%.", List.of("LC 214/2025|art. 137"));
    contexto.registrarTrechosRelacionados();
    when(busca.buscarDispositivos(eq(List.of("LC 214/2025|art. 137")), any()))
        .thenReturn(List.of(trecho("LC 214/2025", "art. 137")));
    when(busca.buscarDispositivos(eq(FundamentoRetencao.DISPOSITIVOS), any()))
        .thenReturn(List.of(trecho("LC 214/2025", "art. 31")));
    ResultadoOrquestracao misto = resposta(Intencao.MISTO,
        "Em 10/2026: 1 venda.\n\n" + RotaLegislacao.TRECHOS_SEM_CONFIRMACAO,
        List.of(new CitacaoLegal("LC 214/2025", "art. 99", "u", "t")));

    ResultadoOrquestracao r = fundamento.aplicar(
        new TextosDoBackend(busca).aplicar(misto, contexto),
        "O comprador reteve Funrural na venda de soja; soja tem reducao?", contexto);

    assertThat(r.texto()).doesNotContain(RotaLegislacao.TRECHOS_SEM_CONFIRMACAO)
        .contains("Soja: reducao de 60%.").endsWith(FundamentoRetencao.TEXTO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 137", "LC 214/2025, art. 31");
  }

  @Test
  @DisplayName("#343: modelo fora do ar e lei sem trecho: 'estou sem o modelo e nao encontrei' sai "
      + "ao lado do fundamento das duas regras")
  void substituiFallbackSemTrecho() {
    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.LEGISLACAO,
            br.com.agrocontabil.assistente.fallback.ModoFallback.SEM_MODELO_SEM_TRECHO, List.of()),
        "Como funciona a retencao do Funrural?", contexto);

    assertThat(r.texto()).isEqualTo(FundamentoRetencao.TEXTO);
  }

  @Test
  @DisplayName("#258/#343: trechos que o modelo citou certo ficam, com a frase que os apresenta, "
      + "e as citacoes fixas entram depois")
  void trechosCitadosPeloModeloFicam() {
    CitacaoLegal citado = new CitacaoLegal("LC 214/2025", "art. 99", "u", "t");
    when(busca.buscarDispositivos(any(), any()))
        .thenReturn(List.of(trecho("LC 214/2025", "art. 31")));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.LEGISLACAO, RotaLegislacao.TRECHOS_SEM_CONFIRMACAO, List.of(citado)),
        "Como funciona a retencao do Funrural?", contexto);

    assertThat(r.texto()).isEqualTo(
        RotaLegislacao.TRECHOS_SEM_CONFIRMACAO + "\n\n" + FundamentoRetencao.TEXTO);
    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 99", "LC 214/2025, art. 31");
  }

  @Test
  @DisplayName("Citacoes da rota ficam na frente, sem repetir a mesma referencia")
  void naoRepeteCitacao() {
    CitacaoLegal daRota = new CitacaoLegal("LC 214/2025", "art. 31", "u", "t");
    when(busca.buscarDispositivos(any(), any()))
        .thenReturn(List.of(trecho("LC 214/2025", "art. 31"),
            trecho("Lei 9.528/1997", "art. 6º")));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.MISTO, "texto", List.of(daRota)), "split payment?", contexto);

    assertThat(r.citacoes()).extracting(CitacaoLegal::referencia)
        .containsExactly("LC 214/2025, art. 31", "Lei 9.528/1997, art. 6º");
  }

  @Test
  @DisplayName("Base fora do ar: o paragrafo segue sem as citacoes, a resposta nao cai")
  void baseForaDoAr() {
    when(busca.buscarDispositivos(any(), any()))
        .thenThrow(new AssistenteIndisponivelException("banco", null));

    ResultadoOrquestracao r = fundamento.aplicar(
        resposta(Intencao.DADOS, "texto", List.of()), "funrural retido?", contexto);

    assertThat(r.texto()).endsWith(FundamentoRetencao.TEXTO);
    assertThat(r.citacoes()).isEmpty();
  }

  @Test
  @DisplayName("Fora de escopo e assunto que nao e retencao passam intactos, sem consultar a base")
  void foraDoAssunto() {
    ResultadoOrquestracao fora = resposta(Intencao.FORA_DE_ESCOPO, "fora", List.of());
    ResultadoOrquestracao outro = resposta(Intencao.DADOS, "R$ 10,00", List.of());

    assertThat(fundamento.aplicar(fora, "funrural do vizinho e receita de bolo", contexto))
        .isSameAs(fora);
    assertThat(fundamento.aplicar(outro, "quanto vendi?", contexto)).isSameAs(outro);
    verifyNoInteractions(busca);
  }
}

package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.AssistenteProperties.ParticaoCasoEspecial;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/** Partição dos trechos de caso especial na busca de lei (issue #436). */
class BuscaLegislacaoServiceTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 7);

  /** Pergunta sem palavra de nenhum setor das faixas. */
  private static final String SEM_SETOR = "Quando o imposto da venda de feijão é devido?";

  private static Document doc(String id, String norma, String dispositivo, double score) {
    return Document.builder().id(id).text("texto " + id)
        .metadata(Map.of(MetadadosTrecho.NORMA, norma, MetadadosTrecho.DISPOSITIVO, dispositivo,
            MetadadosTrecho.URL, "https://exemplo"))
        .score(score).build();
  }

  /** Dez candidatos, do mais para o menos similar, com cinco de caso especial no meio. */
  private static List<Document> candidatos() {
    return List.of(
        doc("imovel", "LC 214/2025", "art. 252, inciso III", 0.80),
        doc("geral12", "LC 214/2025", "art. 12, § 2º", 0.79),
        doc("combustivel", "LC 214/2025", "art. 173", 0.78),
        doc("redacao227", "LC 227/2026", "art. 174 (redação do art. 253, § 1º)", 0.77),
        doc("geral47", "LC 214/2025", "art. 47", 0.76),
        doc("ec", "EC 132/2023", "art. 1º (redação do art. 156-A)", 0.75),
        doc("importacao", "LC 214/2025", "art. 63", 0.74),
        doc("geral49", "LC 214/2025", "art. 49", 0.73),
        doc("financeiro", "LC 214/2025", "art. 218-A", 0.72),
        doc("lei8212", "Lei 8.212/1991", "art. 25", 0.71));
  }

  private static List<String> ids(List<?> lista) {
    return lista.stream()
        .map(x -> x instanceof Document d ? d.getId() : ((TrechoRecuperado) x).id())
        .toList();
  }

  private static BuscaLegislacaoService servico(VectorStore store, ParticaoCasoEspecial particao) {
    AssistenteProperties p = new AssistenteProperties();
    p.getRag().setParticaoCasoEspecial(particao);
    return new BuscaLegislacaoService(store, p);
  }

  @Test
  @DisplayName("Regimes específicos: os de caso especial sem o setor descem, na ordem, sem sair")
  void particaoRegimesEspecificos() {
    List<Document> r = BuscaLegislacaoService.particionar(
        candidatos(), SEM_SETOR, CasoEspecial.REGIMES_ESPECIFICOS, 10);

    assertThat(ids(r)).containsExactly("geral12", "geral47", "ec", "importacao", "geral49",
        "lei8212", "imovel", "combustivel", "redacao227", "financeiro");
  }

  @Test
  @DisplayName("O corte no top-k vem depois da partição")
  void corteDepoisDaParticao() {
    List<Document> r = BuscaLegislacaoService.particionar(
        candidatos(), SEM_SETOR, CasoEspecial.REGIMES_ESPECIFICOS, 5);

    assertThat(ids(r)).containsExactly("geral12", "geral47", "ec", "importacao", "geral49");
  }

  @Test
  @DisplayName("Setor citado na pergunta: os trechos daquela faixa ficam no lugar")
  void setorCitadoNaoDesce() {
    List<Document> r = BuscaLegislacaoService.particionar(
        candidatos(), "Como fica o ALUGUEL de um galpão?", CasoEspecial.REGIMES_ESPECIFICOS, 10);

    assertThat(ids(r)).containsExactly("imovel", "geral12", "redacao227", "geral47", "ec",
        "importacao", "geral49", "lei8212", "combustivel", "financeiro");
  }

  @Test
  @DisplayName("Ampliada: a importação também desce quando a pergunta não fala dela")
  void particaoAmpliada() {
    List<Document> r = BuscaLegislacaoService.particionar(
        candidatos(), SEM_SETOR, CasoEspecial.ampliada(), 10);

    assertThat(ids(r)).containsExactly("geral12", "geral47", "ec", "geral49", "lei8212",
        "imovel", "combustivel", "redacao227", "importacao", "financeiro");
  }

  @Test
  @DisplayName("Poucos trechos gerais: os de caso especial completam o top-k, na ordem")
  void casoEspecialCompletaOTopK() {
    List<Document> poucos = List.of(
        doc("combustivel", "LC 214/2025", "art. 173", 0.80),
        doc("geral12", "LC 214/2025", "art. 12", 0.70),
        doc("imovel", "LC 214/2025", "art. 252", 0.60));

    assertThat(ids(BuscaLegislacaoService.particionar(
        poucos, SEM_SETOR, CasoEspecial.REGIMES_ESPECIFICOS, 5)))
        .containsExactly("geral12", "combustivel", "imovel");
  }

  @Test
  @DisplayName("Desligado: pede o top-k direto e devolve a ordem da busca, sem partição")
  void desligadoComoAntes() {
    VectorStore store = mock(VectorStore.class);
    when(store.similaritySearch(any(SearchRequest.class))).thenReturn(candidatos().subList(0, 5));

    List<TrechoRecuperado> r = servico(store, ParticaoCasoEspecial.DESLIGADO).buscar(SEM_SETOR, HOJE);

    ArgumentCaptor<SearchRequest> requisicao = ArgumentCaptor.forClass(SearchRequest.class);
    verify(store).similaritySearch(requisicao.capture());
    assertThat(requisicao.getValue().getTopK()).isEqualTo(5);
    assertThat(requisicao.getValue().getSimilarityThreshold()).isEqualTo(0.55);
    assertThat(ids(r)).containsExactly("imovel", "geral12", "combustivel", "redacao227", "geral47");
    assertThat(r).extracting(TrechoRecuperado::marca).containsExactly("T1", "T2", "T3", "T4", "T5");
  }

  @Test
  @DisplayName("Ligada: 4 x top-k candidatos, mesmo limiar e mesmo filtro; corta e só então numera")
  void ligadaPedeCandidatosComOMesmoLimiarEFiltro() {
    VectorStore desligada = mock(VectorStore.class);
    VectorStore ligada = mock(VectorStore.class);
    when(desligada.similaritySearch(any(SearchRequest.class))).thenReturn(candidatos());
    when(ligada.similaritySearch(any(SearchRequest.class))).thenReturn(candidatos());

    servico(desligada, ParticaoCasoEspecial.DESLIGADO).buscar(SEM_SETOR, HOJE);
    List<TrechoRecuperado> r =
        servico(ligada, ParticaoCasoEspecial.REGIMES_ESPECIFICOS).buscar(SEM_SETOR, HOJE);

    ArgumentCaptor<SearchRequest> antes = ArgumentCaptor.forClass(SearchRequest.class);
    ArgumentCaptor<SearchRequest> depois = ArgumentCaptor.forClass(SearchRequest.class);
    verify(desligada).similaritySearch(antes.capture());
    verify(ligada).similaritySearch(depois.capture());
    assertThat(depois.getValue().getTopK()).isEqualTo(5 * BuscaLegislacaoService.FATOR_CANDIDATOS);
    assertThat(depois.getValue().getSimilarityThreshold())
        .isEqualTo(antes.getValue().getSimilarityThreshold());
    assertThat(depois.getValue().getFilterExpression())
        .isEqualTo(antes.getValue().getFilterExpression());
    assertThat(depois.getValue().getQuery()).isEqualTo(antes.getValue().getQuery());
    assertThat(ids(r)).containsExactly("geral12", "geral47", "ec", "importacao", "geral49");
    assertThat(r).extracting(TrechoRecuperado::marca).containsExactly("T1", "T2", "T3", "T4", "T5");
    assertThat(r.get(0).similaridade()).isEqualTo(0.79);
  }

  @Test
  @DisplayName("Candidatos no máximo 40: fator 4 com o top-k até 10")
  void candidatosNoMaximo40() {
    assertThat(BuscaLegislacaoService.FATOR_CANDIDATOS * 10).isLessThanOrEqualTo(40);
  }

  @Test
  @DisplayName("Cada valor da propriedade escolhe a sua tabela")
  void faixasPorValor() {
    assertThat(BuscaLegislacaoService.faixas(ParticaoCasoEspecial.DESLIGADO)).isEmpty();
    assertThat(BuscaLegislacaoService.faixas(ParticaoCasoEspecial.REGIMES_ESPECIFICOS))
        .isSameAs(CasoEspecial.REGIMES_ESPECIFICOS);
    assertThat(BuscaLegislacaoService.faixas(ParticaoCasoEspecial.AMPLIADA))
        .isSameAs(CasoEspecial.ampliada());
  }
}

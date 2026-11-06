package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Seleção por artigo distinto da busca de lei (#269). */
class BuscaLegislacaoArtigosDistintosTest {

  private static Document doc(String id, String norma, String dispositivo) {
    return new Document(id, "texto " + id, Map.of(MetadadosTrecho.NORMA, norma,
        MetadadosTrecho.DISPOSITIVO, dispositivo));
  }

  @Test
  @DisplayName("Até 2 trechos por artigo; a ordem de similaridade é mantida")
  void doisTrechosPorArtigo() {
    List<Document> candidatos = List.of(
        doc("1", "LC 214/2025", "art. 254, inciso I"), doc("2", "LC 214/2025", "art. 254, inciso II"),
        doc("3", "LC 214/2025", "art. 254"), doc("4", "LC 214/2025", "art. 10"),
        doc("5", "LC 227/2026", "art. 10, § 1º"), doc("6", "LC 214/2025", "art. 10, § 2º"));

    List<Document> escolhidos = BuscaLegislacaoService.melhoresPorArtigo(candidatos, 5);

    assertThat(escolhidos).extracting(Document::getId).containsExactly("1", "2", "4", "5", "6");
  }

  @Test
  @DisplayName("Para no limite de artigos; o mesmo artigo de outra norma é outro artigo")
  void limite() {
    List<Document> candidatos = List.of(
        doc("1", "LC 214/2025", "art. 1º"), doc("2", "LC 214/2025", "art. 2º"),
        doc("3", "LC 214/2025", "art. 3º"), doc("4", "LC 227/2026", "art. 1º"));

    assertThat(BuscaLegislacaoService.melhoresPorArtigo(candidatos, 3))
        .extracting(Document::getId).containsExactly("1", "2", "3");
    assertThat(BuscaLegislacaoService.melhoresPorArtigo(candidatos, 10))
        .extracting(Document::getId).containsExactly("1", "2", "3", "4");
  }

  @Test
  @DisplayName("Item de anexo não se funde: cada item é um assunto")
  void anexoPorItem() {
    List<Document> candidatos = List.of(
        doc("1", "LC 214/2025", "Anexo I, item 2"), doc("2", "LC 214/2025", "Anexo I, item 3"));

    assertThat(BuscaLegislacaoService.melhoresPorArtigo(candidatos, 5)).hasSize(2);
  }

  @Test
  @DisplayName("Sem candidato: lista vazia")
  void vazio() {
    assertThat(BuscaLegislacaoService.melhoresPorArtigo(List.of(), 5)).isEmpty();
    assertThat(BuscaLegislacaoService.melhoresPorArtigo(null, 5)).isEmpty();
  }
}

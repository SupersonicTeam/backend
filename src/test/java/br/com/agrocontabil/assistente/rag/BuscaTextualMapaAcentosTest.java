package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * O mapa de acentos da busca textual (issue #146) tem de ser o mesmo da coluna gerada do changeSet
 * 0027. Se divergirem, a pergunta e a lei viram lexemas diferentes e a busca deixa de casar em
 * silêncio — este teste roda sem banco e pega a divergência.
 */
class BuscaTextualMapaAcentosTest {

  @Test
  @DisplayName("#146: mapa de acentos do service igual ao do changeSet 0027, posicao a posicao")
  void mapaIgualAoDoChangeSet() throws IOException {
    String changeSet = new ClassPathResource(
        "db/changelog/changes/0027-legislacao-tsv-sem-acento.xml")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(changeSet)
        .contains("'" + BuscaTextualLegislacaoService.COM_ACENTO + "'")
        .contains("'" + BuscaTextualLegislacaoService.SEM_ACENTO + "'");
    assertThat(BuscaTextualLegislacaoService.COM_ACENTO)
        .hasSameSizeAs(BuscaTextualLegislacaoService.SEM_ACENTO);
  }

  @Test
  @DisplayName("#170: NCM casa como codigo inteiro, subposicao e posicao, nunca outro item")
  void alternativasDoNcm() {
    java.util.regex.Pattern padrao = java.util.regex.Pattern.compile("(^|[^0-9.])("
        + BuscaTextualLegislacaoService.alternativasDoNcm("31052000") + ")");

    assertThat(padrao.matcher("Item 4: Adubos - 3105.20.00").find()).isTrue();
    assertThat(padrao.matcher("Adubos da subposicao 3105.20, exceto").find()).isTrue();
    assertThat(padrao.matcher("Produtos da posicao 31.05").find()).isTrue();
    assertThat(padrao.matcher("Produtos da posicao 3105 do capitulo").find()).isTrue();
    assertThat(padrao.matcher("NCM 31052000").find()).isTrue();
    // Outro item da mesma subposicao, outra posicao com prefixo parecido, numero maior.
    assertThat(padrao.matcher("Item 5: Outros - 3105.20.10").find()).isFalse();
    assertThat(padrao.matcher("Item 6: Outros - 3105.10.00").find()).isFalse();
    assertThat(padrao.matcher("Item 7: Outros - 13105.20.00").find()).isFalse();
    assertThat(padrao.matcher("valor de 3105.201").find()).isFalse();
  }
}

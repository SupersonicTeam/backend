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
}

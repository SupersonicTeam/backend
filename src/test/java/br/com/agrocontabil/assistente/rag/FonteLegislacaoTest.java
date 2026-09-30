package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A fonte da legislação precisa ser o texto oficial do Planalto (issue #156). */
class FonteLegislacaoTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 9, 29);

  @Test
  @DisplayName("Link https do Planalto e aceito")
  void planaltoAceito() {
    FonteLegislacao fonte = new FonteLegislacao(" LC 214/2025 ",
        "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm", HOJE, HOJE);

    assertThat(fonte.norma()).isEqualTo("LC 214/2025");
  }

  @Test
  @DisplayName("Outro dominio, http ou URL invalida: recusado (a citacao mostraria link nao oficial)")
  void foraDoPlanaltoRecusado() {
    for (String url : new String[] {
        "http://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm",
        "https://planalto.gov.br.exemplo.com/lcp214.htm",
        "https://www.exemplo.com/lcp214.htm",
        "nao e url"}) {
      assertThatThrownBy(() -> new FonteLegislacao("LC 214/2025", url, HOJE, HOJE))
          .as(url).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("Norma obrigatoria")
  void normaObrigatoria() {
    assertThatThrownBy(() -> new FonteLegislacao(" ",
        "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm", HOJE, HOJE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

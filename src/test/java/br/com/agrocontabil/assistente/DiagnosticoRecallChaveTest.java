package br.com.agrocontabil.assistente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Chave de artigo do recall manual: a mesma regra do {@code busca_contagem.py} (#436). */
class DiagnosticoRecallChaveTest {

  @Test
  void cortaNaPrimeiraVirgula() {
    assertThat(DiagnosticoRecallLeiManual.artigo("LC 214/2025", "art. 4º, § 2º, inciso I"))
        .isEqualTo("LC 214/2025 art. 4º");
  }

  @Test
  void tiraOParenteseFinal() {
    assertThat(DiagnosticoRecallLeiManual.artigo("LC 227/2026", "art. 174 (redação do art. 6º)"))
        .isEqualTo("LC 227/2026 art. 174 (redação do art. 6º");
  }

  @Test
  void anexoVaiAteAVirgula() {
    assertThat(DiagnosticoRecallLeiManual.artigo("LC 214/2025", "Anexo IX, item 7"))
        .isEqualTo("LC 214/2025 Anexo IX");
  }

  @Test
  void espacoUnicodeEDecomposicaoComoNoPython() {
    assertThat(DiagnosticoRecallLeiManual.artigo("Lei 8.212/1991", "art. 25 ,  inciso I"))
        .isEqualTo("Lei 8.212/1991 art. 25");
    assertThat(DiagnosticoRecallLeiManual.artigo("LC 227/2026",
        "art. 174 (redação do art. 6º)"))
        .isEqualTo("LC 227/2026 art. 174 (redação do art. 6º");
  }

  @Test
  void chaveJaPronta() {
    assertThat(DiagnosticoRecallLeiManual.artigo("LC 214/2025 art. 12", ""))
        .isEqualTo("LC 214/2025 art. 12");
  }
}

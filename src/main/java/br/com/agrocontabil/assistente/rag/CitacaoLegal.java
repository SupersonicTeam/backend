package br.com.agrocontabil.assistente.rag;

/**
 * Citação de lei que fundamenta uma resposta da Ceres (issue #156). Montada <strong>pelo
 * backend</strong> a partir dos metadados do trecho recuperado — o modelo só escreve a marca
 * {@code [Tn]}, nunca a referência.
 *
 * @param norma       ex.: {@code LC 214/2025}
 * @param dispositivo ex.: {@code art. 138, § 1º, inciso IV}
 * @param url         link oficial (Planalto)
 * @param trecho      texto do dispositivo
 */
public record CitacaoLegal(String norma, String dispositivo, String url, String trecho) {

  /** Referência legível, ex.: {@code LC 214/2025, art. 138, § 1º}. */
  public String referencia() {
    return norma + ", " + dispositivo;
  }
}

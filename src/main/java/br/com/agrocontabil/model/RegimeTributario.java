package br.com.agrocontabil.model;

/**
 * Regime de tributação do produtor (o do imposto de renda e do Simples Nacional).
 *
 * <p>Não representa a situação do IBS/CBS (contribuinte do regime regular, optante ou não
 * contribuinte, LC 214/2025, arts. 164 e 165), que é a issue #281.
 */
public enum RegimeTributario {
  MEI,
  SIMPLES,
  PRESUMIDO,
  REAL;

  /**
   * O regime cabe a um produtor pessoa física? Simples Nacional e MEI não (issue #298): são de
   * microempresa e empresa de pequeno porte, que são pessoas jurídicas ou o empresário individual
   * inscrito no CNPJ (LC 123/2006, art. 3º), e o produtor rural pessoa física não se inscreve
   * neles.
   *
   * @return {@code false} para {@code SIMPLES} e {@code MEI}
   */
  public boolean admitidoParaPessoaFisica() {
    return this != SIMPLES && this != MEI;
  }
}

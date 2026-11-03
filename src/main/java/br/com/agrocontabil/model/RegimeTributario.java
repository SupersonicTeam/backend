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
   * O regime cabe a um produtor pessoa física? Simples Nacional e MEI não (issue #298): o Simples
   * é da microempresa e da empresa de pequeno porte (LC 123/2006, art. 3º) e o MEI, do empresário
   * individual inscrito no CNPJ (art. 18-A); o produtor rural pessoa física não se inscreve neles.
   *
   * @return {@code false} para {@code SIMPLES} e {@code MEI}
   */
  public boolean admitidoParaPessoaFisica() {
    return this != SIMPLES && this != MEI;
  }

  /**
   * Regra única da combinação proibida (issue #298): pessoa física com {@code SIMPLES} ou
   * {@code MEI}. Usada pela recusa do {@code Produtor}, pelo calendário e pelo aviso do seed.
   *
   * @param tipo   tipo do produtor
   * @param regime regime gravado
   * @return {@code true} se a combinação não cabe
   */
  public static boolean incompativel(TipoProdutor tipo, RegimeTributario regime) {
    return tipo == TipoProdutor.PESSOA_FISICA && regime != null
        && !regime.admitidoParaPessoaFisica();
  }
}

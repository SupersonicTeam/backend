package br.com.agrocontabil.assistente.orquestracao;

import java.util.regex.Pattern;

/**
 * Sinal de que a pergunta pode ser sobre o tratamento de IBS/CBS de um produto (issue #443):
 * redução, alíquota, desconto, isenção ou imposto menor, junto de IBS, CBS, imposto ou reforma.
 *
 * <p>O sinal só decide se vale <strong>tentar</strong> a tabela oficial depois da rota; quem
 * diz se há um produto na pergunta (e qual) é o modelo, numa chamada só com a função da tabela
 * ({@code RotaDados#consultarTratamento}). Pergunta de regra geral, de serviço ou de setor passa
 * pelo sinal e não muda nada: sem produto achado na tabela, a resposta da rota fica como está.
 */
public final class PerguntaTratamentoProduto {

  /** Como o produtor pergunta pelo tratamento: redução, alíquota, isenção, imposto menor... */
  private static final Pattern TRATAMENTO = Pattern.compile("\\b(reducao|reduzid[ao]s?|reduz"
      + "|desconto|descontos|aliquotas?|isent[oa]s?|isencao|zerad[oa]s?|zero|menor|menos"
      + "|cheio|cheia|integral|mais barat[oa]|beneficio|incide|incidem|incidencia|cesta basica)\\b");

  /** O tributo de que se fala. */
  private static final Pattern TRIBUTO = Pattern.compile("\\b(ibs|cbs|impostos?|tributos?"
      + "|tributacao|reforma|aliquotas?)\\b");

  private PerguntaTratamentoProduto() {}

  /**
   * A pergunta fala de tratamento de imposto e pode nomear um produto.
   *
   * @param pergunta pergunta do usuário
   * @return {@code true} se vale consultar a tabela oficial
   */
  public static boolean parece(String pergunta) {
    String texto = RegrasClassificacao.normalizar(pergunta);
    return TRATAMENTO.matcher(texto).find() && TRIBUTO.matcher(texto).find();
  }
}

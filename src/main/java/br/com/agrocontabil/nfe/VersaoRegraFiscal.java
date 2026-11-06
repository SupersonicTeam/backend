package br.com.agrocontabil.nfe;

/**
 * Versão da regra fiscal que gera o XML da NF-e (issue #389): a soma do que o
 * {@link FiscalRulesValidator} exige e do que o {@link NotaFiscalNfeMapper} escreve.
 *
 * <p>Gravada em {@code nota_fiscal.versao_regra_fiscal} na geração do XML, em um ponto só
 * ({@code GeracaoNotaFiscalService}, que a NF-e, a NFA e o seed da demonstração atravessam). O
 * {@code enviar-sefaz} recusa (409) o XML de versão menor, ou sem marca, em vez de transmitir um
 * documento que as regras de hoje não gerariam.
 *
 * <p><strong>Regra de manutenção:</strong> mudou o mapper ou o validador de um jeito que altera o
 * XML ou o que ele precisa ter (grupo novo, CST, cClassTrib admitido, totais), suba
 * {@link #ATUAL}. Mudança que não altera o XML (mensagem, sugestão, log) não sobe. Subir a
 * constante faz toda nota em {@code XML_GERADO} voltar a editável no próximo envio.
 */
public final class VersaoRegraFiscal {

  /**
   * Versão atual. 1 = grupo de ICMS por item (#377), leite in natura e UHT (#374, #381) e
   * cClassTrib informado admitido para o NCM (#375).
   */
  public static final int ATUAL = 1;

  private VersaoRegraFiscal() {}

  /**
   * Diz se um XML gerado na versão informada ficou para trás.
   *
   * @param versaoDoXml versão gravada na nota (nula = gerado antes da marca)
   * @return {@code true} se o XML precisa ser gerado de novo
   */
  public static boolean desatualizada(Integer versaoDoXml) {
    return versaoDoXml == null || versaoDoXml < ATUAL;
  }
}

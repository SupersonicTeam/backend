package br.com.agrocontabil.service;

/**
 * Porta do antivírus dos anexos das pendências (issue #227). A implementação é escolhida no
 * {@code config/AntivirusConfig}: {@link ClamdCliente} (ClamAV por TCP) ou, só em dev/test, uma
 * que não verifica.
 *
 * <p>Nunca é chamada dentro de transação: é I/O de rede e pode levar segundos num arquivo de
 * 5 MB.
 */
@FunctionalInterface
public interface VerificacaoAntivirus {

  /**
   * Verifica o arquivo.
   *
   * @param conteudo bytes do arquivo
   * @return limpo ou infectado (com o nome da assinatura)
   * @throws AntivirusIndisponivelException o antivírus não respondeu ou recusou o arquivo: o
   *     anexo fica em verificação, nunca é liberado
   */
  ResultadoVerificacao verificar(byte[] conteudo);

  /**
   * Resultado de uma verificação concluída.
   *
   * @param infectado  se o antivírus achou ameaça
   * @param assinatura nome da assinatura achada (só quando infectado)
   */
  record ResultadoVerificacao(boolean infectado, String assinatura) {

    /** Sem ameaça. */
    public static ResultadoVerificacao limpo() {
      return new ResultadoVerificacao(false, null);
    }

    /**
     * Ameaça encontrada.
     *
     * @param assinatura nome da assinatura
     * @return o resultado
     */
    public static ResultadoVerificacao infectado(String assinatura) {
      return new ResultadoVerificacao(true, assinatura);
    }
  }
}

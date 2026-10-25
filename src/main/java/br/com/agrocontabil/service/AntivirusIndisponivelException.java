package br.com.agrocontabil.service;

/**
 * O antivírus não deu veredito (issue #227). Não chega ao cliente: o anexo fica
 * {@code EM_VERIFICACAO} e o job tenta de novo.
 *
 * <p>{@link #isForaDoAr()} separa a queda (conexão recusada, tempo esgotado: o próximo arquivo
 * também falharia, e o job para o lote) da recusa de um arquivo só (o clamd respondeu
 * {@code ERROR}: o job segue para o próximo).
 */
public class AntivirusIndisponivelException extends RuntimeException {

  private final boolean foraDoAr;

  /**
   * Cria a exceção.
   *
   * @param mensagem o que houve (sem conteúdo do arquivo)
   * @param causa    erro de I/O, se houver
   * @param foraDoAr se o antivírus caiu (e não só recusou este arquivo)
   */
  public AntivirusIndisponivelException(String mensagem, Throwable causa, boolean foraDoAr) {
    super(mensagem, causa);
    this.foraDoAr = foraDoAr;
  }

  /** Se o antivírus está fora do ar (o lote do job para). */
  public boolean isForaDoAr() {
    return foraDoAr;
  }
}

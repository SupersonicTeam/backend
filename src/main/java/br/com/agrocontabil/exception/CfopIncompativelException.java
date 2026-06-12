package br.com.agrocontabil.exception;

/**
 * Lançada quando o CFOP de um item é incompatível com o tipo de operação da nota
 * (entrada/saída ou âmbito interno/interestadual).
 *
 * <p>Diferente das exceções de emissão (422), esta é uma falha de validação do
 * request de criação/atualização — mapeada para HTTP 400 pelo
 * {@code GlobalExceptionHandler}. A mensagem é descritiva e segura para o cliente
 * (não contém dado sensível).
 *
 * @see br.com.agrocontabil.service.CfopOperacaoValidator
 */
public class CfopIncompativelException extends RuntimeException {

  public CfopIncompativelException(String message) {
    super(message);
  }
}

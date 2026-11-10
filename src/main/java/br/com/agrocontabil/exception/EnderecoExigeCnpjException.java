package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O endereço próprio exige o CNPJ do escritório (issue #464) — HTTP 422. Vale ao gravar o slug e
 * ao apagar o CNPJ de um escritório que tem endereço.
 *
 * <p>Reduz o risco de publicar o nome de uma pessoa física: o escritório unipessoal nasce com o
 * nome do contador, e o slug fica público.
 */
public class EnderecoExigeCnpjException extends DomainException {

  private static final String MENSAGEM = "O endereço próprio exige o CNPJ do escritório.";

  /**
   * Cria a exceção.
   *
   * @param sugestao o que fazer (gravar o CNPJ, ou remover o endereço antes de apagá-lo)
   */
  public EnderecoExigeCnpjException(String sugestao) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ENDERECO_EXIGE_CNPJ.code(), "cnpj", MENSAGEM, sugestao, null, null)));
  }
}

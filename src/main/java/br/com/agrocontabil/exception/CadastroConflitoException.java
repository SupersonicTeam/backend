package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O cadastro esbarrou num e-mail ou CPF/CNPJ que já existe (issue #205). HTTP 409.
 *
 * <p>A mensagem é a mesma nos dois casos e não repete os dados recebidos: dizer "este e-mail já
 * tem conta" transformaria o cadastro numa consulta de quem é cliente.
 *
 * <p>Também cobre o CPF/CNPJ de um produtor ou contador que já existe <em>sem</em> credencial: o
 * cadastro nunca "reivindica" uma identidade preexistente, porque bastaria saber o CPF de alguém
 * para tomar a conta e os dados fiscais dele.
 */
public class CadastroConflitoException extends DomainException {

  private static final String MENSAGEM =
      "Nao foi possivel concluir o cadastro com os dados informados.";

  private static final String SUGESTAO =
      "Se voce ja tem conta, entre com seu e-mail ou recupere a senha. Se o seu CPF/CNPJ ja foi "
          + "cadastrado pelo escritorio de contabilidade, peca a ele o acesso.";

  public CadastroConflitoException() {
    super(
        HttpStatus.CONFLICT,
        MENSAGEM,
        List.of(new ErrorDetail(
            ErrorCode.CADASTRO_CONFLITO.code(), null, MENSAGEM, SUGESTAO, null, null)));
  }
}

package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O escritório é pessoal (issue #519) — HTTP 409. A conta pessoal é de um usuário só: não convida
 * equipe nem grava endereço próprio. Para isso, o administrador torna o escritório empresarial.
 */
public class EscritorioPessoalException extends DomainException {

  /**
   * Cria a exceção.
   *
   * @param mensagem o que o escritório pessoal não pode fazer (texto para o cliente)
   */
  public EscritorioPessoalException(String mensagem) {
    super(HttpStatus.CONFLICT, mensagem, List.of(new ErrorDetail(
        ErrorCode.ESCRITORIO_PESSOAL.code(),
        null,
        mensagem,
        "Torne o escritório empresarial (POST /api/escritorio/tornar-empresarial), com o CNPJ,"
            + " o telefone e o endereço.",
        null,
        null)));
  }
}

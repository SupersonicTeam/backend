package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O contador não pode trocar de escritório pelo convite: o atual tem clientes ou outros membros
 * (issue #228) — HTTP 409. Contador num escritório só (v1).
 */
public class EscritorioAtualEmUsoException extends DomainException {

  private static final String MENSAGEM =
      "O seu escritório atual tem clientes ou equipe; não dá para trocar pelo do convite.";

  /** Cria a exceção. */
  public EscritorioAtualEmUsoException() {
    super(HttpStatus.CONFLICT, MENSAGEM, List.of(new ErrorDetail(
        ErrorCode.ESCRITORIO_ATUAL_EM_USO.code(),
        null,
        MENSAGEM,
        "Encerre a carteira e saia da equipe atual antes de aceitar.",
        null,
        null)));
  }
}

package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * O contador não pode trocar de escritório pelo convite (HTTP 409): o atual tem clientes ou
 * outros membros (issue #228), ou ele é o administrador de um escritório empresarial (#519).
 * Contador num escritório só (v1).
 */
public class EscritorioAtualEmUsoException extends DomainException {

  private static final String MENSAGEM =
      "O seu escritório atual tem clientes ou equipe; não dá para trocar pelo do convite.";

  private static final String SUGESTAO =
      "Encerre a carteira e saia da equipe atual antes de aceitar.";

  private static final String MENSAGEM_ADMINISTRADOR =
      "O administrador de um escritório empresarial não entra em outra equipe.";

  private static final String SUGESTAO_ADMINISTRADOR =
      "Continue no escritório que você administra; com equipe, outro administrador pode "
          + "remover você dele antes do aceite.";

  /** Cria a exceção do escritório com clientes ou equipe. */
  public EscritorioAtualEmUsoException() {
    this(MENSAGEM, SUGESTAO);
  }

  private EscritorioAtualEmUsoException(String mensagem, String sugestao) {
    super(HttpStatus.CONFLICT, mensagem, List.of(new ErrorDetail(
        ErrorCode.ESCRITORIO_ATUAL_EM_USO.code(),
        null,
        mensagem,
        sugestao,
        null,
        null)));
  }

  /**
   * O contador administra um escritório empresarial: não entra em outra equipe, mesmo sozinho e
   * sem clientes (#519).
   *
   * @return a exceção
   */
  public static EscritorioAtualEmUsoException administradorDeEmpresarial() {
    return new EscritorioAtualEmUsoException(MENSAGEM_ADMINISTRADOR, SUGESTAO_ADMINISTRADOR);
  }
}

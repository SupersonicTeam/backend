package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.security.PapelUsuario;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a identidade está autenticada, mas o papel dela não pode executar a operação.
 * HTTP 403 — issue #93.
 *
 * <p><strong>Por que 403 aqui e 404 no anti-IDOR.</strong> São perguntas diferentes. O anti-IDOR
 * responde "este recurso é seu?" e usa 404 de propósito, para não revelar que a nota ou a guia de
 * outro produtor existe. Esta exceção responde "seu papel faz este tipo de operação?" — e a
 * resposta não expõe dado algum de terceiro: o cliente já sabe qual é o próprio papel, e esconder o
 * motivo só transformaria um erro de integração em uma caça ao bug.
 */
public class PapelNaoAutorizadoException extends DomainException {

  private static final String SUGESTAO =
      "Autentique-se com um usuário que tenha o papel exigido por este recurso.";

  /**
   * Cria a exceção para um papel que não atende à operação.
   *
   * @param papelAtual   papel da identidade autenticada
   * @param papelExigido papel que a operação requer
   */
  public PapelNaoAutorizadoException(PapelUsuario papelAtual, PapelUsuario papelExigido) {
    super(
        HttpStatus.FORBIDDEN,
        mensagem(papelExigido),
        List.of(
            new ErrorDetail(
                ErrorCode.PAPEL_NAO_AUTORIZADO.code(),
                null,
                mensagem(papelExigido),
                SUGESTAO,
                papelAtual.name(),
                papelExigido.name())));
  }

  private static String mensagem(PapelUsuario papelExigido) {
    return "Operação restrita ao papel " + papelExigido.name() + ".";
  }
}

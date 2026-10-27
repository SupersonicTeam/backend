package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando a requisição não carrega uma identidade de usuário válida. HTTP 401.
 *
 * <p>Cobre dois casos, deliberadamente indistinguíveis para o cliente (issue #92):
 *
 * <ul>
 *   <li>nenhum mecanismo de identidade está ativo — é o que acontece fora dos profiles
 *       {@code dev}/{@code homolog}, onde o {@code MockIdentidadeFilter} não é registrado. Antes
 *       disso a API respondia <strong>500</strong> em todo endpoint autenticado, porque a
 *       {@code IllegalStateException} do provider caía na rede de segurança do handler genérico;
 *   <li>a identidade declarada no header {@code X-Usuario-Id} não resolve (UUID malformado ou
 *       produtor inexistente). Responder 401 — e não 404 — evita confirmar quais ids existem.
 * </ul>
 *
 * <p>A mensagem não repete o valor recebido no header: refletir entrada arbitrária na resposta não
 * ajuda o cliente legítimo e serve ao ilegítimo.
 */
public class NaoAutenticadoException extends DomainException {

  /**
   * Desafio de todo 401 (RFC 9110 §15.5.2 e RFC 6750 §3), o mesmo na cadeia de filtros e no
   * {@code GlobalExceptionHandler}. Desde a Sprint 8 (#206) o esquema é {@code Bearer}; o header
   * {@code X-Usuario-Id} segue aceito em dev (só nele desde a #221), mas não é anunciado como esquema.
   */
  public static final String DESAFIO = "Bearer realm=\"agrocontabil\"";

  /** Desafio do token recusado (expirado, adulterado, revogado) — RFC 6750 §3.1. */
  public static final String DESAFIO_TOKEN_INVALIDO =
      "Bearer realm=\"agrocontabil\", error=\"invalid_token\"";

  static final String MENSAGEM = "Requisicao sem identidade de usuario valida.";

  /** Sugestão de todo 401 de identidade ausente ou inválida. */
  public static final String SUGESTAO =
      "Entre com e-mail e senha em POST /api/auth/login e envie o token no header "
          + "Authorization: Bearer <token>.";

  public NaoAutenticadoException() {
    super(
        HttpStatus.UNAUTHORIZED,
        MENSAGEM,
        List.of(
            new ErrorDetail(
                ErrorCode.NAO_AUTENTICADO.code(), null, MENSAGEM, SUGESTAO, null, null)));
  }
}

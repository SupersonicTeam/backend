package br.com.agrocontabil.security;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.exception.NaoAutenticadoException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Escreve o envelope {@code ApiError} quando quem barra a requisição é a <em>camada de
 * segurança</em>, e não um serviço — issue #93.
 *
 * <p>Sem isto, sair do {@code permitAll()} quebraria o contrato de erro da issue #66: as negativas
 * do Spring Security acontecem <strong>antes</strong> do controller, logo nunca passam pelo
 * {@code @RestControllerAdvice} e sairiam como a página de erro padrão do container — um corpo que
 * o Angular não sabe ler.
 *
 * <p>São os dois casos que a cadeia de filtros decide sozinha:
 *
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} → <strong>401</strong> {@code NAO_AUTENTICADO}, com o
 *       desafio em {@code WWW-Authenticate} (RFC 9110 §15.5.2), igual ao que o
 *       {@code GlobalExceptionHandler} já produz;
 *   <li>{@link AccessDeniedHandler} → <strong>403</strong> {@code PAPEL_NAO_AUTORIZADO}, mesmo
 *       código que a {@code PapelNaoAutorizadoException} usa quando a negativa nasce no serviço.
 * </ul>
 *
 * <p>Os dois corpos são deliberadamente genéricos: não dizem qual papel seria necessário nem se o
 * recurso existe.
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** Desafio do 401 sem identidade — {@code Bearer} desde a Sprint 8 (#206). */
  static final String DESAFIO = NaoAutenticadoException.DESAFIO;

  private static final String MENSAGEM_401 = "Requisição sem identidade de usuário válida.";
  private static final String SUGESTAO_401 = NaoAutenticadoException.SUGESTAO;
  private static final String MENSAGEM_TOKEN_INVALIDO = "Token de acesso inválido ou expirado.";
  private static final String SUGESTAO_TOKEN_INVALIDO =
      "Entre de novo em POST /api/auth/login para obter um token válido.";
  private static final String MENSAGEM_429 = "Muitas tentativas em pouco tempo.";
  private static final String SUGESTAO_429 =
      "Aguarde o tempo indicado no header Retry-After e tente de novo.";
  private static final String MENSAGEM_500 = "Erro interno ao processar a requisição.";
  private static final String SUGESTAO_500 = "Tente novamente em instantes.";
  private static final String MENSAGEM_403 = "Seu papel não tem acesso a este recurso.";
  private static final String SUGESTAO_403 =
      "Autentique-se com um usuário que tenha o papel exigido por este recurso.";

  private final ObjectMapper objectMapper;

  public SecurityErrorResponder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Requisição sem identidade: 401 com o desafio. */
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException excecao)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, DESAFIO);
    escrever(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.NAO_AUTENTICADO, MENSAGEM_401,
        SUGESTAO_401);
  }

  /**
   * Token Bearer recusado (adulterado, expirado, de outro emissor, de credencial desativada ou com
   * sessão revogada): 401 com {@code error="invalid_token"} (RFC 6750 §3.1).
   *
   * <p>Uma resposta só para todos os motivos — dizer "expirado" ou "revogado" ajudaria quem testa
   * tokens roubados.
   *
   * @param request  requisição recusada
   * @param response resposta a escrever
   * @throws IOException se a escrita falhar
   */
  public void recusarToken(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE, NaoAutenticadoException.DESAFIO_TOKEN_INVALIDO);
    escrever(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.NAO_AUTENTICADO,
        MENSAGEM_TOKEN_INVALIDO, SUGESTAO_TOKEN_INVALIDO);
  }

  /**
   * Falha técnica na própria cadeia de filtros (banco fora ao autenticar o token): 500 genérico,
   * no mesmo envelope do {@code GlobalExceptionHandler}. O detalhe fica no log de quem chama.
   *
   * @param request  requisição que falhou
   * @param response resposta a escrever
   * @throws IOException se a escrita falhar
   */
  public void falhaInterna(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    escrever(request, response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
        MENSAGEM_500, SUGESTAO_500);
  }

  /**
   * Limite de tentativas por IP excedido (issue #208): 429 {@code MUITAS_TENTATIVAS} com
   * {@code Retry-After} (RFC 6585 §4).
   *
   * @param request          requisição barrada
   * @param response         resposta a escrever
   * @param esperarSegundos  segundos até poder tentar de novo
   * @throws IOException se a escrita falhar
   */
  public void muitasTentativas(
      HttpServletRequest request, HttpServletResponse response, long esperarSegundos)
      throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(esperarSegundos));
    escrever(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.MUITAS_TENTATIVAS,
        MENSAGEM_429, SUGESTAO_429);
  }

  /** Identidade válida, papel insuficiente: 403. */
  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException excecao)
      throws IOException {
    escrever(request, response, HttpStatus.FORBIDDEN, ErrorCode.PAPEL_NAO_AUTORIZADO, MENSAGEM_403,
        SUGESTAO_403);
  }

  private void escrever(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      ErrorCode codigo,
      String mensagem,
      String sugestao)
      throws IOException {
    ApiError corpo = ApiError.of(
        status.value(),
        status.getReasonPhrase(),
        mensagem,
        request.getRequestURI(),
        List.of(new ErrorDetail(codigo.code(), null, mensagem, sugestao, null, null)));

    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), corpo);
  }
}

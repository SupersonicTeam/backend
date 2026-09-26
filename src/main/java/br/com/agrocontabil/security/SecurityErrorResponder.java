package br.com.agrocontabil.security;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.config.MockIdentidadeFilter;
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

  /** Desafio do 401 — hoje o header de identidade da Onda 4; com JWT passa a ser {@code Bearer}. */
  static final String DESAFIO =
      "Header realm=\"agrocontabil\", header=\"" + MockIdentidadeFilter.HEADER_USUARIO_ID + "\"";

  private static final String MENSAGEM_401 = "Requisicao sem identidade de usuario valida.";
  private static final String SUGESTAO_401 =
      "Envie o header " + MockIdentidadeFilter.HEADER_USUARIO_ID
          + " com o identificador do usuario.";
  private static final String MENSAGEM_403 = "Seu papel nao tem acesso a este recurso.";
  private static final String SUGESTAO_403 =
      "Autentique-se com um usuario que tenha o papel exigido por este recurso.";

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

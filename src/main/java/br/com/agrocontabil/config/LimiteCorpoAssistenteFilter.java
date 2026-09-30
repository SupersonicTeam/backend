package br.com.agrocontabil.config;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limita o tamanho do corpo das requisições da Ceres ({@code /api/assistente/**}) antes de o
 * Jackson lê-lo (issue #144).
 *
 * <p>O {@code @Size} da pergunta só é avaliado <em>depois</em> da desserialização: sem este filtro,
 * um POST de dezenas de MB seria lido inteiro para a memória — e a pergunta rejeitada, ecoada de
 * volta no {@code rejectedValue}. Com {@code Content-Length} acima do limite a resposta é 413 no
 * envelope {@code errors[]}; sem {@code Content-Length} (chunked), a leitura é cortada no limite e o
 * corpo truncado vira 400 de JSON inválido.
 *
 * <p>Filtro de servlet: roda antes do {@code @RestControllerAdvice}, por isso escreve o
 * {@link ApiError} diretamente (mesmo padrão do {@code SecurityErrorResponder}).
 */
@Component
public class LimiteCorpoAssistenteFilter extends OncePerRequestFilter {

  /**
   * Teto do corpo, em bytes. A pergunta tem no máximo 1000 caracteres (até 4 bytes cada em UTF-8)
   * mais o envelope JSON: 16 KB sobra.
   */
  public static final int LIMITE_BYTES = 16 * 1024;

  private static final String PREFIXO = "/api/assistente/";

  private final ObjectMapper objectMapper;

  public LimiteCorpoAssistenteFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PREFIXO);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long tamanho = request.getContentLengthLong();
    if (tamanho > LIMITE_BYTES) {
      recusar(request, response);
      return;
    }
    chain.doFilter(tamanho < 0 ? new CorpoLimitado(request) : request, response);
  }

  private void recusar(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
    ApiError corpo = ApiError.of(status.value(), status.getReasonPhrase(),
        "A requisicao excede o tamanho maximo permitido.", request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.PAYLOAD_TOO_LARGE,
            "O corpo da requisicao deve ter no maximo " + LIMITE_BYTES + " bytes.")));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(), corpo);
  }

  /** Corpo sem {@code Content-Length}: a leitura para no limite (o JSON truncado vira 400). */
  private static final class CorpoLimitado extends HttpServletRequestWrapper {

    CorpoLimitado(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream original = super.getInputStream();
      return new ServletInputStream() {
        private int lidos;

        @Override
        public int read() throws IOException {
          if (lidos >= LIMITE_BYTES) {
            return -1;
          }
          int b = original.read();
          if (b >= 0) {
            lidos++;
          }
          return b;
        }

        @Override
        public boolean isFinished() {
          return lidos >= LIMITE_BYTES || original.isFinished();
        }

        @Override
        public boolean isReady() {
          return original.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          original.setReadListener(listener);
        }
      };
    }
  }
}

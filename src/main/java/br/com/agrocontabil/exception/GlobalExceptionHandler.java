package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tratamento global de exceções da API. Converte exceções em {@link ApiError}
 * com lista de erros estruturados. Detalhe técnico fica só no log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Qualquer exceção de domínio. Status, message e errors vêm da própria exceção. */
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest request) {
    HttpStatus status = ex.getStatus();
    String clientMessage = clientMessageFor(ex);
    if (status == HttpStatus.SERVICE_UNAVAILABLE) {
      // 503 = dependencia opcional fora do ar (ex.: o modelo da Ceres). Estado operacional
      // esperado, que se repete a cada requisicao enquanto durar: WARN sem stack trace, para nao
      // inundar o log. Quem lanca registra a causa (ModeloLinguagem, em DEBUG).
      log.warn("Dependencia indisponivel (503) em {}: {}", request.getRequestURI(), ex.getMessage());
    } else if (status.is5xxServerError()) {
      log.error("Erro de dominio (5xx) em {}: {}", request.getRequestURI(), ex.getMessage(), ex);
    } else {
      log.warn("Erro de dominio em {}: {}", request.getRequestURI(), ex.getMessage());
    }
    ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), clientMessage,
        request.getRequestURI(), ex.getErrors());

    ResponseEntity.BodyBuilder resposta = ResponseEntity.status(status);
    if (status == HttpStatus.UNAUTHORIZED) {
      // RFC 9110 §15.5.2: toda resposta 401 deve trazer o desafio em WWW-Authenticate. Hoje o
      // esquema e o header de identidade da Onda 4; na Sprint 8 (#93) passa a ser Bearer.
      resposta.header(HttpHeaders.WWW_AUTHENTICATE,
          "Header realm=\"agrocontabil\", header=\"" + MockIdentidadeFilter.HEADER_USUARIO_ID + "\"");
    }
    return resposta.body(body);
  }

  /** Bean Validation (@Valid). Cada FieldError vira um ErrorDetail com rejectedValue. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(this::toErrorDetail)
        .toList();
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Um ou mais campos sao invalidos.",
        request.getRequestURI(),
        errors);
    return ResponseEntity.badRequest().body(body);
  }

  /**
   * Violação de constraint em parâmetros de método (ex.: {@code @Pattern} em {@code @RequestParam}
   * de um controller {@code @Validated}). Cada violação vira um {@link ErrorDetail}. HTTP 400.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiError> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<ErrorDetail> errors = ex.getConstraintViolations().stream()
        .map(this::toErrorDetail)
        .toList();
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Um ou mais parametros sao invalidos.",
        request.getRequestURI(),
        errors);
    return ResponseEntity.badRequest().body(body);
  }

  /** Parâmetro de requisição obrigatório ausente (ex.: {@code @RequestParam} sem valor). HTTP 400. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiError> handleMissingParam(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    ErrorDetail detail = ErrorDetail.of(
        ErrorCode.VALIDATION_ERROR,
        ex.getParameterName(),
        "Parametro obrigatorio ausente: " + ex.getParameterName() + ".");
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Parametro obrigatorio ausente.",
        request.getRequestURI(),
        List.of(detail));
    return ResponseEntity.badRequest().body(body);
  }

  /** Argumento ilegal vindo da camada de serviço. HTTP 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        ex.getMessage(),
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.ILLEGAL_ARGUMENT, ex.getMessage())));
    return ResponseEntity.badRequest().body(body);
  }

  /** Corpo da requisição ilegível (JSON malformado ou tipo incompatível no payload). HTTP 400. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    log.warn("Corpo de requisicao ilegivel em {}: {}", request.getRequestURI(), ex.getMessage());
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Corpo da requisicao invalido ou malformado.",
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.VALIDATION_ERROR,
            "Corpo da requisicao invalido ou malformado. Verifique a sintaxe do JSON enviado.")));
    return ResponseEntity.badRequest().body(body);
  }

  /** Path variable ou request param com tipo inválido (ex.: id que não é UUID). HTTP 400. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String expected = ex.getRequiredType() == null ? null : ex.getRequiredType().getSimpleName();
    ErrorDetail detail = new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        ex.getName(),
        "Valor invalido para o parametro '" + ex.getName() + "'.",
        null,
        ex.getValue() == null ? null : String.valueOf(ex.getValue()),
        expected);
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Um ou mais parametros sao invalidos.",
        request.getRequestURI(),
        List.of(detail));
    return ResponseEntity.badRequest().body(body);
  }

  /** Rota inexistente (nenhum handler ou recurso estático atende o path). HTTP 404. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest request) {
    ApiError body = ApiError.of(
        HttpStatus.NOT_FOUND.value(),
        HttpStatus.NOT_FOUND.getReasonPhrase(),
        "Recurso nao encontrado.",
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.RESOURCE_NOT_FOUND, "Recurso nao encontrado.")));
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  /** Método HTTP não suportado pela rota. HTTP 405. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    String suportados = ex.getSupportedMethods() == null
        ? null
        : String.join(", ", ex.getSupportedMethods());
    ErrorDetail detail = new ErrorDetail(
        ErrorCode.METHOD_NOT_ALLOWED.code(),
        null,
        "Metodo " + ex.getMethod() + " nao suportado para esta rota.",
        null,
        ex.getMethod(),
        suportados);
    ApiError body = ApiError.of(
        HttpStatus.METHOD_NOT_ALLOWED.value(),
        HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
        "Metodo HTTP nao suportado para esta rota.",
        request.getRequestURI(),
        List.of(detail));
    // RFC 7231 §6.5.5: resposta 405 deve informar os metodos permitidos no header Allow
    // (paridade com o DefaultHandlerExceptionResolver do Spring, que setava este header).
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
    if (suportados != null) {
      response.header(HttpHeaders.ALLOW, suportados);
    }
    return response.body(body);
  }

  /** Content-Type da requisição não suportado pelo endpoint. HTTP 415. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    ErrorDetail detail = new ErrorDetail(
        ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.code(),
        null,
        "Content-Type nao suportado por este endpoint.",
        null,
        ex.getContentType() == null ? null : ex.getContentType().toString(),
        null);
    ApiError body = ApiError.of(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
        HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase(),
        "Content-Type nao suportado por este endpoint.",
        request.getRequestURI(),
        List.of(detail));
    // RFC 7231 §6.5.13: resposta 415 deve informar os media types aceitos no header Accept
    // (paridade com o DefaultHandlerExceptionResolver do Spring, que setava este header).
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    List<MediaType> aceitos = ex.getSupportedMediaTypes();
    if (!aceitos.isEmpty()) {
      response.header(HttpHeaders.ACCEPT, MediaType.toString(aceitos));
    }
    return response.body(body);
  }

  /** Upload multipart acima do tamanho máximo configurado. HTTP 413. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> handleMaxUploadSize(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    log.warn("Upload acima do limite em {}: {}", request.getRequestURI(), ex.getMessage());
    ApiError body = ApiError.of(
        HttpStatus.PAYLOAD_TOO_LARGE.value(),
        HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
        "Arquivo excede o tamanho maximo permitido.",
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.PAYLOAD_TOO_LARGE,
            "Arquivo excede o tamanho maximo permitido. Envie um arquivo menor.")));
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
  }

  /**
   * Violação de restrição de integridade do banco (unique, FK, not-null) — ex.: criar nota com
   * {@code (produtor, numero, serie, modelo)} já existente ou {@code chave_acesso} duplicada.
   * Responde 409 com mensagem genérica; o detalhe técnico (nome da constraint, SQL) fica só no
   * log, nunca é vazado ao cliente. Sem este handler a violação cairia no 500 genérico.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    log.warn("Violacao de integridade em {} {}: {}",
        request.getMethod(), request.getRequestURI(),
        ex.getMostSpecificCause().getMessage());
    String message =
        "A operacao conflita com um registro existente ou viola uma restricao de integridade.";
    ApiError body = ApiError.of(
        HttpStatus.CONFLICT.value(),
        HttpStatus.CONFLICT.getReasonPhrase(),
        message,
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.VIOLACAO_INTEGRIDADE, message)));
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * Conflito de lock otimista (issue #95): duas operações concorrentes alteraram o mesmo registro e
   * a versão lida por esta ficou obsoleta — ex.: dois {@code POST /gerar-xml} simultâneos na mesma
   * nota, onde o segundo commit encontraria a nota já transicionada.
   *
   * <p>Responde 409 com orientação de ação (recarregar e repetir), não com o que aconteceu no
   * banco: o texto do ORM (nome da entidade, id, "unsaved-value mapping") fica só no log. Sem este
   * handler o conflito cairia no 500 genérico e o cliente não saberia que basta recarregar.
   *
   * <p>Cobre também a {@code jakarta.persistence.OptimisticLockException}, que o Spring traduz para
   * {@link OptimisticLockingFailureException} na camada de acesso a dados.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> handleOptimisticLocking(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    log.warn("Conflito de concorrencia em {} {}: {}",
        request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
    String message = "A nota foi modificada em outra operacao; recarregue e tente de novo.";
    String suggestion =
        "Recarregue a nota (GET /api/notas/{id}) e repita a operacao com os dados atualizados.";
    ApiError body = ApiError.of(
        HttpStatus.CONFLICT.value(),
        HttpStatus.CONFLICT.getReasonPhrase(),
        message,
        request.getRequestURI(),
        List.of(new ErrorDetail(
            ErrorCode.CONFLITO_CONCORRENCIA.code(), null, message, suggestion, null, null)));
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /** Rede de segurança: qualquer exceção não mapeada vira 500 sem vazar detalhes. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Erro nao tratado em {} {}", request.getMethod(), request.getRequestURI(), ex);
    ApiError body = ApiError.of(
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
        "Erro interno inesperado. Tente novamente mais tarde.",
        request.getRequestURI(),
        List.of(ErrorDetail.of(ErrorCode.INTERNAL_ERROR,
            "Erro interno inesperado. Tente novamente mais tarde.")));
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  /**
   * Mensagem de envelope segura ao cliente. {@link XmlGenerationException} usa sua
   * mensagem-cliente genérica; qualquer 5xx (ex.: {@link AssinaturaDigitalException},
   * cujo {@code getMessage()} carrega detalhe técnico) recebe texto genérico; demais
   * exceções (4xx) usam {@code getMessage()}, que já é seguro.
   */
  private String clientMessageFor(DomainException ex) {
    if (ex instanceof XmlGenerationException xml) {
      return xml.clientMessage();
    }
    if (ex instanceof AssistenteIndisponivelException assistente) {
      // 503 da Ceres: a mensagem generica de 5xx fala em NF-e, o que confundiria o chat.
      return assistente.clientMessage();
    }
    if (ex.getStatus().is5xxServerError()) {
      return "Erro interno ao processar a NF-e. Tente novamente mais tarde.";
    }
    return ex.getMessage();
  }

  private ErrorDetail toErrorDetail(ConstraintViolation<?> violation) {
    String field = lastNode(violation);
    String rejected = violation.getInvalidValue() == null
        ? null
        : String.valueOf(violation.getInvalidValue());
    return new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        field,
        violation.getMessage(),
        null,
        rejected,
        null);
  }

  /** Extrai o último nó do property path (nome do parâmetro), ex.: {@code saldo.periodo → periodo}. */
  private String lastNode(ConstraintViolation<?> violation) {
    String path = violation.getPropertyPath().toString();
    int lastDot = path.lastIndexOf('.');
    return lastDot >= 0 ? path.substring(lastDot + 1) : path;
  }

  private ErrorDetail toErrorDetail(FieldError error) {
    String rejected = error.getRejectedValue() == null
        ? null
        : String.valueOf(error.getRejectedValue());
    return new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        error.getField(),
        error.getDefaultMessage(),
        null,
        rejected,
        null);
  }
}

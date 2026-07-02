package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    if (status.is5xxServerError()) {
      log.error("Erro de dominio (5xx) em {}: {}", request.getRequestURI(), ex.getMessage(), ex);
    } else {
      log.warn("Erro de dominio em {}: {}", request.getRequestURI(), ex.getMessage());
    }
    ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), clientMessage,
        request.getRequestURI(), ex.getErrors());
    return ResponseEntity.status(status).body(body);
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
    ErrorDetail detail = new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        ex.getParameterName(),
        "Parametro obrigatorio ausente: " + ex.getParameterName() + ".",
        null, null, null);
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

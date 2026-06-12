package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento global de excecoes da API.
 *
 * <p>Centraliza a conversao de excecoes em respostas {@link ApiError}
 * consistentes. Loga o detalhe tecnico no servidor, mas devolve ao cliente
 * apenas mensagens seguras — nunca stack trace, senha ou token.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Recurso de dominio nao encontrado. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** Falha de Bean Validation em DTOs (@Valid). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Um ou mais campos sao invalidos.",
                request.getRequestURI(),
                fields);
        return ResponseEntity.badRequest().body(body);
    }

    /** Nota fiscal nao editavel — status diferente de RASCUNHO. HTTP 409 Conflict. */
    @ExceptionHandler(NotaFiscalNaoEditavelException.class)
    public ResponseEntity<ApiError> handleNotaFiscalNaoEditavel(
            NotaFiscalNaoEditavelException ex, HttpServletRequest request) {
        log.warn("Tentativa de edicao em nota nao editavel: uri={}", request.getRequestURI());
        ApiError body = ApiError.of(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Nota fiscal com dados insuficientes para emissao — regras fiscais nao satisfeitas.
     * HTTP 422 Unprocessable Entity, com lista de campos e motivos.
     */
    @ExceptionHandler(NotaFiscalInvalidaParaEmissaoException.class)
    public ResponseEntity<ApiError> handleNotaFiscalInvalidaParaEmissao(
            NotaFiscalInvalidaParaEmissaoException ex, HttpServletRequest request) {
        log.warn("Nota fiscal invalida para emissao: uri={} violacoes={}",
                request.getRequestURI(), ex.getViolations().size());
        List<ApiError.FieldError> fields = ex.getViolations().stream()
                .map(v -> new ApiError.FieldError(v.field(), v.reason()))
                .toList();
        ApiError body = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                "Nota fiscal invalida para emissao da NF-e. Corrija os campos listados.",
                request.getRequestURI(),
                fields);
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /**
     * Erro na geracao ou validacao do XML da NF-e (falha tecnica ou XSD invalido).
     * HTTP 422 Unprocessable Entity.
     *
     * <p>A mensagem tecnica ({@code ex.getMessage()}) pode conter fragmentos do XML ou
     * dados do producao (ex.: SAXParseException com contexto do XSD). Por seguranca,
     * apenas um texto generico e devolvido ao cliente; o detalhe tecnico fica no log
     * server-side para diagnostico.
     */
    @ExceptionHandler(XmlGenerationException.class)
    public ResponseEntity<ApiError> handleXmlGenerationException(
            XmlGenerationException ex, HttpServletRequest request) {
        log.warn("Erro na geracao do XML da NF-e: uri={} detalhe={}",
                request.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                "Nao foi possivel gerar o XML da NF-e. "
                    + "Verifique os dados cadastrais do emitente e do destinatario.",
                request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /**
     * Emitente invalido para Nota Fiscal Avulsa — produtor nao e PF ou CPF invalido.
     * HTTP 422 Unprocessable Entity. Mensagem generica ao cliente; detalhe no log.
     */
    @ExceptionHandler(NfaEmitenteInvalidoException.class)
    public ResponseEntity<ApiError> handleNfaEmitenteInvalido(
            NfaEmitenteInvalidoException ex, HttpServletRequest request) {
        log.warn("Emitente invalido para NFA: uri={} detalhe={}",
                request.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                "Emitente invalido para Nota Fiscal Avulsa: o produtor deve ser "
                    + "pessoa fisica com CPF valido.",
                request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /**
     * CFOP incompativel com o tipo de operacao da nota (entrada/saida, ambito).
     * HTTP 400 Bad Request, com a mensagem descritiva da regra de negocio.
     */
    @ExceptionHandler(CfopIncompativelException.class)
    public ResponseEntity<ApiError> handleCfopIncompativel(
            CfopIncompativelException ex, HttpServletRequest request) {
        log.warn("CFOP incompativel: uri={} detalhe={}",
                request.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** Violacao de regra de negocio (ex.: prazo de cancelamento). HTTP 422. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(
            BusinessException ex, HttpServletRequest request) {
        log.warn("Regra de negocio violada: uri={} detalhe={}",
                request.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /** Parte multipart obrigatoria ausente (ex.: nenhum arquivo no import). HTTP 400. */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException ex,
            HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Requisicao de upload invalida: " + ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** Upload acima do limite configurado (spring.servlet.multipart.max-file-size). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        log.warn("Upload acima do limite: uri={}", request.getRequestURI());
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Arquivo acima do tamanho maximo permitido para importacao.",
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** Argumento ilegal vindo da camada de servico. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** Rede de seguranca: qualquer excecao nao mapeada vira 500 sem vazar detalhes. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro nao tratado em {} {}", request.getMethod(), request.getRequestURI(), ex);
        ApiError body = ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Erro interno inesperado. Tente novamente mais tarde.",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ApiError.FieldError toFieldError(FieldError error) {
        return new ApiError.FieldError(error.getField(), error.getDefaultMessage());
    }
}

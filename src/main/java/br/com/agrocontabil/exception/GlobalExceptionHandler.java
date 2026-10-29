package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.security.CookieRefresh;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tratamento global de exceções da API. Converte exceções em {@link ApiError}
 * com lista de erros estruturados. Detalhe técnico fica só no log.
 *
 * <p>O envelope acompanha o {@code Accept} (JSON ou XML); quando o cliente não aceita nenhum dos
 * dois — streaming da Ceres ({@code text/event-stream}, #157), endpoints de arquivo com
 * {@code Accept: application/pdf} (#250) — sai em JSON. Ver {@link #tipoDoErro()}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * Cookie de refresh (issue #207), para apagá-lo quando a sessão é recusada. Opcional: só existe
   * com servidor web, e o handler também é carregado nos testes de fatia sem ele.
   */
  private final ObjectProvider<CookieRefresh> cookieRefresh;

  public GlobalExceptionHandler(ObjectProvider<CookieRefresh> cookieRefresh) {
    this.cookieRefresh = cookieRefresh;
  }

  /** Qualquer exceção de domínio. Status, message e errors vêm da própria exceção. */
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest request) {
    HttpStatus status = ex.getStatus();
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
    ApiError body = corpoDe(ex, request.getRequestURI());

    ResponseEntity.BodyBuilder resposta = ResponseEntity.status(status);
    if (status == HttpStatus.UNAUTHORIZED) {
      // RFC 9110 §15.5.2: toda resposta 401 traz o desafio em WWW-Authenticate (Bearer, #206).
      resposta.header(HttpHeaders.WWW_AUTHENTICATE, NaoAutenticadoException.DESAFIO);
    }
    if (ex instanceof SessaoInvalidaException) {
      // Refresh recusado (#207): o cookie nao vale mais nada. Sem apaga-lo, o navegador o
      // reenviaria a cada abertura do app ate o Max-Age, sempre com o mesmo 401.
      cookieRefresh.ifAvailable(
          cookie -> resposta.header(HttpHeaders.SET_COOKIE, cookie.apagar()));
    }
    if (ex instanceof ComRetryAfter espera) {
      // RFC 9110 §10.2.3: em 503 (Ceres ocupada, #157) e 429 (cota semanal, #166), diz ao
      // cliente quando vale tentar de novo.
      resposta.header(HttpHeaders.RETRY_AFTER, String.valueOf(espera.retryAfterSegundos()));
    }
    return resposta.contentType(tipoDoErro()).body(body);
  }

  /** Bean Validation (@Valid). Cada FieldError vira um ErrorDetail com rejectedValue. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Object alvo = ex.getBindingResult().getTarget();
    Class<?> tipoAlvo = alvo == null ? null : alvo.getClass();
    List<ErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(erro -> toErrorDetail(erro, tipoAlvo))
        .toList();
    ApiError body = ApiError.of(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        "Um ou mais campos sao invalidos.",
        request.getRequestURI(),
        errors);
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.badRequest().contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(tipoDoErro()).body(body);
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
    return response.contentType(tipoDoErro()).body(body);
  }

  /**
   * Nenhuma representação casa com o {@code Accept} (issue #250): {@code /pdf} pedido com
   * {@code Accept: application/json}, por exemplo. Sem este handler a exceção caía na rede de
   * segurança e virava 500 com stack trace no log.
   *
   * <p>O envelope segue o {@link #tipoDoErro()}: XML para quem aceita XML, JSON nos demais casos
   * (inclusive {@code Accept: application/pdf} num endpoint JSON e {@code Accept} malformado).
   *
   * <p>Limite conhecido: num endpoint sem {@code produces}, o Spring só descobre que não há
   * representação <em>depois</em> de o método rodar (ao escrever o retorno). Numa escrita (POST
   * com {@code Accept: application/pdf}), o 406 sai com a operação já gravada — como antes saía o
   * 500. Os clientes do projeto mandam {@code Accept} JSON nas rotas JSON.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ApiError> handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    String accept = resumirValorRejeitado(request.getHeader(HttpHeaders.ACCEPT));
    log.warn("Accept sem representacao em {}: {}", request.getRequestURI(), accept);
    String message = "Este endpoint nao produz nenhum dos formatos aceitos pelo cliente.";
    // So os tipos concretos: na falha ao escrever o retorno o Spring lista os intervalos de todos
    // os conversores (application/*+json etc.), que nao servem de Accept.
    String produzidos = MediaType.toString(ex.getSupportedMediaTypes().stream()
        .filter(tipo -> !tipo.isWildcardType() && !tipo.isWildcardSubtype()
            && !tipo.getSubtype().startsWith("*+"))
        .map(tipo -> new MediaType(tipo.getType(), tipo.getSubtype()))
        .distinct()
        .toList());
    boolean temProduzidos = !produzidos.isEmpty();
    ErrorDetail detail = new ErrorDetail(
        ErrorCode.MEDIA_TYPE_NOT_ACCEPTABLE.code(),
        null,
        message,
        temProduzidos ? "Envie um Accept compativel com " + produzidos + "." : null,
        accept,
        temProduzidos ? produzidos : null);
    ApiError body = ApiError.of(
        HttpStatus.NOT_ACCEPTABLE.value(),
        HttpStatus.NOT_ACCEPTABLE.getReasonPhrase(),
        message,
        request.getRequestURI(),
        List.of(detail));
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).contentType(tipoDoErro()).body(body);
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
    return response.contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.status(HttpStatus.CONFLICT).contentType(tipoDoErro()).body(body);
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
    return ResponseEntity.status(HttpStatus.CONFLICT).contentType(tipoDoErro()).body(body);
  }

  /** Rede de segurança: qualquer exceção não mapeada vira 500 sem vazar detalhes. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Erro nao tratado em {} {}", request.getMethod(), request.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(tipoDoErro()).body(corpoErroInterno(request.getRequestURI()));
  }

  /**
   * Tipo fixo da resposta de erro, ou {@code null} para negociar pelo {@code Accept}.
   *
   * <p>O envelope só se escreve em JSON ou XML. Quando o {@code Accept} não aceita nenhum dos dois
   * — o streaming da Ceres pede {@code text/event-stream} (#157), os endpoints de arquivo recebem
   * {@code application/pdf} (#250) —, a negociação falharia dentro do próprio handler e o cliente
   * receberia 500 vazio no lugar do 400/404/409/503. Para esse cliente o erro sai em JSON. Quem
   * aceita JSON ou XML segue negociando (o {@code /xml} devolve o envelope em XML a quem pede XML).
   *
   * <p>{@code Accept} malformado também sai em JSON: com ele a negociação do Spring descarta o
   * corpo do erro em silêncio (4xx/5xx sem envelope e sem {@code Content-Type}).
   *
   * @return {@code application/json} quando o {@code Accept} não aceita JSON nem XML ou não se
   *     interpreta; {@code null} (negociar) quando aceita, ou quando não há {@code Accept}
   */
  static MediaType tipoDoErro() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes atributos)) {
      return null;
    }
    String accept = atributos.getRequest().getHeader(HttpHeaders.ACCEPT);
    if (accept == null) {
      return null;
    }
    try {
      List<MediaType> aceitos = MediaType.parseMediaTypes(accept);
      return aceitos.isEmpty() || aceitos.stream().anyMatch(GlobalExceptionHandler::escreveEnvelope)
          ? null
          : MediaType.APPLICATION_JSON;
    } catch (InvalidMediaTypeException e) {
      return MediaType.APPLICATION_JSON;
    }
  }

  /**
   * Tipo aceito em que o envelope se escreve (JSON ou XML), com qualidade acima de zero. Dos
   * intervalos, só {@code *}/{@code *} e {@code application/*} contam: {@code text/*} casaria com
   * {@code text/xml} e mandaria XML a um cliente de SSE que espera o erro em JSON (#157).
   */
  private static boolean escreveEnvelope(MediaType aceito) {
    if (aceito.getQualityValue() <= 0) {
      return false;
    }
    if (aceito.isWildcardSubtype()) {
      return aceito.isWildcardType() || "application".equals(aceito.getType());
    }
    return aceito.isCompatibleWith(MediaType.APPLICATION_JSON)
        || aceito.isCompatibleWith(MediaType.APPLICATION_XML)
        || aceito.isCompatibleWith(MediaType.TEXT_XML);
  }

  /**
   * Envelope {@code ApiError} de uma {@link DomainException}, com a mesma mensagem segura do
   * {@link #handleDomain}. Público para quem responde fora do fluxo do
   * {@code @RestControllerAdvice} — o evento {@code erro} do streaming da Ceres (issue #157), que
   * nasce depois de a resposta HTTP já ter começado.
   *
   * @param ex   exceção de domínio
   * @param path caminho da requisição
   * @return envelope com {@code errors[]}
   */
  public static ApiError corpoDe(DomainException ex, String path) {
    HttpStatus status = ex.getStatus();
    return ApiError.of(status.value(), status.getReasonPhrase(), clientMessageFor(ex), path,
        ex.getErrors());
  }

  /**
   * Envelope genérico de 500, sem nenhum detalhe técnico (mesmo do {@link #handleUnexpected}).
   *
   * @param path caminho da requisição
   * @return envelope com {@code errors[]}
   */
  public static ApiError corpoErroInterno(String path) {
    return ApiError.of(
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
        "Erro interno inesperado. Tente novamente mais tarde.",
        path,
        List.of(ErrorDetail.of(ErrorCode.INTERNAL_ERROR,
            "Erro interno inesperado. Tente novamente mais tarde.")));
  }

  /**
   * Mensagem de envelope segura ao cliente. {@link XmlGenerationException} usa sua
   * mensagem-cliente genérica; qualquer 5xx (ex.: {@link AssinaturaDigitalException},
   * cujo {@code getMessage()} carrega detalhe técnico) recebe texto genérico; demais
   * exceções (4xx) usam {@code getMessage()}, que já é seguro.
   */
  private static String clientMessageFor(DomainException ex) {
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
    Object folha = violation.getLeafBean();
    String rejected = campoSensivel(folha == null ? null : folha.getClass(), field)
        ? null : resumirValorRejeitado(violation.getInvalidValue());
    return new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        field,
        violation.getMessage(),
        null,
        rejected,
        null);
  }

  /**
   * Tamanho máximo do {@code rejectedValue} ecoado (issue #144). O valor rejeitado volta ao
   * cliente: sem teto, uma pergunta de 1 MB à Ceres voltaria inteira no corpo do 400 (reflexão de
   * conteúdo e memória desperdiçada). 100 caracteres bastam para o cliente reconhecer o campo.
   */
  static final int MAXIMO_VALOR_REJEITADO = 100;

  /**
   * Trechos de nome de campo cujo valor nunca volta no {@code rejectedValue} (issue #205): senha,
   * token e segredo. Rede de segurança da anotação {@link Sensivel}.
   */
  private static final List<String> TRECHOS_CAMPO_SENSIVEL =
      List.of("senha", "password", "token", "secret", "segredo");

  /**
   * Diz se o valor do campo nunca pode voltar no {@code rejectedValue} (issue #205).
   *
   * <p>A fonte de verdade é a anotação {@link Sensivel} no campo do DTO. O nome do campo (senha,
   * token, segredo) é a rede de segurança para quem esquecer a anotação — e cobre os caminhos em
   * que a classe do campo não é conhecida (parâmetro de método, propriedade aninhada).
   *
   * @param tipo  classe que declara o campo, se conhecida
   * @param campo nome do campo ou caminho da propriedade
   * @return {@code true} se o valor deve ser omitido
   */
  static boolean campoSensivel(Class<?> tipo, String campo) {
    return anotadoSensivel(tipo, campo) || campoSensivel(campo);
  }

  static boolean campoSensivel(String campo) {
    if (campo == null) {
      return false;
    }
    String minusculo = campo.toLowerCase(Locale.ROOT);
    return TRECHOS_CAMPO_SENSIVEL.stream().anyMatch(minusculo::contains);
  }

  private static boolean anotadoSensivel(Class<?> tipo, String campo) {
    if (tipo == null || campo == null || campo.contains(".") || campo.contains("[")) {
      return false;
    }
    for (Class<?> atual = tipo; atual != null && atual != Object.class;
        atual = atual.getSuperclass()) {
      try {
        return atual.getDeclaredField(campo).isAnnotationPresent(Sensivel.class);
      } catch (NoSuchFieldException e) {
        // Campo herdado: segue para a superclasse.
      }
    }
    return false;
  }

  static String resumirValorRejeitado(Object valor) {
    if (valor == null) {
      return null;
    }
    String texto = String.valueOf(valor);
    if (texto.codePointCount(0, texto.length()) <= MAXIMO_VALOR_REJEITADO) {
      return texto;
    }
    // Corte por code point: por indice de char, um emoji na borda viraria surrogate solto.
    return texto.substring(0, texto.offsetByCodePoints(0, MAXIMO_VALOR_REJEITADO)) + "...";
  }

  /** Extrai o último nó do property path (nome do parâmetro), ex.: {@code saldo.periodo → periodo}. */
  private String lastNode(ConstraintViolation<?> violation) {
    String path = violation.getPropertyPath().toString();
    int lastDot = path.lastIndexOf('.');
    return lastDot >= 0 ? path.substring(lastDot + 1) : path;
  }

  private ErrorDetail toErrorDetail(FieldError error, Class<?> tipoAlvo) {
    String rejected = campoSensivel(tipoAlvo, error.getField())
        ? null : resumirValorRejeitado(error.getRejectedValue());
    return new ErrorDetail(
        ErrorCode.VALIDATION_ERROR.code(),
        error.getField(),
        error.getDefaultMessage(),
        null,
        rejected,
        null);
  }
}

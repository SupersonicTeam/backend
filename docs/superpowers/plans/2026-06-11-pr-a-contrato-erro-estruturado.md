# PR A — Contrato de Erro Estruturado — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Padronizar o contrato de erro da API num envelope JSON `errors[]` rico (code, field, message, suggestion, rejectedValue, expectedValue), válido para todos os erros.

**Architecture:** Introduz `ErrorCode` (catálogo), `ErrorDetail` (item rico), `DomainException` (base abstrata com status + lista de detalhes). O `GlobalExceptionHandler` ganha um handler único para `DomainException`; as 4 exceções de domínio existentes passam a estendê-la. `ApiError.fields` vira `ApiError.errors`.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Jackson, JUnit 5, MockMvc.

**Branch:** `feat/contrato-erro-estruturado` (worktree isolado em `.claude/.worktrees/`).

---

### Task 1: Enum `ErrorCode` (catálogo de códigos)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/ErrorCode.java`
- Test: `src/test/java/br/com/agrocontabil/common/ErrorCodeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void codeShouldBeStableStringIdentifier() {
    assertThat(ErrorCode.VALIDATION_ERROR.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(ErrorCode.RESOURCE_NOT_FOUND.code()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(ErrorCode.NOTA_NAO_EDITAVEL.code()).isEqualTo("NOTA_NAO_EDITAVEL");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ErrorCodeTest`
Expected: FAIL — `ErrorCode` não existe (erro de compilação).

- [ ] **Step 3: Write minimal implementation**

```java
package br.com.agrocontabil.common;

/**
 * Catálogo central de códigos de erro estáveis expostos ao cliente no campo
 * {@code errors[].code}. O frontend pode reagir programaticamente por código.
 */
public enum ErrorCode {
  // Genéricos
  VALIDATION_ERROR,
  RESOURCE_NOT_FOUND,
  ILLEGAL_ARGUMENT,
  INTERNAL_ERROR,
  // Nota fiscal
  NOTA_NAO_EDITAVEL,
  NOTA_INVALIDA_EMISSAO,
  XML_GENERATION_ERROR;

  /** Retorna o código estável (igual ao nome da constante). */
  public String code() {
    return name();
  }
}
```

> Os códigos de assinatura (`CERTIFICADO_AUSENTE`, etc.) são adicionados no PR B.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ErrorCodeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/ErrorCode.java src/test/java/br/com/agrocontabil/common/ErrorCodeTest.java
git commit -F - <<'MSG'
feat(common): adiciona enum ErrorCode com catalogo de codigos de erro

Codigos estaveis expostos no contrato de erro estruturado (errors[].code).
MSG
```

---

### Task 2: Record `ErrorDetail` (item de erro rico)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/ErrorDetail.java`
- Test: `src/test/java/br/com/agrocontabil/common/ErrorDetailTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ErrorDetailTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void shouldOmitNullOptionalFieldsInJson() throws Exception {
    ErrorDetail detail = ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "itens[0].ncm",
        "NCM deve ter 8 digitos");

    String json = mapper.writeValueAsString(detail);

    assertThat(json).contains("\"code\":\"VALIDATION_ERROR\"");
    assertThat(json).contains("\"field\":\"itens[0].ncm\"");
    assertThat(json).contains("\"message\":\"NCM deve ter 8 digitos\"");
    assertThat(json).doesNotContain("suggestion");
    assertThat(json).doesNotContain("rejectedValue");
    assertThat(json).doesNotContain("expectedValue");
  }

  @Test
  void shouldSerializeAllFieldsWhenPresent() throws Exception {
    ErrorDetail detail = new ErrorDetail("CFOP_INVALIDO", "itens[0].cfop",
        "CFOP incompativel com a UF de destino",
        "Verifique se a operacao e estadual ou interestadual", "5102", "6102");

    String json = mapper.writeValueAsString(detail);

    assertThat(json).contains("\"suggestion\":");
    assertThat(json).contains("\"rejectedValue\":\"5102\"");
    assertThat(json).contains("\"expectedValue\":\"6102\"");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ErrorDetailTest`
Expected: FAIL — `ErrorDetail` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package br.com.agrocontabil.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Item de erro estruturado exposto no contrato da API ({@code errors[]}).
 *
 * <p>Campos opcionais nulos são omitidos do JSON ({@link JsonInclude.Include#NON_NULL}).
 *
 * @param code          código estável do erro (ver {@link ErrorCode})
 * @param field         campo afetado (ex.: {@code itens[0].cfop}); opcional
 * @param message       mensagem amigável ao usuário
 * @param suggestion    sugestão de correção; opcional
 * @param rejectedValue valor recebido; opcional
 * @param expectedValue valor esperado; opcional
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
    String code,
    String field,
    String message,
    String suggestion,
    String rejectedValue,
    String expectedValue) {

  /** Cria um detalhe apenas com código, campo e mensagem (sem extras). */
  public static ErrorDetail of(ErrorCode code, String field, String message) {
    return new ErrorDetail(code.code(), field, message, null, null, null);
  }

  /** Cria um detalhe sem campo associado (erro de nível de requisição). */
  public static ErrorDetail of(ErrorCode code, String message) {
    return new ErrorDetail(code.code(), null, message, null, null, null);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ErrorDetailTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/ErrorDetail.java src/test/java/br/com/agrocontabil/common/ErrorDetailTest.java
git commit -F - <<'MSG'
feat(common): adiciona ErrorDetail com campos ricos e omissao de nulos

code/field/message/suggestion/rejectedValue/expectedValue; @JsonInclude(NON_NULL).
MSG
```

---

### Task 3: Refatorar `ApiError` (`fields` → `errors`)

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/common/ApiError.java`
- Test: `src/test/java/br/com/agrocontabil/common/ApiErrorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiErrorTest {

  @Test
  void ofWithoutErrorsShouldProduceEmptyErrorsList() {
    ApiError error = ApiError.of(404, "Not Found", "Nao encontrado", "/api/notas/1");
    assertThat(error.errors()).isEmpty();
    assertThat(error.status()).isEqualTo(404);
  }

  @Test
  void ofWithErrorsShouldExposeThem() {
    ErrorDetail detail = ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "ncm", "invalido");
    ApiError error = ApiError.of(400, "Bad Request", "Campos invalidos", "/api/notas",
        List.of(detail));
    assertThat(error.errors()).containsExactly(detail);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ApiErrorTest`
Expected: FAIL — método `errors()` não existe (ainda é `fields`).

- [ ] **Step 3: Write minimal implementation**

Substituir o conteúdo de `ApiError.java` por:

```java
package br.com.agrocontabil.common;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Corpo padronizado de resposta de erro da API.
 *
 * <p>Usado por toda a aplicacao através do {@code GlobalExceptionHandler}.
 * Nunca expõe stack trace nem detalhes sensíveis ao cliente.
 *
 * @param timestamp instante do erro
 * @param status    código HTTP
 * @param error     descrição curta do status (ex.: "Not Found")
 * @param message   mensagem amigável (envelope) para o cliente
 * @param path      caminho da requisição que falhou
 * @param errors    lista de erros estruturados (pode ser vazia)
 */
public record ApiError(
    OffsetDateTime timestamp,
    int status,
    String error,
    String message,
    String path,
    List<ErrorDetail> errors) {

  public static ApiError of(int status, String error, String message, String path) {
    return new ApiError(OffsetDateTime.now(), status, error, message, path, List.of());
  }

  public static ApiError of(int status, String error, String message, String path,
      List<ErrorDetail> errors) {
    return new ApiError(OffsetDateTime.now(), status, error, message, path, errors);
  }
}
```

> Remove o record interno `FieldError` (substituído por `ErrorDetail`). Isso quebra a compilação do `GlobalExceptionHandler` e `NotaFiscalInvalidaParaEmissaoException` — corrigidos nas Tasks 5-9.

- [ ] **Step 4: Run test (unidade do ApiError passa; o módulo ainda não compila inteiro)**

Run: `./mvnw test -Dtest=ApiErrorTest`
Expected: pode FALHAR no compile de outras classes que ainda usam `FieldError`. Está OK — seguir para as Tasks de refactor. (Se preferir, rode esta task junto das 4-9 antes de compilar tudo.)

- [ ] **Step 5: Commit** (após Task 9, num único commit de refactor — ver nota abaixo)

> **Nota de ordenação:** Tasks 3 a 9 são um refactor acoplado (mudança de tipo). Implemente as Tasks 4-9 antes de rodar o build completo, e faça **um commit** cobrindo o conjunto de refactor do contrato (ou commits por arquivo se o build intermediário permitir). O TDD aqui é por classe, mas o `./mvnw test` verde só ocorre ao fim da Task 10.

---

### Task 4: `DomainException` (base abstrata)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/exception/DomainException.java`
- Test: `src/test/java/br/com/agrocontabil/exception/DomainExceptionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DomainExceptionTest {

  /** Subclasse concreta mínima para exercitar a base. */
  private static final class SampleException extends DomainException {
    SampleException() {
      super(HttpStatus.CONFLICT, "Conflito de estado",
          List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL, "Nota em XML_GERADO")));
    }
  }

  @Test
  void shouldCarryStatusMessageAndErrors() {
    DomainException ex = new SampleException();
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ex.getMessage()).isEqualTo("Conflito de estado");
    assertThat(ex.getErrors()).hasSize(1);
    assertThat(ex.getErrors().get(0).code()).isEqualTo("NOTA_NAO_EDITAVEL");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DomainExceptionTest`
Expected: FAIL — `DomainException` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorDetail;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Base de todas as exceções de domínio mapeadas para o contrato de erro estruturado.
 *
 * <p>Carrega o {@link HttpStatus} de resposta, a mensagem de envelope (amigável ao
 * cliente) e a lista de {@link ErrorDetail}. O {@code GlobalExceptionHandler} converte
 * qualquer subclasse num {@code ApiError} de forma uniforme.
 */
public abstract class DomainException extends RuntimeException {

  private final transient HttpStatus status;
  private final transient List<ErrorDetail> errors;

  protected DomainException(HttpStatus status, String message, List<ErrorDetail> errors) {
    super(message);
    this.status = status;
    this.errors = errors == null ? List.of() : Collections.unmodifiableList(errors);
  }

  /** Status HTTP de resposta para esta exceção. */
  public HttpStatus getStatus() {
    return status;
  }

  /** Lista imutável de erros estruturados associados. */
  public List<ErrorDetail> getErrors() {
    return errors;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=DomainExceptionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/DomainException.java src/test/java/br/com/agrocontabil/exception/DomainExceptionTest.java
git commit -F - <<'MSG'
feat(exception): adiciona DomainException base com status e errors estruturados
MSG
```

---

### Task 5: `ResourceNotFoundException` → `DomainException`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/ResourceNotFoundException.java`

- [ ] **Step 1: Substituir a implementação**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando um recurso de domínio não é encontrado. HTTP 404.
 */
public class ResourceNotFoundException extends DomainException {

  public ResourceNotFoundException(String message) {
    super(HttpStatus.NOT_FOUND, message,
        List.of(ErrorDetail.of(ErrorCode.RESOURCE_NOT_FOUND, message)));
  }

  public ResourceNotFoundException(String resource, Object id) {
    this("%s nao encontrado(a) para o identificador '%s'".formatted(resource, id));
  }
}
```

- [ ] **Step 2: Compilar a classe**

Run: `./mvnw -q compile`
Expected: a classe compila (o handler ainda será ajustado na Task 9).

- [ ] **Step 3: Commit** (junto do bloco de refactor — ver nota da Task 3)

---

### Task 6: `NotaFiscalNaoEditavelException` → `DomainException`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/NotaFiscalNaoEditavelException.java`

- [ ] **Step 1: Substituir a implementação**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando edição/exclusão é tentada em nota cujo status não é RASCUNHO. HTTP 409.
 */
public class NotaFiscalNaoEditavelException extends DomainException {

  public NotaFiscalNaoEditavelException(UUID id, StatusNotaFiscal status) {
    super(
        HttpStatus.CONFLICT,
        "Nota fiscal '%s' nao pode ser editada ou excluida no status '%s'. "
            .formatted(id, status)
            + "Somente notas em status RASCUNHO podem ser alteradas.",
        List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL,
            "Somente notas em RASCUNHO podem ser alteradas.")));
  }
}
```

- [ ] **Step 2: Compilar**

Run: `./mvnw -q compile`
Expected: compila.

- [ ] **Step 3: Commit** (bloco de refactor)

---

### Task 7: `NotaFiscalInvalidaParaEmissaoException` → `DomainException`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/NotaFiscalInvalidaParaEmissaoException.java`

- [ ] **Step 1: Substituir a implementação** (mantém o construtor por `FieldViolation` usado pelo `FiscalRulesValidator`)

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Lançada quando uma NotaFiscal não atende às pré-condições de emissão do XML. HTTP 422.
 * Cada violação vira um {@link ErrorDetail} com {@code code=NOTA_INVALIDA_EMISSAO}.
 */
public class NotaFiscalInvalidaParaEmissaoException extends DomainException {

  /** Registro imutável de uma violação de regra fiscal. */
  public record FieldViolation(String field, String reason) {}

  public NotaFiscalInvalidaParaEmissaoException(List<FieldViolation> violations) {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Nota fiscal invalida para emissao da NF-e. Corrija os campos listados.",
        toErrors(violations));
  }

  private static List<ErrorDetail> toErrors(List<FieldViolation> violations) {
    return violations.stream()
        .map(v -> ErrorDetail.of(ErrorCode.NOTA_INVALIDA_EMISSAO, v.field(), v.reason()))
        .toList();
  }
}
```

> Remove `getViolations()` e `buildMessage()`. Verificar com grep que nada mais chama `getViolations()` (o handler antigo chamava — será reescrito na Task 9).

- [ ] **Step 2: Verificar usos órfãos**

Run (Grep tool ou): `git grep -n "getViolations()"`
Expected: apenas o `GlobalExceptionHandler` (reescrito na Task 9). Se houver outro, ajustar.

- [ ] **Step 3: Commit** (bloco de refactor)

---

### Task 8: `XmlGenerationException` → `DomainException` (mensagem genérica ao cliente)

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/XmlGenerationException.java`

**Contexto:** a mensagem técnica pode conter fragmento de XML/SAXParseException. O construtor recebe o detalhe técnico (vai para `getCause`/log via handler), mas o **envelope ao cliente é genérico** e o `ErrorDetail` não carrega o detalhe técnico.

- [ ] **Step 1: Substituir a implementação**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Erro técnico na geração/validação do XML da NF-e (marshalling JAXB ou XSD). HTTP 422.
 *
 * <p>A mensagem técnica ({@link #getMessage()}) só vai para o log (via handler). Ao cliente
 * é devolvido um envelope genérico, sem vazar fragmento de XML ou contexto do XSD.
 */
public class XmlGenerationException extends DomainException {

  private static final String CLIENT_MESSAGE =
      "Nao foi possivel gerar o XML da NF-e. "
          + "Verifique os dados cadastrais do emitente e do destinatario.";

  public XmlGenerationException(String technicalMessage) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, technicalMessage,
        List.of(ErrorDetail.of(ErrorCode.XML_GENERATION_ERROR, CLIENT_MESSAGE)));
  }

  public XmlGenerationException(String technicalMessage, Throwable cause) {
    this(technicalMessage);
    initCause(cause);
  }

  /** Mensagem genérica e segura para exibir ao cliente. */
  public String clientMessage() {
    return CLIENT_MESSAGE;
  }
}
```

> Atenção: `getMessage()` retorna o detalhe técnico (para log); o handler usa `clientMessage()`/`getErrors()` para a resposta. Garantir no handler (Task 9) que o **envelope** use `clientMessage()`, não `getMessage()`.

- [ ] **Step 2: Compilar**

Run: `./mvnw -q compile`
Expected: compila (após Task 9).

- [ ] **Step 3: Commit** (bloco de refactor)

---

### Task 9: Reescrever `GlobalExceptionHandler` (handler único de `DomainException`)

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Substituir o conteúdo**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
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
   * Para {@link XmlGenerationException} usa a mensagem-cliente genérica como envelope,
   * não o detalhe técnico. Demais exceções usam {@code getMessage()}.
   */
  private String clientMessageFor(DomainException ex) {
    if (ex instanceof XmlGenerationException xml) {
      return xml.clientMessage();
    }
    return ex.getMessage();
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
```

- [ ] **Step 2: Build completo**

Run: `./mvnw -q compile`
Expected: compila — todas as exceções agora estendem `DomainException`.

- [ ] **Step 3: Commit** (fecha o bloco de refactor das Tasks 3-9)

```bash
git add src/main/java/br/com/agrocontabil/common/ApiError.java \
        src/main/java/br/com/agrocontabil/exception/ResourceNotFoundException.java \
        src/main/java/br/com/agrocontabil/exception/NotaFiscalNaoEditavelException.java \
        src/main/java/br/com/agrocontabil/exception/NotaFiscalInvalidaParaEmissaoException.java \
        src/main/java/br/com/agrocontabil/exception/XmlGenerationException.java \
        src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java
git commit -F - <<'MSG'
refactor(exception): unifica tratamento de erro no contrato estruturado

ApiError.fields -> errors (List<ErrorDetail>); exceptions de dominio estendem
DomainException; handler unico para DomainException; MethodArgumentNotValid
passa a expor rejectedValue. Detalhe tecnico de XML so no log.
MSG
```

---

### Task 10: Atualizar `NotaFiscalControllerTest` (`$.fields` → `$.errors`)

**Files:**
- Modify: `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java` (linhas com `$.fields`: ~142-143, 165-166, 198-200, 268, 302)

- [ ] **Step 1: Substituir todas as asserções `$.fields` por `$.errors`**

Trocar cada ocorrência:
- `jsonPath("$.fields").isArray()` → `jsonPath("$.errors").isArray()`
- `jsonPath("$.fields[?(@.field == 'itens[0].ncm')]").exists()` → `jsonPath("$.errors[?(@.field == 'itens[0].ncm')]").exists()`
- idem para `'itens'`, `'itens[0].valorUnitario'`.

Adicionar, em ao menos um desses testes, a verificação do novo campo `code`:

```java
        .andExpect(jsonPath("$.errors[?(@.code == 'VALIDATION_ERROR')]").exists());
```

- [ ] **Step 2: Rodar os testes do controller**

Run: `./mvnw test -Dtest=NotaFiscalControllerTest`
Expected: PASS — o JSON agora usa `errors[]` com `code`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -F - <<'MSG'
test(controller): ajusta asserts do contrato de erro (fields -> errors + code)
MSG
```

---

### Task 11: Teste de integração do contrato de erro (envelope completo)

**Files:**
- Create: `src/test/java/br/com/agrocontabil/exception/GlobalExceptionHandlerIT.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerIT {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("GET de nota inexistente retorna 404 no contrato estruturado errors[]")
  void getUnknownNotaReturnsStructuredNotFound() throws Exception {
    mockMvc.perform(get("/api/notas/{id}", "00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.path").value("/api/notas/00000000-0000-0000-0000-000000000000"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.code == 'RESOURCE_NOT_FOUND')]").exists());
  }
}
```

> Ajustar o path/ID conforme o comportamento real do `MockProdutorFilter` no profile `test`. Se o endpoint exigir produtor seedado, usar um ID de nota inexistente para o produtor mockado (deve dar 404 por ownership/inexistência).

- [ ] **Step 2: Run test to verify it fails (ou passa, se o handler já cobre)**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerIT`
Expected: PASS se o handler estiver correto; caso falhe por path/seed, ajustar o setup.

- [ ] **Step 3: Build + suíte completa**

Run: `./mvnw test`
Expected: PASS — toda a suíte verde com o novo contrato.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/br/com/agrocontabil/exception/GlobalExceptionHandlerIT.java
git commit -F - <<'MSG'
test(exception): teste de integracao do contrato de erro estruturado (404)
MSG
```

---

## Self-Review (executado)

- **Cobertura do spec (camada A):** `ErrorDetail` (T2), `ApiError.errors` (T3), `ErrorCode` (T1), `DomainException` (T4), refactor das 4 exceções (T5-T8), handler único + rejectedValue (T9), atualização dos testes (T10-T11). ✔
- **Placeholders:** nenhum — todo código presente. ✔
- **Consistência de tipos:** `ErrorDetail` usado uniformemente; `clientMessage()` definido em T8 e consumido em T9; `getStatus()/getErrors()` definidos em T4 e usados em T9. ✔
- **Risco conhecido:** Tasks 3-9 quebram a compilação intermediária (mudança de tipo acoplada) — sinalizado; build verde só ao fim. Commit do refactor agrupado na T9.

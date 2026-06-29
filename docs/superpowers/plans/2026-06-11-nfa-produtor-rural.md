# NFA (Nota Fiscal Avulsa) para produtor rural PF — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir gerar Nota Fiscal Avulsa (NFA-e) para produtor rural pessoa física, reusando o pipeline NF-e modelo 55 existente, diferenciando NF-e de NFA no código e no banco e validando o CPF do emitente.

**Architecture:** Um discriminador `TipoDocumento {NFE, NFA}` na entidade `NotaFiscal` (coluna nova). Um `GeracaoNfaService` dedicado compõe o `GeracaoNotaFiscalService` já existente: valida que o emitente é PF com CPF válido (módulo 11) e delega a geração do XML. `NotaFiscalService.gerarXml` roteia por `tipoDocumento`. O mapper JAXB já emite `<CPF>`/`IE="ISENTO"` para PF, então o XML sai correto; adiciona-se apenas um marcador NFA no `infCpl`.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Data JPA, Liquibase, Bean Validation (Jakarta), JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-11-nfa-produtor-rural-design.md`

**Branch:** `sprint-2--notas-fiscais` (já existente).

---

## Notas gerais para o executor

- **Idioma:** código em inglês onde aplicável, mas este projeto usa nomes de domínio em português (`NotaFiscal`, `Produtor`, `gerarXml`). Siga o padrão existente.
- **Comando de teste:** `./mvnw test` (profile `test`, H2). Para um teste só: `./mvnw test -Dtest=NomeDaClasse`.
- **CPFs de teste:** `52998224725` e `11144477735` são **válidos** (módulo 11). `12345678901` e `11111111111` são **inválidos**. O CPF do seed (`123.456.789-00`) também é inválido — não use em happy-path.
- **Mockito strict stubs:** não crie stub de colaborador usado só após um early return (ex.: não stube `geracaoNotaFiscalService` num teste que rejeita PJ antes de chamá-lo).
- **Estilo de teste:** `@DisplayName` em português, AssertJ (`assertThat`, `assertThatThrownBy`), como em `GeracaoNotaFiscalServiceTest`.

---

## Task 1: Discriminador `TipoDocumento` na entidade e no banco

**Files:**
- Create: `src/main/java/br/com/agrocontabil/model/TipoDocumento.java`
- Modify: `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`
- Create: `src/main/resources/db/changelog/changes/0005-nota-fiscal-tipo-documento.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Criar o enum `TipoDocumento`**

Create `src/main/java/br/com/agrocontabil/model/TipoDocumento.java`:

```java
package br.com.agrocontabil.model;

/**
 * Tipo de documento fiscal emitido por um {@link Produtor}.
 *
 * <ul>
 *   <li>{@link #NFE} — Nota Fiscal eletronica modelo 55 (fluxo padrao).</li>
 *   <li>{@link #NFA} — Nota Fiscal Avulsa eletronica do produtor rural pessoa fisica.
 *       Reusa o layout NF-e 4.00 modelo 55; o emitente e PF (CPF).</li>
 * </ul>
 */
public enum TipoDocumento {
  NFE,
  NFA
}
```

- [ ] **Step 2: Adicionar o campo `tipoDocumento` na entidade `NotaFiscal`**

Em `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`, logo após o campo `modelo` (linhas 76-79), adicione:

```java
  /**
   * Tipo do documento fiscal: {@link TipoDocumento#NFE} (padrao) ou
   * {@link TipoDocumento#NFA} (Nota Fiscal Avulsa do produtor rural PF).
   * Diferencia NF-e de NFA no banco e roteia o fluxo de geracao.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_documento", length = 3, nullable = false,
      columnDefinition = "VARCHAR(3)")
  @Builder.Default
  private TipoDocumento tipoDocumento = TipoDocumento.NFE;
```

(`@Enumerated`, `@Column`, `@Builder.Default` já estão importados nesta classe.)

- [ ] **Step 3: Criar a migration Liquibase `0005`**

Create `src/main/resources/db/changelog/changes/0005-nota-fiscal-tipo-documento.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- ================================================================
         0005-nota-fiscal-tipo-documento.xml
         Sprint 2 — issue #18: diferenciacao NF-e vs NFA.

         Adiciona a coluna tipo_documento em nota_fiscal:
           - tipo_documento VARCHAR(3) NOT NULL DEFAULT 'NFE'

         Default 'NFE' garante compatibilidade retroativa: notas
         existentes (NF-e) recebem 'NFE' automaticamente.
         VARCHAR e portavel H2/PostgreSQL.
         NAO editar changesets anteriores (0001-0004).
         ================================================================ -->

    <changeSet id="0005-nota-fiscal-add-tipo-documento" author="supersonic-team">
        <comment>
            Adiciona a coluna tipo_documento (VARCHAR(3) NOT NULL DEFAULT 'NFE')
            para diferenciar NF-e de NFA (Nota Fiscal Avulsa do produtor rural PF).
        </comment>

        <addColumn tableName="nota_fiscal">
            <column name="tipo_documento" type="VARCHAR(3)" defaultValue="NFE">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <rollback>
            <dropColumn tableName="nota_fiscal" columnName="tipo_documento"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Incluir a migration no master changelog**

Em `src/main/resources/db/changelog/db.changelog-master.yaml`, após a linha do `0004` (linha 16), adicione:

```yaml
  - include:
      file: db/changelog/changes/0005-nota-fiscal-tipo-documento.xml
```

- [ ] **Step 5: Compilar e rodar a suíte para garantir que o schema valida**

Run: `./mvnw test -Dtest=AgroContabilApplicationTests`
Expected: PASS (o contexto sobe; Liquibase aplica o `0005` no H2 e o Hibernate valida o schema).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/model/TipoDocumento.java \
        src/main/java/br/com/agrocontabil/model/NotaFiscal.java \
        src/main/resources/db/changelog/changes/0005-nota-fiscal-tipo-documento.xml \
        src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(nfa): adiciona discriminador TipoDocumento na NotaFiscal e migration 0005"
```

---

## Task 2: `CpfUtils` — validação de CPF por módulo 11 (lógica pura)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/validation/CpfUtils.java`
- Test: `src/test/java/br/com/agrocontabil/common/validation/CpfUtilsTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/common/validation/CpfUtilsTest.java`:

```java
package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CpfUtils — validacao de CPF por modulo 11")
class CpfUtilsTest {

  @Test
  @DisplayName("aceita CPF valido com 11 digitos")
  void aceitaCpfValido() {
    assertThat(CpfUtils.isValid("52998224725")).isTrue();
    assertThat(CpfUtils.isValid("11144477735")).isTrue();
  }

  @Test
  @DisplayName("aceita CPF valido mesmo com formatacao (pontos e traco)")
  void aceitaCpfFormatado() {
    assertThat(CpfUtils.isValid("529.982.247-25")).isTrue();
  }

  @Test
  @DisplayName("rejeita CPF com digito verificador errado")
  void rejeitaDigitoVerificadorErrado() {
    assertThat(CpfUtils.isValid("12345678901")).isFalse();
    assertThat(CpfUtils.isValid("52998224724")).isFalse();
  }

  @Test
  @DisplayName("rejeita CPF com todos os digitos iguais")
  void rejeitaDigitosRepetidos() {
    assertThat(CpfUtils.isValid("11111111111")).isFalse();
    assertThat(CpfUtils.isValid("00000000000")).isFalse();
  }

  @Test
  @DisplayName("rejeita CPF com numero de digitos diferente de 11")
  void rejeitaTamanhoInvalido() {
    assertThat(CpfUtils.isValid("123")).isFalse();
    assertThat(CpfUtils.isValid("123456789012")).isFalse();
  }

  @Test
  @DisplayName("rejeita null e string em branco")
  void rejeitaNullEBranco() {
    assertThat(CpfUtils.isValid(null)).isFalse();
    assertThat(CpfUtils.isValid("   ")).isFalse();
  }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `./mvnw test -Dtest=CpfUtilsTest`
Expected: FAIL com erro de compilação ("cannot find symbol CpfUtils").

- [ ] **Step 3: Implementar `CpfUtils`**

Create `src/main/java/br/com/agrocontabil/common/validation/CpfUtils.java`:

```java
package br.com.agrocontabil.common.validation;

/**
 * Utilitario puro de validacao de CPF (cadastro de pessoa fisica) pelo
 * algoritmo dos digitos verificadores (modulo 11).
 *
 * <p>Sem dependencia de Spring ou Jakarta — testavel isoladamente e reutilizavel
 * tanto pela camada de Bean Validation ({@link CpfValidator}) quanto pela camada
 * de servico (validacao do CPF do produtor emitente da NFA).
 */
public final class CpfUtils {

  private CpfUtils() {}

  /**
   * Valida um CPF. Aceita o valor com ou sem formatacao (pontos e traco sao
   * removidos antes da validacao).
   *
   * @param cpf CPF a validar (pode ser null)
   * @return {@code true} se for um CPF estruturalmente valido; {@code false} caso
   *         contrario (null, tamanho != 11, digitos repetidos ou DV invalido)
   */
  public static boolean isValid(String cpf) {
    if (cpf == null) {
      return false;
    }
    String digits = cpf.replaceAll("\\D", "");
    if (digits.length() != 11) {
      return false;
    }
    if (allDigitsEqual(digits)) {
      return false;
    }
    int dv1 = checkDigit(digits, 9);
    int dv2 = checkDigit(digits, 10);
    return dv1 == (digits.charAt(9) - '0') && dv2 == (digits.charAt(10) - '0');
  }

  private static boolean allDigitsEqual(String digits) {
    char first = digits.charAt(0);
    for (int i = 1; i < digits.length(); i++) {
      if (digits.charAt(i) != first) {
        return false;
      }
    }
    return true;
  }

  /**
   * Calcula um digito verificador do CPF.
   *
   * @param digits  string com os 11 digitos do CPF
   * @param length  numero de digitos considerados no calculo (9 para o 1o DV,
   *                10 para o 2o DV)
   * @return o digito verificador esperado (0-9)
   */
  private static int checkDigit(String digits, int length) {
    int sum = 0;
    int weight = length + 1;
    for (int i = 0; i < length; i++) {
      sum += (digits.charAt(i) - '0') * weight;
      weight--;
    }
    int remainder = sum % 11;
    return remainder < 2 ? 0 : 11 - remainder;
  }
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `./mvnw test -Dtest=CpfUtilsTest`
Expected: PASS (todos os cenários).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/validation/CpfUtils.java \
        src/test/java/br/com/agrocontabil/common/validation/CpfUtilsTest.java
git commit -m "feat(nfa): CpfUtils valida CPF por modulo 11"
```

---

## Task 3: Bean Validation customizado `@Cpf`

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/validation/Cpf.java`
- Create: `src/main/java/br/com/agrocontabil/common/validation/CpfValidator.java`
- Test: `src/test/java/br/com/agrocontabil/common/validation/CpfValidatorTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/common/validation/CpfValidatorTest.java`:

```java
package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("CpfValidator — constraint @Cpf")
class CpfValidatorTest {

  private CpfValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new CpfValidator();
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("considera valido um CPF correto")
  void cpfValido() {
    assertThat(validator.isValid("52998224725", context)).isTrue();
  }

  @Test
  @DisplayName("considera invalido um CPF com DV errado")
  void cpfInvalido() {
    assertThat(validator.isValid("12345678901", context)).isFalse();
  }

  @Test
  @DisplayName("considera valido null (obrigatoriedade fica a cargo de @NotBlank)")
  void nullEhValido() {
    assertThat(validator.isValid(null, context)).isTrue();
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=CpfValidatorTest`
Expected: FAIL com erro de compilação ("cannot find symbol CpfValidator").

- [ ] **Step 3: Criar a anotação `@Cpf`**

Create `src/main/java/br/com/agrocontabil/common/validation/Cpf.java`:

```java
package br.com.agrocontabil.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Valida que o valor anotado e um CPF estruturalmente valido (modulo 11).
 *
 * <p>Um valor {@code null} e considerado valido — combine com {@code @NotBlank}
 * quando o campo for obrigatorio. A validacao remove formatacao (pontos e traco)
 * antes de checar os digitos verificadores.
 */
@Documented
@Constraint(validatedBy = CpfValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Cpf {

  String message() default "CPF invalido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 4: Criar o `CpfValidator`**

Create `src/main/java/br/com/agrocontabil/common/validation/CpfValidator.java`:

```java
package br.com.agrocontabil.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa a constraint {@link Cpf} delegando a {@link CpfUtils#isValid(String)}.
 *
 * <p>Valores {@code null} sao tratados como validos (a obrigatoriedade e
 * responsabilidade de {@code @NotBlank}/{@code @NotNull}).
 */
public class CpfValidator implements ConstraintValidator<Cpf, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    return CpfUtils.isValid(value);
  }
}
```

- [ ] **Step 5: Rodar e confirmar que passa**

Run: `./mvnw test -Dtest=CpfValidatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/validation/Cpf.java \
        src/main/java/br/com/agrocontabil/common/validation/CpfValidator.java \
        src/test/java/br/com/agrocontabil/common/validation/CpfValidatorTest.java
git commit -m "feat(nfa): constraint Bean Validation customizada @Cpf"
```

---

## Task 4: Exceção de domínio `NfaEmitenteInvalidoException` (422)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/exception/NfaEmitenteInvalidoException.java`
- Modify: `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Criar a exceção**

Create `src/main/java/br/com/agrocontabil/exception/NfaEmitenteInvalidoException.java`:

```java
package br.com.agrocontabil.exception;

/**
 * Lancada quando o emitente de uma Nota Fiscal Avulsa (NFA) nao atende aos
 * requisitos do fluxo: o produtor precisa ser pessoa fisica (PF) com CPF valido.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) pelo {@code GlobalExceptionHandler}.
 * A mensagem e generica ao cliente; o detalhe tecnico fica apenas no log.
 *
 * @see br.com.agrocontabil.service.GeracaoNfaService
 */
public class NfaEmitenteInvalidoException extends RuntimeException {

  public NfaEmitenteInvalidoException(String message) {
    super(message);
  }
}
```

- [ ] **Step 2: Mapear a exceção no `GlobalExceptionHandler` (422)**

Em `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`, adicione um handler logo após o `handleXmlGenerationException` (após a linha 110):

```java
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
```

(`NfaEmitenteInvalidoException` está no mesmo pacote `br.com.agrocontabil.exception`, não precisa de import.)

- [ ] **Step 3: Compilar**

Run: `./mvnw test-compile`
Expected: BUILD SUCCESS (sem erros de compilação).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/NfaEmitenteInvalidoException.java \
        src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java
git commit -m "feat(nfa): NfaEmitenteInvalidoException mapeada para 422"
```

---

## Task 5: `GeracaoNfaService` — orquestra a NFA compondo o pipeline NF-e

**Files:**
- Create: `src/main/java/br/com/agrocontabil/service/GeracaoNfaService.java`
- Test: `src/test/java/br/com/agrocontabil/service/GeracaoNfaServiceTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/service/GeracaoNfaServiceTest.java`:

```java
package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.model.TipoProdutor;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link GeracaoNfaService}.
 *
 * <p>O pipeline de XML ({@link GeracaoNotaFiscalService}) e mockado — aqui validamos
 * apenas as pre-condicoes especificas da NFA (emitente PF com CPF valido) e o
 * roteamento da geracao.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeracaoNfaService — pre-validacao e delegacao da NFA")
class GeracaoNfaServiceTest {

  @Mock
  private GeracaoNotaFiscalService geracaoNotaFiscalService;

  @InjectMocks
  private GeracaoNfaService service;

  private NotaFiscal nota;

  @BeforeEach
  void setUp() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725") // CPF valido
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .estado("PR")
        .build();

    nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .tipoDocumento(TipoDocumento.NFA)
        .produtor(produtor)
        .build();
  }

  @Test
  @DisplayName("delega ao pipeline NF-e quando emitente e PF com CPF valido")
  void delegaQuandoPfComCpfValido() {
    when(geracaoNotaFiscalService.gerarXml(nota)).thenReturn("<xml/>");

    String xml = service.gerarXml(nota);

    assertThat(xml).isEqualTo("<xml/>");
    verify(geracaoNotaFiscalService).gerarXml(nota);
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e pessoa juridica")
  void rejeitaProdutorPj() {
    nota.getProdutor().setTipoProdutor(TipoProdutor.PESSOA_JURIDICA);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o CPF do produtor e invalido")
  void rejeitaCpfInvalido() {
    nota.getProdutor().setCpfCnpj("12345678901"); // CPF invalido

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }

  @Test
  @DisplayName("rejeita (422) quando o produtor e nulo")
  void rejeitaProdutorNulo() {
    nota.setProdutor(null);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);

    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=GeracaoNfaServiceTest`
Expected: FAIL com erro de compilação ("cannot find symbol GeracaoNfaService").

- [ ] **Step 3: Implementar `GeracaoNfaService`**

Create `src/main/java/br/com/agrocontabil/service/GeracaoNfaService.java`:

```java
package br.com.agrocontabil.service;

import br.com.agrocontabil.common.validation.CpfUtils;
import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra a geracao da Nota Fiscal Avulsa (NFA-e) do produtor rural pessoa fisica.
 *
 * <p>A NFA-e reusa o layout NF-e 4.00 modelo 55: este servico nao duplica o pipeline
 * de marshalling/validacao XSD — ele <strong>compoe</strong> o
 * {@link GeracaoNotaFiscalService}, adicionando apenas as pre-condicoes especificas
 * da NFA antes de delegar:
 * <ol>
 *   <li>O emitente (produtor) deve ser {@link TipoProdutor#PESSOA_FISICA}.</li>
 *   <li>O CPF do emitente deve ser estruturalmente valido (modulo 11).</li>
 * </ol>
 *
 * <p>Qualquer violacao lanca {@link NfaEmitenteInvalidoException} (HTTP 422). O
 * {@link br.com.agrocontabil.nfe.NotaFiscalNfeMapper} ja emite {@code <CPF>} para
 * emitente PF, portanto o XML resultante esta estruturalmente correto para a NFA.
 */
@Service
public class GeracaoNfaService {

  private static final Logger log = LoggerFactory.getLogger(GeracaoNfaService.class);

  private final GeracaoNotaFiscalService geracaoNotaFiscalService;

  public GeracaoNfaService(GeracaoNotaFiscalService geracaoNotaFiscalService) {
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
  }

  /**
   * Valida o emitente PF + CPF e gera o XML da NFA delegando ao pipeline NF-e.
   *
   * @param nota nota fiscal com {@code tipoDocumento = NFA} e produtor PF associado
   * @return XML da NFA-e (layout 4.00, modelo 55), validado contra o XSD
   * @throws NfaEmitenteInvalidoException se o produtor nao for PF ou o CPF for invalido
   */
  public String gerarXml(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();

    if (produtor == null || produtor.getTipoProdutor() != TipoProdutor.PESSOA_FISICA) {
      log.warn("Geracao de NFA rejeitada: emitente nao e pessoa fisica. notaId={}",
          nota.getId());
      throw new NfaEmitenteInvalidoException(
          "NFA exige emitente pessoa fisica (produtor rural PF).");
    }

    if (!CpfUtils.isValid(produtor.getCpfCnpj())) {
      log.warn("Geracao de NFA rejeitada: CPF do emitente invalido. notaId={}",
          nota.getId());
      throw new NfaEmitenteInvalidoException(
          "CPF do produtor emitente da NFA e invalido.");
    }

    log.info("Gerando XML da NFA: notaId={} produtor={}", nota.getId(), produtor.getId());
    return geracaoNotaFiscalService.gerarXml(nota);
  }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `./mvnw test -Dtest=GeracaoNfaServiceTest`
Expected: PASS (4 cenários).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/GeracaoNfaService.java \
        src/test/java/br/com/agrocontabil/service/GeracaoNfaServiceTest.java
git commit -m "feat(nfa): GeracaoNfaService valida emitente PF/CPF e delega ao pipeline NF-e"
```

---

## Task 6: Marcador NFA no XML (`infCpl` do mapper)

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapper.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapperNfaTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapperNfaTest.java`:

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.model.InfAdic;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotaFiscalNfeMapper — diferenciacao NFA no infCpl")
class NotaFiscalNfeMapperNfaTest {

  private NotaFiscalNfeMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new NotaFiscalNfeMapper(new ChaveAcessoGenerator());
  }

  @Test
  @DisplayName("infCpl contem marcador de NOTA FISCAL AVULSA quando tipoDocumento = NFA")
  void infCplContemMarcadorNfa() {
    NFe nfe = mapper.toNfe(buildNota(TipoDocumento.NFA));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("infCpl NAO contem marcador de NFA quando tipoDocumento = NFE")
  void infCplSemMarcadorParaNfe() {
    NFe nfe = mapper.toNfe(buildNota(TipoDocumento.NFE));

    InfAdic infAdic = nfe.getInfNFe().getInfAdic();
    assertThat(infAdic.getInfCpl()).doesNotContain("NOTA FISCAL AVULSA");
  }

  private NotaFiscal buildNota(TipoDocumento tipo) {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao")
        .ncm("12010090")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .build();

    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores")
        .numero("100")
        .municipio("Curitiba")
        .codigoMunicipioIbge("4106902")
        .uf("PR")
        .cep("80010000")
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .tipoDocumento(tipo)
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();

    nota.addItem(item);
    return nota;
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=NotaFiscalNfeMapperNfaTest`
Expected: FAIL — o teste `infCplContemMarcadorNfa` falha (o `infCpl` atual é estático e não contém "NOTA FISCAL AVULSA").

- [ ] **Step 3: Diferenciar o `infCpl` por `tipoDocumento`**

Em `src/main/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapper.java`:

(a) No método `toNfe`, troque a chamada `infNFe.setInfAdic(buildInfAdic());` (linha 151) por:

```java
    infNFe.setInfAdic(buildInfAdic(nota));
```

(b) Adicione o import de `TipoDocumento` no topo (junto aos demais imports de `br.com.agrocontabil.model`):

```java
import br.com.agrocontabil.model.TipoDocumento;
```

(c) Substitua o método `buildInfAdic()` (linhas 478-484) por:

```java
  private InfAdic buildInfAdic(NotaFiscal nota) {
    StringBuilder infCpl = new StringBuilder(
        "NOTA FISCAL EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL. "
            + "IBS/CBS INFORMATIVOS - ANO-TESTE 2026 - EC 132/2023 / LC 214/2025.");
    if (nota.getTipoDocumento() == TipoDocumento.NFA) {
      infCpl.append(" NOTA FISCAL AVULSA - PRODUTOR RURAL PESSOA FISICA.");
    }
    InfAdic infAdic = new InfAdic();
    infAdic.setInfCpl(infCpl.toString());
    return infAdic;
  }
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `./mvnw test -Dtest=NotaFiscalNfeMapperNfaTest`
Expected: PASS (ambos os cenários).

- [ ] **Step 5: Garantir que o teste de NF-e existente continua verde**

Run: `./mvnw test -Dtest=GeracaoNotaFiscalServiceTest`
Expected: PASS (a mudança no `infCpl` é compatível — o XSD aceita o texto adicional e os asserts existentes não checam o `infCpl`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapper.java \
        src/test/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapperNfaTest.java
git commit -m "feat(nfa): marcador NOTA FISCAL AVULSA no infCpl do XML"
```

---

## Task 7: Roteamento em `NotaFiscalService.gerarXml` por `tipoDocumento`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`
- Test: `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`

- [ ] **Step 1: Escrever os testes que falham**

Em `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`:

(a) Adicione um mock para o `GeracaoNfaService`, logo após o mock `geracaoNotaFiscalService` (após a linha 77):

```java
  @Mock
  private GeracaoNfaService geracaoNfaService;
```

(b) Adicione os imports necessários (junto aos demais imports):

```java
import br.com.agrocontabil.model.TipoDocumento;
import static org.mockito.Mockito.never;
```

(`never` pode já estar importado — verifique antes de duplicar.)

(c) Adicione dois testes ao final da classe (antes do último `}` e dos helpers, seguindo o estilo dos demais):

```java
  @Test
  @DisplayName("gerarXml roteia para o pipeline NF-e quando tipoDocumento e NFE")
  void gerarXmlRoteiaParaNfe() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId)
        .numero(1L)
        .tipoDocumento(TipoDocumento.NFE)
        .status(StatusNotaFiscal.RASCUNHO)
        .produtor(produtor)
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(geracaoNotaFiscalService.gerarXml(nota)).thenReturn(VALID_XML);
    when(notaFiscalRepository.save(any(NotaFiscal.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    notaFiscalService.gerarXml(notaId);

    verify(geracaoNotaFiscalService).gerarXml(nota);
    verify(geracaoNfaService, never()).gerarXml(any());
  }

  @Test
  @DisplayName("gerarXml roteia para o pipeline NFA quando tipoDocumento e NFA")
  void gerarXmlRoteiaParaNfa() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId)
        .numero(1L)
        .tipoDocumento(TipoDocumento.NFA)
        .status(StatusNotaFiscal.RASCUNHO)
        .produtor(produtor)
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(geracaoNfaService.gerarXml(nota)).thenReturn(VALID_XML);
    when(notaFiscalRepository.save(any(NotaFiscal.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    notaFiscalService.gerarXml(notaId);

    verify(geracaoNfaService).gerarXml(nota);
    verify(geracaoNotaFiscalService, never()).gerarXml(any());
  }
```

(d) Adicione, no topo da classe junto aos campos, uma constante com um XML que contém a chave de acesso no formato esperado por `extrairChaveAcessoDoXml` (`Id="NFe" + 44 dígitos`):

```java
  /** XML minimo com atributo Id no formato exigido por extrairChaveAcessoDoXml. */
  private static final String VALID_XML =
      "<?xml version=\"1.0\"?><NFe><infNFe Id=\"NFe"
          + "41262612345678000195550010000000011000000012\"></infNFe></NFe>";
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=NotaFiscalServiceTest`
Expected: FAIL — `geracaoNfaService` não existe como dependência de `NotaFiscalService` (erro de compilação no `@InjectMocks`) e o roteamento NFA ainda não existe.

- [ ] **Step 3: Injetar `GeracaoNfaService` e rotear em `NotaFiscalService`**

Em `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`:

(a) Adicione o import:

```java
import br.com.agrocontabil.model.TipoDocumento;
```

(b) Adicione o campo (após `geracaoNotaFiscalService`, linha 56):

```java
  private final GeracaoNfaService geracaoNfaService;
```

(c) Atualize o construtor (linhas 58-67) para receber e atribuir o novo colaborador:

```java
  public NotaFiscalService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      CalculoIbsCbsService calculoIbsCbsService,
      GeracaoNotaFiscalService geracaoNotaFiscalService,
      GeracaoNfaService geracaoNfaService) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.calculoIbsCbsService = calculoIbsCbsService;
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
    this.geracaoNfaService = geracaoNfaService;
  }
```

(d) No método `gerarXml(UUID)`, troque a linha que gera o XML (linha 240):

```java
    String xml = geracaoNotaFiscalService.gerarXml(nota);
```

por um roteamento por tipo de documento:

```java
    String xml = nota.getTipoDocumento() == TipoDocumento.NFA
        ? geracaoNfaService.gerarXml(nota)
        : geracaoNotaFiscalService.gerarXml(nota);
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `./mvnw test -Dtest=NotaFiscalServiceTest`
Expected: PASS (incluindo os dois novos testes de roteamento e todos os pré-existentes).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/NotaFiscalService.java \
        src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java
git commit -m "feat(nfa): NotaFiscalService roteia gerarXml por tipoDocumento"
```

---

## Task 8: Expor `tipoDocumento` nos DTOs e no mapper

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/dto/NotaFiscalRequest.java`
- Modify: `src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java`
- Modify: `src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java`
- Modify (call sites): `src/test/java/br/com/agrocontabil/mapper/NotaFiscalMapperTest.java`,
  `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`,
  `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`
- Test: `src/test/java/br/com/agrocontabil/mapper/NotaFiscalMapperTest.java`

- [ ] **Step 1: Escrever o teste que falha (mapper seta tipoDocumento)**

Em `src/test/java/br/com/agrocontabil/mapper/NotaFiscalMapperTest.java`, adicione um teste novo (siga o estilo da classe; use o helper de request existente — ver Step 5 sobre a assinatura atualizada). O teste valida o default e o valor explícito:

```java
  @Test
  @DisplayName("toEntity usa NFE como default quando tipoDocumento e null")
  void toEntityDefaultNfe() {
    NotaFiscalRequest request = buildValidRequest(); // helper existente, tipoDocumento = null
    NotaFiscal nota = NotaFiscalMapper.toEntity(request, buildProdutor());
    assertThat(nota.getTipoDocumento()).isEqualTo(br.com.agrocontabil.model.TipoDocumento.NFE);
  }

  @Test
  @DisplayName("toResponse expoe o tipoDocumento da entidade")
  void toResponseExpoeTipoDocumento() {
    NotaFiscal nota = NotaFiscalMapper.toEntity(buildValidRequest(), buildProdutor());
    nota.setTipoDocumento(br.com.agrocontabil.model.TipoDocumento.NFA);
    NotaFiscalResponse response = NotaFiscalMapper.toResponse(nota);
    assertThat(response.tipoDocumento()).isEqualTo(br.com.agrocontabil.model.TipoDocumento.NFA);
  }
```

> Nota: se os helpers `buildValidRequest()`/`buildProdutor()` não existirem com esses nomes na classe, use os helpers equivalentes já presentes (ex.: o método na linha 417 que retorna `NotaFiscalRequest`, e a construção de `Produtor` usada nos demais testes). O objetivo dos asserts é o que importa.

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./mvnw test -Dtest=NotaFiscalMapperTest`
Expected: FAIL — `request` não tem componente `tipoDocumento` e `response.tipoDocumento()` não existe (erro de compilação).

- [ ] **Step 3: Adicionar `tipoDocumento` ao `NotaFiscalRequest` (último componente)**

Em `src/main/java/br/com/agrocontabil/dto/NotaFiscalRequest.java`:

(a) Adicione os imports:

```java
import br.com.agrocontabil.model.TipoDocumento;
```

(b) Adicione um novo componente **ao final** do record (após `List<NotaFiscalItemRequest> itens`, adicionando uma vírgula após `itens`):

```java
    @Schema(
        description = "Tipo do documento: NFE (padrao) ou NFA (Nota Fiscal Avulsa "
            + "do produtor rural pessoa fisica)",
        example = "NFE")
    TipoDocumento tipoDocumento
```

- [ ] **Step 4: Adicionar `tipoDocumento` ao `NotaFiscalResponse` (último componente)**

Em `src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java`:

(a) Adicione o import:

```java
import br.com.agrocontabil.model.TipoDocumento;
```

(b) Adicione um novo componente **ao final** do record (após `OffsetDateTime updatedAt`, adicionando vírgula após `updatedAt`):

```java
    ,
    @Schema(description = "Tipo do documento fiscal (NFE ou NFA)", example = "NFE")
    TipoDocumento tipoDocumento
```

- [ ] **Step 5: Atualizar o `NotaFiscalMapper`**

Em `src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java`:

(a) Adicione o import:

```java
import br.com.agrocontabil.model.TipoDocumento;
```

(b) No `toEntity`, adicione ao builder (após `.modelo("55")`, linha 58):

```java
        .tipoDocumento(request.tipoDocumento() != null
            ? request.tipoDocumento() : TipoDocumento.NFE)
```

(c) No `toResponse`, adicione o argumento `nota.getTipoDocumento()` como **último** argumento do `new NotaFiscalResponse(...)` (após `nota.getUpdatedAt()`, linha 117 — acrescente vírgula):

```java
        nota.getUpdatedAt(),
        nota.getTipoDocumento());
```

- [ ] **Step 6: Atualizar os call sites de `new NotaFiscalRequest(...)` nos testes**

O record ganhou um componente final. Em **cada** uma das chamadas abaixo, acrescente `, null` (ou `, TipoDocumento.NFE`) como último argumento, antes do `)` de fechamento:

- `NotaFiscalMapperTest.java`: linhas ~86, ~101, ~230, ~417
- `NotaFiscalServiceTest.java`: linhas ~306, ~350, ~386, ~429, ~505, ~703

> Dica: o último argumento atual é a lista `itens` (`List.of(...)` ou similar). Adicione `, null` logo após o fechamento dessa lista e antes do `)` do construtor. Onde o teste precisa criar uma NFA explicitamente, use `, TipoDocumento.NFA` e importe `TipoDocumento`.

- [ ] **Step 7: Atualizar os call sites de `new NotaFiscalResponse(...)` no controller test**

Em `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java` (chamadas nas linhas ~417 e ~442): acrescente `, br.com.agrocontabil.model.TipoDocumento.NFE` como último argumento (após o argumento `updatedAt`), antes do `)`.

- [ ] **Step 8: Rodar a suíte de DTO/mapper/controller e confirmar verde**

Run: `./mvnw test -Dtest=NotaFiscalMapperTest,NotaFiscalServiceTest,NotaFiscalControllerTest`
Expected: PASS (todas as classes; os call sites compilam com o novo componente).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/br/com/agrocontabil/dto/NotaFiscalRequest.java \
        src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java \
        src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java \
        src/test/java/br/com/agrocontabil/mapper/NotaFiscalMapperTest.java \
        src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java \
        src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(nfa): expoe tipoDocumento nos DTOs de request/response"
```

---

## Task 9: Teste de pipeline real da NFA (end-to-end do mapper ao XSD) e verificação final

**Files:**
- Test: `src/test/java/br/com/agrocontabil/service/GeracaoNfaServicePipelineTest.java`

- [ ] **Step 1: Escrever o teste de pipeline real**

Diferente do `GeracaoNfaServiceTest` (que mocka o pipeline), este monta os componentes reais — como `GeracaoNotaFiscalServiceTest` faz — e valida que a NFA de um produtor PF gera XML válido contra o XSD, com `<CPF>` no emitente e o marcador NFA.

Create `src/test/java/br/com/agrocontabil/service/GeracaoNfaServicePipelineTest.java`:

```java
package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GeracaoNfaService — pipeline real (sem Spring, sem banco)")
class GeracaoNfaServicePipelineTest {

  private GeracaoNfaService nfaService;
  private CalculoIbsCbsService calculoIbsCbsService;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    calculoIbsCbsService = new CalculoIbsCbsService(props);

    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()),
        new XmlGenerator(),
        new XsdValidator());
    nfaService = new GeracaoNfaService(pipeline);
  }

  @Test
  @DisplayName("gera XML valido com CPF do emitente e marcador NFA para produtor PF")
  void geraXmlValidoParaNfaPf() {
    NotaFiscal nota = buildNfaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xml = nfaService.gerarXml(nota);

    assertThat(xml).startsWith("<?xml");
    // Emitente PF: tag <CPF> presente (zero-padded para 11 digitos)
    assertThat(xml).contains("<CPF>52998224725</CPF>");
    // Marcador de NFA no infCpl
    assertThat(xml).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("rejeita (422) NFA cujo emitente e PJ, sem gerar XML")
  void rejeitaNfaComEmitentePj() {
    NotaFiscal nota = buildNfaCompleta();
    nota.getProdutor().setTipoProdutor(TipoProdutor.PESSOA_JURIDICA);
    nota.getProdutor().setCpfCnpj("12345678000195");
    calculoIbsCbsService.calcular(nota);

    assertThatThrownBy(() -> nfaService.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);
  }

  private NotaFiscal buildNfaCompleta() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao safra 2026")
        .ncm("12010090")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores")
        .numero("100")
        .municipio("Curitiba")
        .codigoMunicipioIbge("4106902")
        .uf("PR")
        .cep("80010000")
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .tipoDocumento(TipoDocumento.NFA)
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();

    nota.addItem(item);
    return nota;
  }
}
```

- [ ] **Step 2: Rodar e confirmar que passa**

Run: `./mvnw test -Dtest=GeracaoNfaServicePipelineTest`
Expected: PASS (XML válido com `<CPF>` e marcador NFA; PJ rejeitado).

> Se o assert do `<CPF>` falhar por zero-padding, confirme o valor gerado: `52998224725` já tem 11 dígitos, então `<CPF>52998224725</CPF>` é o esperado.

- [ ] **Step 3: Rodar a suíte completa**

Run: `./mvnw test`
Expected: BUILD SUCCESS — todos os testes (pré-existentes + novos) verdes.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/br/com/agrocontabil/service/GeracaoNfaServicePipelineTest.java
git commit -m "test(nfa): pipeline real gera XML valido de NFA para produtor PF"
```

---

## Self-Review (preenchido pelo autor do plano)

**Cobertura do spec:**
- Discriminador `TipoDocumento` no código e banco → Task 1 ✓
- Validação de CPF (Bean Validation customizado) → Tasks 2 (lógica) + 3 (`@Cpf`) ✓
- `GeracaoNfaService` dedicado → Task 5 ✓
- Diferenciação no documento gerado (infCpl) → Task 6 ✓
- Roteamento + criação via `tipoDocumento` no request/response → Tasks 7 + 8 ✓
- Exceção 422 para emitente inválido → Task 4 ✓
- Testes (CpfUtils, CpfValidator, GeracaoNfaService, pipeline real, roteamento) → Tasks 2,3,5,7,9 ✓
- DoD "NFA gerada para produtor PF sem erros" → Task 9 (pipeline real, XSD válido) ✓

**Consistência de tipos/assinaturas:**
- `CpfUtils.isValid(String)` usado em Task 3 e Task 5 — assinatura idêntica ✓
- `GeracaoNfaService.gerarXml(NotaFiscal)` definido em Task 5, usado em Tasks 7 e 9 ✓
- `TipoDocumento {NFE, NFA}` consistente em todas as tasks ✓
- `NfaEmitenteInvalidoException(String)` definido em Task 4, lançado em Task 5 ✓
- Novo componente `tipoDocumento` adicionado ao **final** de ambos os records — todos os call sites atualizados em Task 8 ✓

**Placeholders:** nenhum "TODO"/"TBD"; todo passo de código mostra o código.

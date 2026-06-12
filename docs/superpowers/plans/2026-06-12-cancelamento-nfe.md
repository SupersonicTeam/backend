# Cancelamento de NF-e (RN006) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cancelar uma NF-e autorizada via `POST /api/notas/{id}/cancelar`, respeitando o limite de 30 dias após a emissão (RN006), enviando o pedido a um `SefazService` (mock).

**Architecture:** O `NotaFiscalService.cancelar` valida ownership (404), status AUTORIZADA (409 via `NotaFiscalNaoEditavelException`) e o prazo de 30 dias (422 via `BusinessException` nova); delega o cancelamento ao `SefazService` (interface + `MockSefazService`); persiste `status=CANCELADA` + data/motivo/protocolo. Backend apenas (frontend é repo separado).

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Web, Spring Data JPA, Liquibase, Bean Validation, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-12-cancelamento-nfe-design.md`

**Branch:** `sprint-2--notas-fiscais`.

---

## Notas gerais para o executor

- **Comando de teste:** `.\mvnw.cmd test` (PowerShell) ou `./mvnw test` (bash). Um só: `-Dtest=NomeDaClasse`.
- **Infra de teste:** profile `test` desabilita Liquibase e usa `create-drop`; `@DataJpaTest` usa `schema-nota-fiscal-test.sql` (`ddl-auto=none`) — colunas novas da entidade `NotaFiscal` PRECISAM ser adicionadas nesse script.
- **`@WebMvcTest`** (`NotaFiscalControllerTest`) mocka `NotaFiscalService`, `ProdutorRepository`, `NcmAgropecuarioRepository`, `CfopProdutorRuralRepository`, `ImportacaoNotaFiscalService`.
- **Adicionar componente a um `record`** (`NotaFiscalResponse`) quebra os construtores — atualizar todos os call sites (mapper + testes do controller).
- **Estilo:** `@DisplayName` em português, AssertJ, Lombok. Conventional Commits. Nunca editar changesets 0001-0007.

---

## Task 1: `BusinessException` + mapeamento 422

**Files:**
- Create: `src/main/java/br/com/agrocontabil/exception/BusinessException.java`
- Modify: `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Criar a exceção**

Create `src/main/java/br/com/agrocontabil/exception/BusinessException.java`:

```java
package br.com.agrocontabil.exception;

/**
 * Violação de uma regra de negócio que impede a conclusão da operação, embora a
 * requisição esteja sintaticamente válida.
 *
 * <p>Mapeada para HTTP 422 (Unprocessable Entity) pelo {@code GlobalExceptionHandler}.
 * A mensagem é descritiva e segura para o cliente. Usada, por exemplo, pela regra
 * dos 30 dias do cancelamento de NF-e (RN006).
 */
public class BusinessException extends RuntimeException {

  public BusinessException(String message) {
    super(message);
  }
}
```

- [ ] **Step 2: Mapear para 422 no `GlobalExceptionHandler`**

Em `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`, adicione um handler (junto aos demais de regra de negócio; antes do `handleIllegalArgument`):

```java
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
```

`BusinessException` está no mesmo pacote — sem import. Os demais tipos já estão importados.

- [ ] **Step 3: Compilar**

Run: `.\mvnw.cmd test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/BusinessException.java src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java
git commit -m "feat(cancelamento): BusinessException mapeada para 422"
```

---

## Task 2: `SefazService` (interface) + `MockSefazService`

**Files:**
- Create: `src/main/java/br/com/agrocontabil/service/sefaz/CancelamentoSefazResult.java`
- Create: `src/main/java/br/com/agrocontabil/service/sefaz/SefazService.java`
- Create: `src/main/java/br/com/agrocontabil/service/sefaz/MockSefazService.java`
- Test: `src/test/java/br/com/agrocontabil/service/sefaz/MockSefazServiceTest.java`

- [ ] **Step 1: Criar o record de resultado**

Create `src/main/java/br/com/agrocontabil/service/sefaz/CancelamentoSefazResult.java`:

```java
package br.com.agrocontabil.service.sefaz;

import java.time.OffsetDateTime;

/**
 * Resultado do pedido de cancelamento junto à SEFAZ.
 *
 * @param protocolo número do protocolo do evento de cancelamento
 * @param dataHora  data/hora do registro do cancelamento
 */
public record CancelamentoSefazResult(String protocolo, OffsetDateTime dataHora) {}
```

- [ ] **Step 2: Criar a interface**

Create `src/main/java/br/com/agrocontabil/service/sefaz/SefazService.java`:

```java
package br.com.agrocontabil.service.sefaz;

/**
 * Abstração da integração com a SEFAZ para eventos da NF-e.
 *
 * <p>Nesta fase existe apenas a implementação mock ({@link MockSefazService}). A
 * integração real (transmissão SOAP, assinatura ICP-Brasil, evento 110111 de
 * cancelamento) será implementada na Sprint 7, sem alterar este contrato.
 */
public interface SefazService {

  /**
   * Solicita o cancelamento de uma NF-e autorizada.
   *
   * @param chaveAcesso chave de acesso (44 dígitos) da NF-e
   * @param motivo      justificativa do cancelamento (15–255 caracteres)
   * @return resultado com o protocolo e a data/hora do cancelamento
   */
  CancelamentoSefazResult cancelar(String chaveAcesso, String motivo);
}
```

- [ ] **Step 3: Escrever o teste do mock (falha)**

Create `src/test/java/br/com/agrocontabil/service/sefaz/MockSefazServiceTest.java`:

```java
package br.com.agrocontabil.service.sefaz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MockSefazService — cancelamento mock")
class MockSefazServiceTest {

  private final MockSefazService service = new MockSefazService();

  @Test
  @DisplayName("retorna protocolo nao-vazio e dataHora ao cancelar")
  void cancelarRetornaProtocolo() {
    CancelamentoSefazResult result =
        service.cancelar("41260612345678000195550010000000011000000017", "Erro de digitacao nos dados");

    assertThat(result).isNotNull();
    assertThat(result.protocolo()).isNotBlank();
    assertThat(result.dataHora()).isNotNull();
  }
}
```

- [ ] **Step 4: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=MockSefazServiceTest`
Expected: FAIL (compilação: `MockSefazService` não existe).

- [ ] **Step 5: Implementar o mock**

Create `src/main/java/br/com/agrocontabil/service/sefaz/MockSefazService.java`:

```java
package br.com.agrocontabil.service.sefaz;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementação mock do {@link SefazService} para as sprints anteriores à
 * integração real (Sprint 7). Gera um protocolo determinístico e não realiza
 * qualquer comunicação externa.
 *
 * <p><strong>Não usar em produção real:</strong> nenhuma transmissão SOAP nem
 * assinatura digital é feita aqui.
 */
@Service
public class MockSefazService implements SefazService {

  private static final Logger log = LoggerFactory.getLogger(MockSefazService.class);

  private final AtomicLong sequencia = new AtomicLong(1);

  @Override
  public CancelamentoSefazResult cancelar(String chaveAcesso, String motivo) {
    String protocolo = "MOCK" + String.format("%015d", sequencia.getAndIncrement());
    log.info("MOCK SEFAZ: cancelamento simulado chave={} protocolo={}", chaveAcesso, protocolo);
    return new CancelamentoSefazResult(protocolo, OffsetDateTime.now());
  }
}
```

- [ ] **Step 6: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=MockSefazServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/sefaz/CancelamentoSefazResult.java src/main/java/br/com/agrocontabil/service/sefaz/SefazService.java src/main/java/br/com/agrocontabil/service/sefaz/MockSefazService.java src/test/java/br/com/agrocontabil/service/sefaz/MockSefazServiceTest.java
git commit -m "feat(cancelamento): SefazService (interface) + MockSefazService"
```

---

## Task 3: Migration 0008 + campos de cancelamento na entidade

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`
- Create: `src/main/resources/db/changelog/changes/0008-nota-fiscal-cancelamento.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `src/test/resources/schema-nota-fiscal-test.sql`

- [ ] **Step 1: Campos na entidade**

Em `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`, logo após o campo `dataGeracaoXml` (que termina com `private OffsetDateTime dataGeracaoXml;`) e antes de `created_at`, adicione:

```java
  /**
   * Data e hora do cancelamento da nota junto à SEFAZ. Nula até o cancelamento.
   */
  @Column(name = "data_cancelamento")
  private OffsetDateTime dataCancelamento;

  /** Justificativa do cancelamento (15–255 caracteres). Nula até o cancelamento. */
  @Column(name = "motivo_cancelamento", length = 255)
  private String motivoCancelamento;

  /** Protocolo do evento de cancelamento retornado pela SEFAZ. Nulo até o cancelamento. */
  @Column(name = "protocolo_cancelamento", length = 50)
  private String protocoloCancelamento;
```

(`@Column`, `OffsetDateTime` já importados.)

- [ ] **Step 2: Migration 0008**

Create `src/main/resources/db/changelog/changes/0008-nota-fiscal-cancelamento.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- ================================================================
         0008-nota-fiscal-cancelamento.xml
         Sprint 3 — issue #21 (RN006): cancelamento de NF-e.

         Adiciona em nota_fiscal (todas nullable):
           - data_cancelamento       TIMESTAMP WITH TZ
           - motivo_cancelamento      VARCHAR(255)
           - protocolo_cancelamento   VARCHAR(50)

         A coluna status ja existe (enum inclui CANCELADA).
         NAO editar changesets anteriores (0001-0007).
         ================================================================ -->

    <changeSet id="0008-nota-fiscal-add-cancelamento" author="supersonic-team">
        <comment>Campos de cancelamento da NF-e (data, motivo, protocolo).</comment>

        <addColumn tableName="nota_fiscal">
            <column name="data_cancelamento" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="true"/>
            </column>
        </addColumn>
        <addColumn tableName="nota_fiscal">
            <column name="motivo_cancelamento" type="VARCHAR(255)">
                <constraints nullable="true"/>
            </column>
        </addColumn>
        <addColumn tableName="nota_fiscal">
            <column name="protocolo_cancelamento" type="VARCHAR(50)">
                <constraints nullable="true"/>
            </column>
        </addColumn>

        <rollback>
            <dropColumn tableName="nota_fiscal" columnName="data_cancelamento"/>
            <dropColumn tableName="nota_fiscal" columnName="motivo_cancelamento"/>
            <dropColumn tableName="nota_fiscal" columnName="protocolo_cancelamento"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Incluir no master changelog**

Em `src/main/resources/db/changelog/db.changelog-master.yaml`, após o include do `0007-nota-fiscal-importacao.xml`, adicione:

```yaml
  - include:
      file: db/changelog/changes/0008-nota-fiscal-cancelamento.xml
```

- [ ] **Step 4: Atualizar o schema H2 de teste**

Em `src/test/resources/schema-nota-fiscal-test.sql`, na tabela `nota_fiscal`, após a coluna `emitente_cpf_cnpj` (e antes de `created_at`), adicione:

```sql
    data_cancelamento           TIMESTAMP WITH TIME ZONE,
    motivo_cancelamento         VARCHAR(255),
    protocolo_cancelamento      VARCHAR(50),
```

- [ ] **Step 5: Verificar contexto e repo test**

Run: `.\mvnw.cmd test -Dtest=AgroContabilApplicationTests,NotaFiscalRepositoryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/model/NotaFiscal.java src/main/resources/db/changelog/changes/0008-nota-fiscal-cancelamento.xml src/main/resources/db/changelog/db.changelog-master.yaml src/test/resources/schema-nota-fiscal-test.sql
git commit -m "feat(cancelamento): campos data/motivo/protocolo + migration 0008"
```

---

## Task 4: `CancelamentoRequest` + expor cancelamento no `NotaFiscalResponse`

**Files:**
- Create: `src/main/java/br/com/agrocontabil/dto/CancelamentoRequest.java`
- Modify: `src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java`
- Modify: `src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java`
- Modify (call sites): `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`

- [ ] **Step 1: Criar o DTO de request**

Create `src/main/java/br/com/agrocontabil/dto/CancelamentoRequest.java`:

```java
package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada para o cancelamento de uma NF-e.
 *
 * <p>O motivo (justificativa) é obrigatório e deve ter 15–255 caracteres, espelhando
 * a regra da SEFAZ para o campo {@code xJust} do evento de cancelamento.
 */
@Schema(description = "Dados para cancelamento de uma nota fiscal")
public record CancelamentoRequest(

    @Schema(description = "Justificativa do cancelamento (15 a 255 caracteres)",
        example = "Erro na emissao: dados do destinatario incorretos")
    @NotBlank(message = "motivo e obrigatorio")
    @Size(min = 15, max = 255, message = "motivo deve ter entre 15 e 255 caracteres")
    String motivo
) {}
```

- [ ] **Step 2: READ `NotaFiscalResponse.java`** e localizar o último componente do record (`tipoDocumento`).

- [ ] **Step 3: Adicionar componentes ao `NotaFiscalResponse` (após `tipoDocumento`)**

Em `src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java`, o último componente é `TipoDocumento tipoDocumento`. Adicione vírgula após ele e dois novos componentes finais:

```java
    ,
    @Schema(description = "Data/hora do cancelamento (nula se nao cancelada)")
    java.time.OffsetDateTime dataCancelamento,

    @Schema(description = "Motivo do cancelamento (nulo se nao cancelada)")
    String motivoCancelamento
```

- [ ] **Step 4: Atualizar `NotaFiscalMapper.toResponse`**

Em `src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java`, o `new NotaFiscalResponse(...)` termina hoje com `nota.getTipoDocumento())`. Troque para acrescentar os dois novos argumentos finais:

```java
        nota.getTipoDocumento(),
        nota.getDataCancelamento(),
        nota.getMotivoCancelamento());
```

- [ ] **Step 5: Atualizar os call sites de `new NotaFiscalResponse(...)` no teste do controller**

Em `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java` há 2 chamadas (`buildResponse` e `buildResponseXmlGerado`), ambas terminando com `...TipoDocumento.NFE)`. Em cada uma, acrescente `, null, null` como os dois últimos argumentos (dataCancelamento e motivoCancelamento), antes do `)`:

```java
        br.com.agrocontabil.model.TipoDocumento.NFE,
        null,
        null);
```

- [ ] **Step 6: Compilar/rodar os testes afetados**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalMapperTest,NotaFiscalControllerTest`
Expected: PASS (compilam com os novos componentes; nenhum assert quebrado).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/br/com/agrocontabil/dto/CancelamentoRequest.java src/main/java/br/com/agrocontabil/dto/NotaFiscalResponse.java src/main/java/br/com/agrocontabil/mapper/NotaFiscalMapper.java src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(cancelamento): CancelamentoRequest + expoe dados de cancelamento no response"
```

---

## Task 5: `NotaFiscalService.cancelar` + factory de exceção 409

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/exception/NotaFiscalNaoEditavelException.java`
- Modify: `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`
- Test: `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`

- [ ] **Step 1: Adicionar factory de cancelamento à exceção 409**

Em `src/main/java/br/com/agrocontabil/exception/NotaFiscalNaoEditavelException.java`, adicione um construtor privado de mensagem livre e uma factory estática (mantém o mapeamento 409 existente, com mensagem específica do cancelamento):

```java
  /** Construtor de mensagem livre, para variações de motivo (ex.: cancelamento). */
  private NotaFiscalNaoEditavelException(String message) {
    super(message);
  }

  /**
   * Cria a exceção para tentativa de cancelamento de nota fora do status AUTORIZADA.
   *
   * @param id     identificador da nota
   * @param status status atual
   * @return exceção com mensagem específica de cancelamento
   */
  public static NotaFiscalNaoEditavelException naoCancelavel(UUID id, StatusNotaFiscal status) {
    return new NotaFiscalNaoEditavelException(
        "Nota fiscal '%s' nao pode ser cancelada no status '%s'. "
            .formatted(id, status)
            + "Somente notas AUTORIZADAS podem ser canceladas.");
  }
```

(Coloque a factory e o construtor privado dentro da classe; o construtor público `(UUID, StatusNotaFiscal)` permanece.)

- [ ] **Step 2: Escrever os testes que falham**

Em `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`:

(a) Imports:
```java
import br.com.agrocontabil.dto.CancelamentoRequest;
import br.com.agrocontabil.exception.BusinessException;
import br.com.agrocontabil.service.sefaz.CancelamentoSefazResult;
import br.com.agrocontabil.service.sefaz.SefazService;
import java.time.OffsetDateTime;
```
(`when`, `verify`, `never`, `any`, `Optional`, `UUID`, `StatusNotaFiscal`, `NotaFiscal`, `assertThatThrownBy` já estão importados.)

(b) Adicione um `@Mock` do `SefazService` junto aos demais mocks:
```java
  @Mock
  private SefazService sefazService;
```

(c) Adicione os 4 testes (use o `produtor`/`produtorId` do `setUp`). Coloque-os antes dos helpers:

```java
  @Test
  @DisplayName("cancelar dentro do prazo transiciona para CANCELADA e chama a SEFAZ")
  void cancelarDentroDoPrazo() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId)
        .numero(1L)
        .status(StatusNotaFiscal.AUTORIZADA)
        .chaveAcesso("41260612345678000195550010000000011000000017")
        .dataEmissao(OffsetDateTime.now().minusDays(5))
        .produtor(produtor)
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(sefazService.cancelar(any(), any()))
        .thenReturn(new CancelamentoSefazResult("MOCK000000000000001", OffsetDateTime.now()));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    notaFiscalService.cancelar(notaId, new CancelamentoRequest("Erro nos dados do destinatario"));

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(nota.getDataCancelamento()).isNotNull();
    assertThat(nota.getProtocoloCancelamento()).isEqualTo("MOCK000000000000001");
    assertThat(nota.getMotivoCancelamento()).isEqualTo("Erro nos dados do destinatario");
    verify(sefazService).cancelar(eq("41260612345678000195550010000000011000000017"), any());
  }

  @Test
  @DisplayName("cancelar fora do prazo de 30 dias lanca BusinessException (422) sem chamar SEFAZ")
  void cancelarForaDoPrazo() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId).numero(1L).status(StatusNotaFiscal.AUTORIZADA)
        .chaveAcesso("41260612345678000195550010000000011000000017")
        .dataEmissao(OffsetDateTime.now().minusDays(31))
        .produtor(produtor)
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() ->
        notaFiscalService.cancelar(notaId, new CancelamentoRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(BusinessException.class);

    verify(sefazService, never()).cancelar(any(), any());
    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("cancelar nota nao-AUTORIZADA lanca NotaFiscalNaoEditavelException (409)")
  void cancelarStatusInvalido() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId).numero(1L).status(StatusNotaFiscal.RASCUNHO)
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() ->
        notaFiscalService.cancelar(notaId, new CancelamentoRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(NotaFiscalNaoEditavelException.class);

    verify(sefazService, never()).cancelar(any(), any());
  }

  @Test
  @DisplayName("cancelar nota de outro produtor lanca ResourceNotFoundException (404)")
  void cancelarNotaInexistente() {
    UUID notaId = UUID.randomUUID();
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() ->
        notaFiscalService.cancelar(notaId, new CancelamentoRequest("Motivo com mais de 15 chars")))
        .isInstanceOf(ResourceNotFoundException.class);
  }
```

> `eq` já deve estar importado (`import static org.mockito.ArgumentMatchers.eq;`); se não, adicione.

- [ ] **Step 3: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalServiceTest`
Expected: FAIL — `sefazService` não é dependência (erro no `@InjectMocks`) e `cancelar` não existe.

- [ ] **Step 4: Injetar `SefazService` e implementar `cancelar`**

Em `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`:

(a) Imports:
```java
import br.com.agrocontabil.dto.CancelamentoRequest;
import br.com.agrocontabil.exception.BusinessException;
import br.com.agrocontabil.service.sefaz.CancelamentoSefazResult;
import br.com.agrocontabil.service.sefaz.SefazService;
```

(b) Adicione a constante do limite (junto à `VERSAO_LAYOUT_NFE` no topo da classe):
```java
  /** Limite legal para cancelamento de NF-e após a emissão (RN006). */
  private static final int PRAZO_CANCELAMENTO_DIAS = 30;
```

(c) Adicione o campo `private final SefazService sefazService;` e inclua-o no construtor (parâmetro ao final + atribuição), ao lado de `cfopOperacaoValidator`.

(d) Adicione o método (ex.: após `gerarXml` / antes dos helpers privados):
```java
  /**
   * Cancela uma NF-e autorizada do produtor autenticado, respeitando o limite de
   * {@value #PRAZO_CANCELAMENTO_DIAS} dias após a emissão (RN006).
   *
   * @param id      identificador da nota
   * @param request justificativa do cancelamento (validada no DTO)
   * @return DTO atualizado com status CANCELADA
   * @throws ResourceNotFoundException      se não existir ou for de outro produtor (404)
   * @throws NotaFiscalNaoEditavelException se o status não for AUTORIZADA (409)
   * @throws BusinessException              se fora do prazo de 30 dias (422)
   */
  public NotaFiscalResponse cancelar(UUID id, CancelamentoRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getStatus() != StatusNotaFiscal.AUTORIZADA) {
      throw NotaFiscalNaoEditavelException.naoCancelavel(id, nota.getStatus());
    }

    long diasDesdeEmissao = java.time.Duration
        .between(nota.getDataEmissao(), OffsetDateTime.now()).toDays();
    if (diasDesdeEmissao > PRAZO_CANCELAMENTO_DIAS) {
      throw new BusinessException(
          "Cancelamento nao permitido: a nota foi emitida ha " + diasDesdeEmissao
              + " dias, acima do limite de " + PRAZO_CANCELAMENTO_DIAS
              + " dias apos a emissao (RN006).");
    }

    CancelamentoSefazResult resultado =
        sefazService.cancelar(nota.getChaveAcesso(), request.motivo());

    nota.setStatus(StatusNotaFiscal.CANCELADA);
    nota.setDataCancelamento(resultado.dataHora());
    nota.setMotivoCancelamento(request.motivo());
    nota.setProtocoloCancelamento(resultado.protocolo());

    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("NotaFiscal cancelada: id={} produtor={} protocolo={}",
        saved.getId(), produtor.getId(), resultado.protocolo());
    return NotaFiscalMapper.toResponse(saved);
  }
```

> `OffsetDateTime` já está importado no serviço (usado em `gerarXml`). `Duration` é referenciado de forma totalmente qualificada acima.

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalServiceTest`
Expected: PASS (todos os pré-existentes + os 4 novos).

> Nota sobre o limite: usar `diasDesdeEmissao > 30` significa que exatamente 30 dias ainda é permitido; 31+ é rejeitado. O teste usa 5 dias (ok) e 31 dias (rejeitado).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/NotaFiscalNaoEditavelException.java src/main/java/br/com/agrocontabil/service/NotaFiscalService.java src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java
git commit -m "feat(cancelamento): NotaFiscalService.cancelar com regra dos 30 dias (RN006)"
```

---

## Task 6: Endpoint `POST /api/notas/{id}/cancelar` + verificação final

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java`
- Test: `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`

- [ ] **Step 1: Escrever os testes que falham (controller)**

Em `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`:

(a) Import (se faltar): `import br.com.agrocontabil.dto.CancelamentoRequest;` e
`import br.com.agrocontabil.model.StatusNotaFiscal;` (provavelmente já presente).

(b) Adicione os testes (após os de gerar-xml, por exemplo). Reutilize `buildResponse`
e o `objectMapper` da classe. Para o response CANCELADA, monte um `NotaFiscalResponse`
inline ou reutilize `buildResponse` (o status exato não é o foco — o foco é o 200/422/409/400):

```java
  @Test
  @DisplayName("POST /api/notas/{id}/cancelar valido retorna 200")
  void cancelarValidoRetorna200() throws Exception {
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenReturn(buildResponse(notaId));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(notaId.toString()));
  }

  @Test
  @DisplayName("POST cancelar fora do prazo (servico lanca BusinessException) retorna 422")
  void cancelarForaDoPrazoRetorna422() throws Exception {
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenThrow(new br.com.agrocontabil.exception.BusinessException(
            "Cancelamento nao permitido: 31 dias, acima do limite de 30 dias (RN006)."));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("RN006")));
  }

  @Test
  @DisplayName("POST cancelar com status invalido (servico lanca 409) retorna 409")
  void cancelarStatusInvalidoRetorna409() throws Exception {
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenThrow(br.com.agrocontabil.exception.NotaFiscalNaoEditavelException
            .naoCancelavel(notaId, StatusNotaFiscal.RASCUNHO));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  @DisplayName("POST cancelar com motivo curto (<15) retorna 400")
  void cancelarMotivoCurtoRetorna400() throws Exception {
    UUID notaId = UUID.randomUUID();
    String payload = "{\"motivo\": \"curto\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields[?(@.field == 'motivo')]").exists());
  }
```

- [ ] **Step 2: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: FAIL — endpoint `/cancelar` não existe (404 em vez dos status esperados).

- [ ] **Step 3: Adicionar o endpoint no controller**

Em `src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java`:

(a) Import: `import br.com.agrocontabil.dto.CancelamentoRequest;`

(b) Adicione o endpoint (após `gerarXml`/`obterXml`):
```java
  /**
   * Cancela uma NF-e autorizada, respeitando o limite de 30 dias após a emissão (RN006).
   */
  @PostMapping("/{id}/cancelar")
  @Operation(
      summary = "Cancela uma NF-e autorizada",
      description = "Cancela a nota (status AUTORIZADA) dentro do limite de 30 dias apos a "
          + "emissao. Retorna 409 se o status nao for AUTORIZADA e 422 se fora do prazo.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Nota cancelada com sucesso"),
      @ApiResponse(responseCode = "400", description = "Motivo invalido",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Nota nao encontrada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "Nota nao esta em status AUTORIZADA",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422", description = "Fora do prazo de 30 dias (RN006)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<NotaFiscalResponse> cancelar(
      @PathVariable UUID id,
      @RequestBody @Valid CancelamentoRequest request) {
    return ResponseEntity.ok(notaFiscalService.cancelar(id, request));
  }
```

(`@PostMapping`, `@PathVariable`, `@RequestBody`, `@Valid`, `MediaType`, `ApiResponse`, `Content`, `Schema`, `Operation`, `ApiError`, `NotaFiscalResponse`, `ResponseEntity`, `UUID` já estão importados/uso no controller.)

- [ ] **Step 4: Rodar o teste do controller**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: PASS (todos, incluindo os 4 novos de cancelamento).

- [ ] **Step 5: Suíte completa (verificação final)**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS — todos os testes verdes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(cancelamento): endpoint POST /api/notas/{id}/cancelar"
```

---

## Self-Review (preenchido pelo autor do plano)

**Cobertura do spec:**
- Endpoint `POST /api/notas/{id}/cancelar` com DTO de motivo → Tasks 4, 6 ✓
- Validação status AUTORIZADA → Task 5 (409) ✓
- Regra 30 dias → `BusinessException` (422) → Tasks 1, 5 ✓
- Envio via `SefazService` → Tasks 2, 5 ✓
- Migration status + data_cancelamento → Task 3 ✓
- DoD "dentro do prazo funciona" / "fora → 422" → Tasks 5 (serviço) + 6 (HTTP 422) ✓
- Frontend → fora de escopo (documentado no spec) ✓

**Consistência de tipos/assinaturas:**
- `SefazService.cancelar(String, String)→CancelamentoSefazResult` (Task 2) usado em Task 5 ✓
- `CancelamentoSefazResult(protocolo, dataHora)` (Task 2) usado em Task 5 ✓
- `BusinessException(String)` (Task 1) lançada em Task 5, testada em 5/6 ✓
- `NotaFiscalNaoEditavelException.naoCancelavel(UUID, StatusNotaFiscal)` (Task 5) usada em 5/6 ✓
- `CancelamentoRequest(motivo)` (Task 4) usado em 5/6 ✓
- `NotaFiscalService.cancelar(UUID, CancelamentoRequest)` (Task 5) usado em Task 6 ✓
- Campos `dataCancelamento`/`motivoCancelamento`/`protocoloCancelamento` (Task 3) usados em 5; response expõe os 2 primeiros (Task 4) ✓

**Riscos sinalizados:**
- Adicionar componentes ao record `NotaFiscalResponse` (Task 4) quebra os 3 call sites (mapper + 2 builders do controller test) — atualizados na própria Task 4.
- Schema H2 de teste precisa das 3 colunas novas (Task 3, Step 4).
- `@WebMvcTest` já mocka `NotaFiscalService`; o endpoint novo não exige novos `@MockBean`.
- Limite: `> 30` dias rejeita (exatamente 30 permitido) — documentado.

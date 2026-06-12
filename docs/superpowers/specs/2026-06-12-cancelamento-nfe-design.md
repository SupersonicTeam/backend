# Design — Cancelamento de NF-e com limite de 30 dias (RN006)

- **Data:** 2026-06-12
- **Sprint:** 3 (issue #21 — SupersonicTeam/backend, RN006)
- **Autor:** Supersonic Team
- **Status:** aprovado para planejamento

## Contexto

A entidade `NotaFiscal` já possui o status `CANCELADA` no enum `StatusNotaFiscal`
(reservado, ainda não usado) e o campo `dataEmissao`. Esta feature implementa o
**cancelamento** de uma NF-e autorizada, respeitando o limite de **30 dias após a
emissão** (RN006), com envio do pedido a um `SefazService` (mock nesta fase; a
integração real é da Sprint 7).

Escopo: **backend apenas**. O botão/dialog Angular pertence ao repositório do
frontend (`SupersonicTeam/Agrocontabil`) e será uma tarefa separada.

## Objetivos (Definition of Done)

- Cancelamento funcional dentro do prazo (30 dias).
- Tentativa fora do prazo retorna **422 Unprocessable Entity** com mensagem clara.

## Decisões de design

1. **`SefazService`**: interface + implementação mock (`MockSefazService`) que
   retorna um protocolo fake, no padrão "mock até a Sprint X" do projeto. A
   integração real entra na Sprint 7 sem alterar o fluxo.
2. **Escopo cancelável**: exige `status == AUTORIZADA` (fiel ao RN006/spec), sem
   restrição de origem. A transição `XML_GERADO → AUTORIZADA` da nota própria virá
   na Sprint 7; o cancelamento já fica pronto.
3. **Status HTTP**:
   - Nota não-AUTORIZADA → **409 Conflict** (estado incompatível, consistente com
     `NotaFiscalNaoEditavelException`).
   - Fora do prazo de 30 dias → **422** via `BusinessException` (DoD).
   - Ownership/inexistente → **404** (anti-IDOR, padrão do projeto).
4. **Motivo**: obrigatório, **15–255 caracteres** (espelha `xJust` da SEFAZ).
5. **Frontend**: fora de escopo deste plano (repo separado).

## Componentes

### 1. Banco — migration `0008-nota-fiscal-cancelamento.xml`
Novos campos em `NotaFiscal` (a coluna `status` já existe):
- `data_cancelamento TIMESTAMP WITH TIME ZONE` (nullable)
- `motivo_cancelamento VARCHAR(255)` (nullable)
- `protocolo_cancelamento VARCHAR(50)` (nullable) — protocolo retornado pela SEFAZ
Migration adiciona as 3 colunas (nullable, rollback dropColumn) + include no master.
`schema-nota-fiscal-test.sql` recebe as 3 colunas (o `@DataJpaTest` usa schema manual).

### 2. Entidade — `NotaFiscal`
Novos campos: `dataCancelamento` (`OffsetDateTime`), `motivoCancelamento` (`String`),
`protocoloCancelamento` (`String`), com `@Column` correspondentes (todos nullable).

### 3. Exceção de negócio — `exception/BusinessException`
- `BusinessException extends RuntimeException` (mensagem descritiva), genérica para
  violações de regra de negócio → mapeada para **422** no `GlobalExceptionHandler`.
  Usada pela regra dos 30 dias (e reutilizável em regras futuras).

### 4. SEFAZ (mock) — `service/sefaz/`
- **`SefazService`** (interface): `CancelamentoSefazResult cancelar(String chaveAcesso,
  String motivo)`.
- **`CancelamentoSefazResult`** (record): `protocolo` (String), `dataHora`
  (`OffsetDateTime`).
- **`MockSefazService`** (`@Service`, impl): retorna um protocolo determinístico/fake
  (ex.: `"MOCK-" + timestamp`/sequencial) e `dataHora = now`. Loga que é mock. Documenta
  que a integração real (transmissão SOAP, certificado ICP-Brasil) é Sprint 7.

### 5. DTO de entrada — `dto/CancelamentoRequest`
- record `CancelamentoRequest(String motivo)` com `@NotBlank` + `@Size(min=15, max=255)`
  e `@Schema`. Falha de validação → 400 (handler existente).

### 6. Serviço — `NotaFiscalService.cancelar(UUID id, CancelamentoRequest req)`
Fluxo:
1. Ownership: `findByIdAndProdutorId` → 404 se inexistente/de outro produtor.
2. Status: se `status != AUTORIZADA` → `NotaFiscalNaoEditavelException` (409).
   (Reusa a exceção existente; mensagem indica que só notas AUTORIZADAS são canceláveis.)
3. Prazo: se `dataEmissao` há mais de 30 dias (`dataEmissao.plusDays(30) < now`) →
   `BusinessException` (422) com mensagem clara (dias decorridos, limite 30).
4. SEFAZ: `sefazService.cancelar(chaveAcesso, motivo)` → protocolo.
5. Persiste: `status=CANCELADA`, `dataCancelamento=now`, `motivoCancelamento=motivo`,
   `protocoloCancelamento=protocolo`; salva; retorna `NotaFiscalResponse`.

`NotaFiscalResponse` passa a expor `dataCancelamento` (e, se útil, `motivoCancelamento`).

### 7. Controller — `POST /api/notas/{id}/cancelar`
- `@RequestBody @Valid CancelamentoRequest` → `notaFiscalService.cancelar(id, req)` →
  200 com `NotaFiscalResponse` (status CANCELADA). OpenAPI: 200/404/409/422.

## Fluxo de dados

```
POST /api/notas/{id}/cancelar { motivo }
  → @Valid (motivo 15-255) — senão 400
  → NotaFiscalService.cancelar
      findByIdAndProdutorId            -> 404 anti-IDOR
      status != AUTORIZADA             -> 409
      dataEmissao + 30d < now          -> 422 (BusinessException)
      sefazService.cancelar(chave,motivo) -> protocolo (mock)
      status=CANCELADA, dataCancelamento, motivo, protocolo -> save
  -> 200 NotaFiscalResponse (CANCELADA)
```

## Tratamento de erros

- Motivo inválido (vazio/<15/>255) → **400** (Bean Validation).
- Nota inexistente/outro produtor → **404**.
- Status ≠ AUTORIZADA → **409** (`NotaFiscalNaoEditavelException`).
- Fora do prazo de 30 dias → **422** (`BusinessException`) com mensagem clara.

## Testes

- **`BusinessException` → 422**: coberto indiretamente pelo teste de controller
  (serviço lança → 422) e/ou um teste do handler.
- **`MockSefazServiceTest`**: retorna protocolo não-vazio e dataHora.
- **`NotaFiscalServiceTest`** (Mockito): cancelar dentro do prazo (AUTORIZADA, emissão
  recente) → status CANCELADA, chama `sefazService`, persiste protocolo/data/motivo;
  fora do prazo (emissão > 30 dias) → `BusinessException`, **não** chama SEFAZ nem salva;
  status ≠ AUTORIZADA → `NotaFiscalNaoEditavelException`, sem SEFAZ; nota de outro
  produtor → `ResourceNotFoundException`.
- **Controller** (`@WebMvcTest`): POST válido → 200 CANCELADA; serviço lança
  `BusinessException` → **422**; serviço lança `NotaFiscalNaoEditavelException` → 409;
  motivo curto (<15) → 400.

## Fora de escopo

- Integração real com a SEFAZ (transmissão SOAP, certificado ICP-Brasil) — Sprint 7.
- Frontend (botão/dialog Angular) — repositório separado, tarefa à parte.
- Transição `XML_GERADO → AUTORIZADA` da nota própria — Sprint 7.
- Eventos de cancelamento/sequência de eventos da NF-e (evento 110111) detalhados —
  o mock abstrai o protocolo.

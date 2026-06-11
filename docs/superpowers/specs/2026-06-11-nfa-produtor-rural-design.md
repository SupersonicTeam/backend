# Design — Geração de Nota Fiscal Avulsa (NFA) para produtor rural PF

- **Data:** 2026-06-11
- **Sprint:** 2 (issue #18 — SupersonicTeam/backend)
- **Autor:** Supersonic Team
- **Status:** aprovado para planejamento

## Contexto

O backend já gera NF-e modelo 55 (layout 4.00) via um pipeline em camadas:
`FiscalRulesValidator → NotaFiscalNfeMapper → XmlGenerator → XsdValidator`,
orquestrado por `GeracaoNotaFiscalService` e disparado por
`NotaFiscalService.gerarXml(UUID)` (transição `RASCUNHO → XML_GERADO`).

A **NFA-e (Nota Fiscal Avulsa eletrônica) do produtor rural pessoa física**
no Brasil **usa o mesmo layout NF-e 4.00 modelo 55** — não é um schema próprio.
A diferença é de *fluxo*: o emitente é o produtor PF (CPF, tipicamente sem IE
própria → `IE="ISENTO"`), emitida "avulsa" por não ter credenciamento regular
de emissor.

O `NotaFiscalNfeMapper` **já** emite `<CPF>` em vez de `<CNPJ>` quando o
emitente é PF e já usa `IE="ISENTO"` como placeholder. Logo, o XML de uma NFA
de produtor PF já sai estruturalmente correto. O trabalho desta feature é:
**marcar o documento como NFA, garantir que o emitente é PF com CPF válido, e
orquestrar esse fluxo num serviço dedicado, diferenciando NF-e de NFA no código
e no banco.**

## Objetivos (Definition of Done)

- NFA gerada para produtor PF sem erros (XML válido contra o XSD 4.00).
- Diferenciação clara NF-e vs NFA no código e no banco.
- `GeracaoNfaService` (`@Service`) dedicado.
- Diferenciação de fluxo PF vs PJ via discriminador na entidade.
- Validação específica de CPF de produtor rural via Bean Validation customizado.

## Decisões de design

1. **Discriminador:** enum `TipoDocumento { NFE, NFA }` na entidade `NotaFiscal`
   (coluna no banco). O fluxo PF vs PJ é derivado de `Produtor.tipoProdutor`
   (já existe `PESSOA_FISICA` / `PESSOA_JURIDICA`). NFA exige produtor PF.
2. **Reuso do pipeline XML:** `GeracaoNfaService` **compõe** o
   `GeracaoNotaFiscalService` existente — não duplica o pipeline de marshalling
   e validação XSD.
3. **Criação da NFA:** campo opcional `tipoDocumento` no `POST /api/notas`
   (default `NFE`). Sem endpoint novo.
4. **Momento da rejeição PJ:** na geração do XML (consistente com o
   `FiscalRulesValidator`, que valida pré-condições de emissão), não na criação
   do rascunho.

## Componentes

### 1. Modelo & Banco

- **`model/TipoDocumento.java`** — `enum { NFE, NFA }`.
- **`NotaFiscal`** — novo campo `tipoDocumento`:
  - `@Enumerated(EnumType.STRING)`, coluna `tipo_documento VARCHAR(3)`,
    `NOT NULL`, `@Builder.Default = TipoDocumento.NFE`.
- **Migration `0005-nota-fiscal-tipo-documento.xml`** (segue o padrão do 0004):
  - `addColumn` `tipo_documento VARCHAR(3)` com `defaultValue="NFE"` e
    constraint `nullable=false`. Default garante compatibilidade retroativa
    (notas existentes viram `NFE`).
  - `rollback` com `dropColumn`.
  - Incluir no `db.changelog-master.yaml`.

### 2. Validação de CPF (Bean Validation customizado)

- **`common/validation/CpfUtils.java`** — lógica pura `isValid(String cpf)`:
  remove não-dígitos; exige 11 dígitos; rejeita sequências repetidas
  (`00000000000`, `11111111111`, …); valida os 2 dígitos verificadores
  (módulo 11). Sem dependência de Spring/Jakarta — testável isoladamente.
- **`common/validation/Cpf.java`** — anotação de constraint
  (`@Constraint(validatedBy = CpfValidator.class)`), aplicável a `String`,
  com `message` default.
- **`common/validation/CpfValidator.java`** —
  `ConstraintValidator<Cpf, String>` que delega a `CpfUtils.isValid`.
  `null` é considerado válido (deixa a obrigatoriedade para `@NotBlank`).

A anotação fica disponível para DTOs (ex.: futuro cadastro de produtor). No
fluxo NFA, o CPF a validar é o do **produtor autenticado** (vem da auth mockada,
não do request), então o `GeracaoNfaService` chama `CpfUtils.isValid`
diretamente sobre a entidade — mesma regra, sem duplicação.

> Adianta parcialmente um débito técnico da Sprint 8 (validação de DV de
> CPF/CNPJ), restrito ao CPF do produtor rural na NFA.

### 3. `service/GeracaoNfaService.java` (`@Service`)

Orquestra a geração da NFA, compondo `GeracaoNotaFiscalService`:

```
public String gerarXml(NotaFiscal nota):
  1. valida produtor != null e tipoProdutor == PESSOA_FISICA
       senão → NfaEmitenteInvalidoException (422)
  2. valida CpfUtils.isValid(produtor.cpfCnpj)
       senão → NfaEmitenteInvalidoException (422)
  3. return geracaoNotaFiscalService.gerarXml(nota)
```

- **`exception/NfaEmitenteInvalidoException.java`** — exceção de domínio,
  mapeada para **422** no `GlobalExceptionHandler` (mesmo status das demais
  falhas de pré-emissão, ex.: `NotaFiscalInvalidaParaEmissaoException`).
  Mensagem ao cliente genérica; detalhe técnico só no log.

### 4. Diferenciação no documento gerado

- **`NotaFiscalNfeMapper.buildInfAdic`** passa a receber a `NotaFiscal` e ler
  `tipoDocumento`: para `NFA`, o `infCpl` inclui
  `"NOTA FISCAL AVULSA - PRODUTOR RURAL PESSOA FISICA"` (concatenado ao texto
  de homologação já existente). Diferença visível no XML, dentro do XSD.

### 5. Roteamento & API

- **`NotaFiscalService.gerarXml(UUID)`** roteia por `tipoDocumento`:
  `NFA → geracaoNfaService.gerarXml(nota)`; caso contrário → fluxo NFE atual.
  Persistência de status/chave/XML/data permanece idêntica.
- **`NotaFiscalRequest`** — novo campo opcional `tipoDocumento`
  (`TipoDocumento`, nullable; default `NFE` aplicado no mapper). Sem Bean
  Validation extra (enum já restringe valores).
- **`NotaFiscalMapper.toEntity`** — seta `tipoDocumento` a partir do request
  (ou `NFE` se null).
- **`NotaFiscalResponse`** + `NotaFiscalMapper.toResponse` — expõem
  `tipoDocumento`.
- **OpenAPI** — descrições atualizadas nos DTOs e no endpoint de criação.

## Fluxo de dados

```
POST /api/notas {tipoDocumento: NFA, ...}
  → NotaFiscalService.create → toEntity (tipoDocumento=NFA) → save (RASCUNHO)
POST /api/notas/{id}/gerar-xml
  → NotaFiscalService.gerarXml
      → tipoDocumento==NFA?
          → GeracaoNfaService.gerarXml
              → valida PF + CPF (422 se inválido)
              → GeracaoNotaFiscalService.gerarXml (pipeline NF-e reusado)
      → persiste xmlGerado, chaveAcesso, status=XML_GERADO
```

## Tratamento de erros

- Produtor PJ em nota NFA, ou CPF inválido → `NfaEmitenteInvalidoException`
  → **422** com `ApiError` (mensagem genérica ao cliente).
- Demais erros (XSD, dados ausentes) → comportamento atual inalterado.
- Ownership/anti-IDOR (404) e bloqueio de status (409) herdados do fluxo atual.

## Testes

- **`CpfUtilsTest`** — CPFs válidos, inválidos (DV errado), repetidos, formato
  inválido, null/blank.
- **`CpfValidatorTest`** — integração da constraint (válido, inválido, null).
- **`GeracaoNfaServiceTest`** (Mockito) — PF+CPF válido → delega e retorna XML;
  PJ → 422; CPF inválido → 422; produtor null → 422. Strict stubs: não stubar
  `geracaoNotaFiscalService` nos casos de early return.
- **Integração** — criar NFA para produtor PF seed → `gerar-xml` →
  status `XML_GERADO`, XML contém `<CPF>` no `emit` e o marcador NFA no
  `infCpl`; criar NFA com produtor PJ → 422.

## Fora de escopo

- Transmissão à SEFAZ, assinatura digital ICP-Brasil (Sprint 7).
- Endereço/IE completos do produtor (débito técnico já registrado).
- Validação de DV de CNPJ (Sprint 8).
- Frontend.

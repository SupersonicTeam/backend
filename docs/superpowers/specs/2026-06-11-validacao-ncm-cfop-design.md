# Design — Validação de NCM e CFOP nas notas fiscais

- **Data:** 2026-06-11
- **Sprint:** 2 (issue #20 — SupersonicTeam/backend, RN008)
- **Autor:** Supersonic Team
- **Status:** aprovado para planejamento

## Contexto

Os itens de nota fiscal (`NotaFiscalItem` / `NotaFiscalItemRequest`) já validam o
**formato** de NCM (8 dígitos) e CFOP (4 dígitos) via `@Pattern`. Não há, porém,
validação **semântica**: qualquer sequência de 8/4 dígitos é aceita, mesmo que o
NCM não exista ou o CFOP seja incompatível com a operação.

Esta feature adiciona validação semântica em duas camadas, ambas resultando em
**HTTP 400**:

1. **Existência** — o NCM deve estar no catálogo de NCMs agropecuários válidos; o
   CFOP deve estar na lista de CFOPs aplicáveis ao produtor rural. Implementada
   como Bean Validation customizado (`@ValidNcm` / `@ValidCfop`) nos campos do DTO,
   executada no `@Valid` de `POST`/`PUT /api/notas`.
2. **Compatibilidade CFOP × operação** — o CFOP deve ser coerente com o tipo de
   operação (entrada/saída) e o âmbito (interna/interestadual). Como exige a UF do
   emitente (produtor autenticado, ausente do request) e a do destinatário, é uma
   **regra de negócio** na camada de serviço, lançando exceção descritiva.

## Objetivos (Definition of Done)

- Nota com NCM inválido retorna **400** com mensagem descritiva.
- Nota com CFOP incompatível retorna **400**.

## Decisões de design

1. **Momento da validação:** na **criação e atualização** (`POST`/`PUT /api/notas`).
   `@ValidNcm`/`@ValidCfop` no `@Valid` do request; a regra CFOP×operação no
   `create`/`update` do serviço. Falha cedo, antes de persistir rascunho inválido.
   O `FiscalRulesValidator` (emissão) permanece inalterado.
2. **Metadados do CFOP em colunas** da tabela (`tipo_operacao`, `ambito`), não
   derivados do primeiro dígito em código. Data-driven, explícito, extensível.
3. **CFOP incompatível → 400** (atende o DoD literalmente). A nova exceção de
   negócio é mapeada para 400 no `GlobalExceptionHandler` — diverge
   conscientemente da convenção das regras de emissão fiscal (que usam 422),
   porque aqui a validação ocorre no request de criação, não na emissão.
4. **Seed curado representativo** (~15-25 NCMs agro e os CFOPs típicos do produtor
   rural), versionado no Liquibase — disponível em todos os profiles, inclusive
   testes (H2).

## Componentes

### 1. Banco — migration Liquibase `0006-ncm-cfop-referencia.xml`

Duas tabelas de referência com dados versionados (changesets de `<createTable>` +
`<insert>`; nunca editar changesets 0001-0005):

- **`ncm_agropecuario`**
  - `codigo VARCHAR(8)` — PK
  - `descricao VARCHAR(255) NOT NULL`
  - Seed (~15-25): soja `12010090`, milho `10059010`, café `09011110`,
    bovino vivo `01022190`, leite `04012010`, algodão `52010010`, arroz `10063021`,
    trigo `10019900`, cana `12129200`, etc. (lista final curada no plano).

- **`cfop_produtor_rural`**
  - `codigo VARCHAR(4)` — PK
  - `descricao VARCHAR(255) NOT NULL`
  - `tipo_operacao VARCHAR(7) NOT NULL` — `ENTRADA` | `SAIDA`
  - `ambito VARCHAR(15) NOT NULL` — `INTERNA` | `INTERESTADUAL`
  - Seed: 5101/6101 (venda de produção do estabelecimento), 5102/6102 (venda de
    mercadoria adquirida de terceiros), 5949/6949 (outra saída não especificada),
    1101/2101 (compra para industrialização/produção), etc. (5xxx=INTERNA/SAIDA,
    6xxx=INTERESTADUAL/SAIDA, 1xxx=INTERNA/ENTRADA, 2xxx=INTERESTADUAL/ENTRADA).

Incluir o `0006` no `db.changelog-master.yaml`.

### 2. Entidades + repositórios

- **`model/NcmAgropecuario`** — `@Entity @Table("ncm_agropecuario")`, `@Id codigo`,
  `descricao`. Tabela de referência (sem auditoria/timestamps).
- **`model/CfopProdutorRural`** — `@Entity`, `@Id codigo`, `descricao`,
  `@Enumerated(STRING) tipoOperacao`, `@Enumerated(STRING) ambito`.
- **`model/TipoOperacao`** — `enum { ENTRADA, SAIDA }`.
- **`model/AmbitoOperacao`** — `enum { INTERNA, INTERESTADUAL }`.
- **`repository/NcmAgropecuarioRepository extends JpaRepository<NcmAgropecuario,String>`**
  — `boolean existsByCodigo(String codigo)`.
- **`repository/CfopProdutorRuralRepository extends JpaRepository<CfopProdutorRural,String>`**
  — `boolean existsByCodigo(String codigo)`; `Optional<CfopProdutorRural> findByCodigo(String codigo)`.

### 3. Bean Validation customizado (→ 400)

- **`common/validation/ValidNcm`** — anotação `@Constraint(validatedBy = NcmValidator.class)`,
  `@Target(FIELD)`, `@Retention(RUNTIME)`, `message() default "NCM nao cadastrado para agropecuaria"`.
- **`common/validation/NcmValidator implements ConstraintValidator<ValidNcm,String>`**
  — Spring bean injetando `NcmAgropecuarioRepository`. `null`/blank → `true` (defere
  ao `@NotBlank`); senão `repository.existsByCodigo(value)`.
- **`common/validation/ValidCfop`** — anotação análoga, `message() default
  "CFOP nao aplicavel ao produtor rural"`.
- **`common/validation/CfopValidator implements ConstraintValidator<ValidCfop,String>`**
  — injeta `CfopProdutorRuralRepository`; `existsByCodigo`.

Aplicados em `NotaFiscalItemRequest.ncm` (junto ao `@Pattern` existente) e `.cfop`.
Como os validators são beans Spring, o `LocalValidatorFactoryBean` do Spring Boot
os instancia com injeção de dependência. A violação no `@Valid` gera
`MethodArgumentNotValidException`, já mapeada para **400** com `ApiError.fields`.

### 4. Regra de negócio CFOP × operação (→ 400)

- **`service/CfopOperacaoValidator`** (`@Component`) — injeta
  `CfopProdutorRuralRepository`. Método `validar(NotaFiscal nota)`:
  - Operação = sempre **SAIDA** (o modelo atual emite `tpNF=1`).
  - Âmbito esperado: `INTERNA` se `produtor.estado == destinatarioEndereco.uf`,
    senão `INTERESTADUAL`.
  - Para cada item: `findByCodigo(item.cfop)` (presença garantida pelo `@ValidCfop`
    no request; se ausente por robustez, ignora — o Bean Validation já barrou).
    Se `cfop.tipoOperacao != SAIDA` **ou** (âmbito determinável e
    `cfop.ambito != esperado`) → `CfopIncompativelException` descritiva.
  - **Edge case:** UF do destinatário ou do produtor ausente (endereço é opcional
    em RASCUNHO) → âmbito indeterminável → pula a checagem de âmbito, mas mantém a
    de `tipoOperacao == SAIDA`.
- Invocado em `NotaFiscalService.create` e `update`, após `NotaFiscalMapper.toEntity`
  (produtor e itens disponíveis), antes de `save`.

### 5. Exceção + handler

- **`exception/CfopIncompativelException extends RuntimeException`** — construtor
  com mensagem descritiva (ex.: `"Item 2: CFOP 5101 (operacao interna) incompativel
  com venda interestadual (PR -> SP). Use um CFOP 6xxx."`).
- Handler `@ExceptionHandler(CfopIncompativelException.class)` no
  `GlobalExceptionHandler` → **400 Bad Request** com `ApiError` (mensagem ao
  cliente é a própria mensagem descritiva — não contém dado sensível). Posicionado
  junto aos demais handlers de regra de negócio.

## Fluxo de dados

```
POST/PUT /api/notas {itens:[{ncm, cfop, ...}]}
  → @Valid: @ValidNcm/@ValidCfop por item
      → NCM/CFOP inexistente → MethodArgumentNotValidException → 400
  → NotaFiscalService.create/update
      → NotaFiscalMapper.toEntity
      → CfopOperacaoValidator.validar(nota)
          → CFOP de entrada em saída, ou âmbito divergente
              → CfopIncompativelException → 400
      → save
```

## Tratamento de erros

- NCM/CFOP inexistente → **400** via Bean Validation (`ApiError.fields` com campo e
  motivo).
- CFOP incompatível com a operação → **400** via `CfopIncompativelException`
  (mensagem descritiva única).
- Ownership/anti-IDOR e bloqueio de status herdados do fluxo de create/update atual.

## Testes

- **`NcmAgropecuarioRepositoryTest` / `CfopProdutorRuralRepositoryTest`**
  (`@DataJpaTest`, H2): seed carregado; `existsByCodigo` true/false.
- **`NcmValidatorTest` / `CfopValidatorTest`** (unit, mock repo): existe → válido;
  inexistente → inválido; null/blank → válido.
- **`CfopOperacaoValidatorTest`** (unit, mock repo): saída interna OK; CFOP interno
  (5xxx) em venda interestadual → exceção; CFOP de entrada (1xxx) em saída →
  exceção; UF do destinatário ausente → pula âmbito (não lança).
- **Integração**: item com NCM fora do catálogo → violação de Bean Validation
  (jakarta `Validator` sobre o `NotaFiscalItemRequest`, asserta violação); CFOP
  incompatível → `CfopIncompativelException` no `NotaFiscalService.create`.

## Fora de escopo

- Notas de entrada (o modelo atual só emite saída) — a dimensão `tipo_operacao`
  fica modelada para uso futuro, validando que CFOPs de entrada sejam rejeitados em
  saídas.
- Tabela oficial completa de NCM/CFOP (seed curado é suficiente para o TCC).
- Validação de NCM por compatibilidade com o produto/descrição.
- Frontend.

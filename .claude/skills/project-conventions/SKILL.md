---
name: project-conventions
description: >
  Padrões inegociáveis do AgroContabil Backend: camadas, fluxo de nova feature,
  contrato de erro, DTO/mapper, validação, testes, migrations e git.
  Use ANTES de escrever ou revisar qualquer código, para seguir o projeto sem reler o CLAUDE.md inteiro.
---

# Convenções do AgroContabil Backend

Regras que todo código novo deve seguir. Se um trecho novo diverge daqui, está errado — ajuste o código, não a regra.

## Arquitetura em camadas (direção única)

`Controller → Service → Repository`. Nunca pular nem inverter.

- **Controller** só orquestra: recebe DTO, chama service, devolve resposta. **Zero** regra de negócio.
- **Regra de negócio** vive **só** no `service`.
- **Repository** = Spring Data JPA. Queries de ownership sempre `findByIdAndProdutorId`.
- **Entidade JPA nunca sai da API.** Sempre converter para DTO via `mapper`.
- **Alíquota e redução de base têm um único resolvedor cada.** Nunca ler `TributosProperties.ibs/cbs` nem `AliquotaIbsUfRepository`/`NcmReferenciaRepository` direto num serviço de cálculo — sempre via `AliquotaIbsCbsResolver` (issue #86) e `ReducaoNcmResolver` (issue #52). Débito e crédito precisam sair do mesmo ponto para a apuração ser simétrica.

## Fluxo para adicionar um endpoint (checklist)

1. **DTO** `record` em `dto/` — `XxxRequest` (entrada) e `XxxResponse` (saída). Validação Bean Validation nas anotações do request.
2. **Mapper** em `mapper/` — entidade ↔ DTO. Nunca expor entidade.
3. **Service** em `service/` — regra de negócio + transações. Lança exceção de domínio (`extends DomainException`) em erro.
4. **Controller** — `@Valid` no request, chama service, retorna `ResponseEntity`/DTO. Sem try/catch (o `GlobalExceptionHandler` cuida).
5. **Migration** (se mudar schema) — novo changeSet em `db/changelog/changes/`. Nunca editar um já aplicado.
6. **Testes** — unitário de service (Mockito) + integração do fluxo crítico.
7. Documentar o endpoint na tabela do CLAUDE.md e no `codebase-map` se criar classe significativa.

## Validação

- **Sempre** Bean Validation (`@Valid` + anotações no DTO). **Nunca** validar manualmente o que a anotação resolve.
- Regra fiscal/de-negócio (não expressável por anotação) → no service.

## Contrato de erro (obrigatório)

- Todo erro sai pelo `GlobalExceptionHandler` (`@RestControllerAdvice`) → `ApiError` com envelope **`errors[]`**.
- Cada erro é um `ErrorDetail`: `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`.
- Exceções de domínio estendem `DomainException` (carrega `HttpStatus` + lista de `ErrorDetail`). Código em `ErrorCode`.
- **Nunca** tratar exceção no controller. **Nunca** vazar stack trace / detalhe técnico ao cliente. 5xx = mensagem genérica; detalhe só no log.

## Segurança (já aplicada, manter)

- **Anti-IDOR:** toda operação sobre nota usa `findByIdAndProdutorId` e responde **404** (não 403) para nota de outro produtor — não revela existência.
- Produtor "autenticado" vem do `MockProdutorFilter` via `AuthenticatedProdutorProvider` (auth real só na Sprint 8).
- **Nenhum segredo no repo** — env vars (`${DB_PASSWORD}`, `${JWT_SECRET}`, `NFE_CERT_*`). CORS por env, nunca `*`.
- **Logs sem senha/token/credencial.**

## Banco / migrations

- Schema só via **Liquibase** (`db.changelog-master.yaml`). Hibernate em `ddl-auto: validate` — **proibido** `update`/`create`.
- Uma mudança de schema = um changeSet novo. Nunca editar changeSet aplicado.

## Testes

- Feature nova = teste unitário de service (Mockito) + teste de integração do fluxo crítico.
- Mockito **strict stubs**: não crie stub de objeto usado só após early return.
- Rodar `./mvnw test` (profile test, H2) antes de abrir PR. Manter todos verdes.

## Idioma do código (convenção híbrida)

- **Domínio fiscal → português:** `NotaFiscal`, `Produtor`, `gerarXml`, `sincronizarStatus`, `CalculoIbsCbsService`.
- **Infra/utilitário técnico genérico → inglês:** `ApiError`, `ErrorCode`, `XmlGenerator`, `XsdValidator`, `*Config`, `GlobalExceptionHandler`.
- **API do Java/Spring → inglês:** getters/setters, nomes exigidos por framework.
- **Regra prática:** escreva como o código ao redor. Domínio fiscal na dúvida → português; utilitário genérico → inglês.
- **Comunicação (PR, commit, conversa) → português.**

## Git / PRs

- **Nunca** commitar direto em `main`/`dev`/`prod`. Usar `feature/`, `fix/`, `chore/` + **worktree isolado**.
  - Armadilha: ao usar worktree, Read/Edit/Write e `mvnw` devem apontar para o path do worktree, não a raiz.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:`).
- **PRs pequenos e deployáveis:** uma responsabilidade lógica por PR.
- **Datas:** `YYYY-MM-DD`, timezone `America/Sao_Paulo`.

## Estado da nota fiscal (referência rápida)

`RASCUNHO → XML_GERADO → AUTORIZADA / REJEITADA / PENDENTE_SEFAZ / CANCELADA`; `COM_INCONSISTENCIAS`.
- Editável (update/delete/gerar-xml): apenas `RASCUNHO` e `COM_INCONSISTENCIAS`.
- Fora disso → **409** (`NotaFiscalNaoEditavelException`).

## Bibliotecas externas

Ao mexer com Spring, JAXB, JasperReports, Apache Santuario, Liquibase etc., busque doc atualizada via **Context7 MCP** antes de codar (evita API desatualizada).

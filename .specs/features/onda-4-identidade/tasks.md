# Tasks — Onda 4: Identidade multi-usuário sem JWT

Gate de toda task: `./mvnw test` verde (478 + novos). Commit atômico por task, Conventional Commits.
Ordem dos PRs: **#91 → #94 → #92** (a #94 só é testável de verdade com dois usuários).

---

## PR 1 — `feat(identidade): resolve usuario por requisicao via header X-Usuario-Id` (#91)

### T1 — Seed de dois produtores no `DataLoader`
- **Onde:** `config/DataLoader.java`
- **O quê:** extrair `criarProdutor(...)`; semear A (`123.456.789-00`, fallback) e B (`987.654.321-00`,
  "Maria Agricultora"); idempotente por CPF; log INFO com os UUIDs semeados. B leva
  `inscricaoEstadual` e `EnderecoProdutor` próprios (município/IE diferentes dos de A) — desde a
  issue #88 endereço e IE são pré-condição da emissão, e valores distintos fazem uma troca de
  produtor aparecer no XML.
- **Pronto quando:** subir em dev duas vezes não duplica produtor; log mostra os dois ids.
- **Testes:** cobertura indireta pelo teste de integração de T4; sem teste unitário dedicado.

### T2 — `MockProdutorFilter` resolve por requisição
- **Onde:** `config/MockProdutorFilter.java`
- **O quê:** constante pública `HEADER_USUARIO_ID`; remover `cachedProdutor`; resolver header →
  `UUID.fromString` → `findById`; ausente → `findByCpfCnpj(CPF_SEMENTE)`; não resolvido → **deixa o
  contexto vazio** (o 401 nasce no provider, T8); `clearContext()` em `finally` **apenas** quando foi
  este filtro que autenticou; preservar contexto pré-existente. Javadoc: header **declara**, não prova;
  nunca em prod.
- **Depende de:** T1 (para o cenário multiusuário fazer sentido)
- **Pronto quando:** RF01–RF06 atendidos.
- **Testes:** `config/MockProdutorFilterTest` (novo, Mockito puro + `MockHttpServletRequest`):
  semente sem header · uuid válido → produtor certo · uuid malformado → contexto vazio + cadeia segue ·
  id inexistente → contexto vazio · contexto pré-existente intocado · contexto limpo após a cadeia.

### T3 — Javadoc do `SecurityConfig`
- **Onde:** `config/SecurityConfig.java`
- **O quê:** documentar `{dev, homolog}`, o contrato do header e o fato de que em prod a ausência do
  filtro implica 401 (não 500). Sem mudança funcional.
- **Testes:** nenhum.

### T4 — Teste de integração multi-usuário
- **Onde:** `src/test/java/br/com/agrocontabil/config/IdentidadeMultiUsuarioIntegrationTest.java` (novo)
- **O quê:** `@SpringBootTest` + `MockMvc` com o filtro registrado via `@TestConfiguration`; produtores
  A e B criados no setup; notas de A e de B.
- **Cobre critérios de aceite:** 1, 2, 6, 10 e 11. Os critérios **7 e 8 (401)** só são exigíveis a
  partir do PR 3: neste PR, header inválido resulta em contexto vazio e o provider ainda lança
  `IllegalStateException` → 500. Aqui o teste apenas asserta que **não autentica como A**; o assert de
  401 entra em T11.
- **Depende de:** T2

### T5 — CORS travado para o header
- **Onde:** `src/test/java/br/com/agrocontabil/config/CorsIntegrationTest.java`
- **O quê:** preflight com `Access-Control-Request-Headers: X-Usuario-Id` → permitido. `CorsConfig` já
  usa `allowedHeaders("*")`; o teste impede regressão silenciosa se a lista for apertada.
- **Testes:** o próprio.

---

## PR 2 — `fix(guias): exige produtor autenticado no produtorId de /api/contador/guias` (#94)

### T6 — Guard de ownership em `criar` e `listar`
- **Onde:** `service/GuiaFiscalService.java`
- **O quê:** `exigirProdutorAutenticado(UUID)` privado → `ResourceNotFoundException("Produtor", id)`
  quando divergir do `produtorProvider.get().getId()`; aplicar em `criar` (antes do `findById`, para
  não gravar nada) e em `listar`. Javadoc da classe e dos métodos reescrito.
- **Pronto quando:** RF07–RF09 atendidos.
- **Testes:** `service/GuiaFiscalServiceTest` (ajuste): `criar` com produtorId de outro → 404 e
  `guiaFiscalRepository` nunca chamado; `listar` idem. Atenção a **strict stubs**: stubs de
  repositório usados só após o guard devem sair dos cenários de 404.

### T7 — OpenAPI e teste de controller
- **Onde:** `controller/GuiaFiscalController.java`, `controller/GuiaFiscalControllerTest.java`
- **O quê:** `@ApiResponse` 404 no `GET` (hoje só documenta 200) e descrição do `POST`/`GET` citando o
  vínculo com o produtor autenticado; teste do 404 no controller.
- **Depende de:** T6

### T8 — Integração cross-tenant das guias
- **Onde:** `service/GuiaFiscalIntegrationTest.java` (ajuste) ou o teste novo de T4
- **O quê:** com header de B: `GET ...?produtorId=A` → 404; `POST` com `produtorId` de A → 404 e
  contagem de guias de A inalterada; `PATCH .../pagar` de guia de A → 404 (regressão).
- **Cobre critérios de aceite:** 3, 4, 5.
- **Depende de:** T2, T6

---

## PR 3 — `fix(identidade): responde 401 sem usuario autenticado e habilita profile homolog` (#92)

### T9 — `ErrorCode.NAO_AUTENTICADO` + `NaoAutenticadoException`
- **Onde:** `common/ErrorCode.java`, `exception/NaoAutenticadoException.java` (novo)
- **O quê:** código novo em seção própria do catálogo; exceção `extends DomainException`
  (`HttpStatus.UNAUTHORIZED`) com `ErrorDetail` via construtor canônico (com `suggestion`).
  **Sem** `@ExceptionHandler` novo — `DomainException` já é tratado.
- **Testes:** cobertura pelo T10/T11.

### T10 — Provider lança 401
- **Onde:** `service/AuthenticatedProdutorProvider.java`
- **O quê:** `IllegalStateException` → `NaoAutenticadoException`; Javadoc e `@throws` atualizados.
- **Testes:** `service/AuthenticatedProdutorProviderTest` (ajuste) — contexto vazio, principal de outro
  tipo e autenticação nula → `NaoAutenticadoException`.
- **Depende de:** T9

### T11 — 401 ponta a ponta sem filtro (simula prod)
- **Onde:** `IdentidadeMultiUsuarioIntegrationTest` (ou classe irmã)
- **O quê:** (a) `MockMvc` **sem** o filtro registrado → endpoint autenticado responde **401** com
  `errors[0].code == "NAO_AUTENTICADO"`, nunca 500; (b) com o filtro, header malformado e header com
  UUID inexistente → o mesmo 401.
- **Cobre critérios de aceite:** 7, 8 e 9.
- **Depende de:** T10

### T12 — `@Profile({"dev","homolog"})` + `application-homolog.yml`
- **Onde:** `config/MockProdutorFilter.java`, `config/DataLoader.java`, `resources/application-homolog.yml` (novo)
- **O quê:** ampliar os profiles; yml espelhando `prod` **como a Onda 1 (#98) o deixou** — pool e
  timeouts herdados do `application.yml` base e, no profile, só o
  `hikari.data-source-properties.options: -c statement_timeout=${DB_STATEMENT_TIMEOUT_MS:30000}`
  (específico do PostgreSQL; o H2 dos testes rejeitaria a propriedade). Mais env vars de banco,
  `docker.compose.enabled: false`, `APP_CORS_ALLOWED_ORIGINS` obrigatória, logs DEBUG do pacote da
  app. Nenhum segredo literal.
- **Pronto quando:** RF12–RF13 atendidos; `./mvnw test` verde.

### T13 — Documentação
- **Onde:** `CLAUDE.md`, `.claude/skills/codebase-map/SKILL.md`, `.claude/skills/project-conventions/SKILL.md`
- **O quê:**
  - Seção "Segurança": contrato do header, profiles, fallback, 401.
  - Débito técnico da Sprint 8: remover "IllegalStateException→401" (entregue) e reescrever a nota das
    guias — de "aceitam produtorId explícito sem checagem de ownership" para "exigem que o produtorId
    bata com o produtor autenticado; papel Contador de verdade fica na #93".
  - `codebase-map`: `NaoAutenticadoException`, novo `ErrorCode`, profile `homolog`, filtro por header.
  - Nota de integração para o frontend (seção 8 do `spec.md`) replicada no CLAUDE.md.
- **Depende de:** T1–T12

---

## Rastreabilidade

| Requisito | Tasks |
|-----------|-------|
| RF01–RF06 | T2, T4 |
| RF07–RF09 | T6, T7, T8 |
| RF10–RF11 | T9, T10, T11 |
| RF12–RF14 | T1, T12 |
| RNF01 | gate de toda task |
| RNF02 | T12 |
| RNF03 | T2 |
| RNF04 | T3, T11 |

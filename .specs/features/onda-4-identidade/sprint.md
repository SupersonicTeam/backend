# Sprint — Onda 4: Identidade multi-usuário sem JWT

- **Data do plano:** 2026-09-23
- **Duração:** 2 semanas (projeto acadêmico / TCC)
- **Branch base:** `claude/sleepy-wright-937f83` (worktree isolado)
- **Insumos:** `spec.md` (RF01–RF14, RNF01–RNF04, critérios 1–11, D1–D4), `design.md`, `tasks.md` (T1–T13)
- **Fora de escopo:** JWT, entidade `Contador`, vínculo Contador↔Produtor, RBAC, login/cadastro, rate limiting — tudo na issue #93 (Sprint 8, Onda 7).

---

## 1. Épico

> **Tornar o backend multi-usuário de verdade**, para que o anti-IDOR já espalhado pela API passe a ser
> exercível ponta a ponta e a aplicação responda com honestidade (401, nunca 500) fora do profile `dev`
> — com uma camada de identidade plugável que a Sprint 8 troca por JWT sem tocar em controllers nem services.

| Issue | Título curto | PR |
|-------|--------------|----|
| **#91** | `MockProdutorFilter` resolve usuário por requisição via `X-Usuario-Id` | PR 1 |
| **#94** | IDOR: `/api/contador/guias` aceita `produtorId` arbitrário | PR 2 |
| **#92** | Fora do profile `dev` a API responde 500 em vez de 401; falta seed | PR 3 |

---

## 2. Histórias de usuário

### H1 — Identidade por requisição (#91) · PR 1
> **Como** desenvolvedor do AgroContabil, **quero** que o backend resolva qual produtor está autenticado
> a cada requisição a partir do header `X-Usuario-Id`, **para** poder exercitar cenários com mais de um
> usuário sem depender de autenticação real.

**Commit/PR:** `feat(identidade): resolve usuario por requisicao via header X-Usuario-Id`

| Task | Escopo | Onde |
|------|--------|------|
| T1 | Seed de dois produtores (A fallback, B com IE + endereço próprios) | `config/DataLoader.java` |
| T2 | Filtro resolve por requisição; sem cache; `clearContext()` no `finally`; contexto pré-existente preservado | `config/MockProdutorFilter.java` |
| T3 | Javadoc do `SecurityConfig` (`{dev, homolog}`, contrato do header, 401 em prod) | `config/SecurityConfig.java` |
| T4 | Teste de integração multi-usuário (`@SpringBootTest` + `MockMvc`, filtro via `@TestConfiguration`) | `IdentidadeMultiUsuarioIntegrationTest` (novo) |
| T5 | Preflight CORS aceita `X-Usuario-Id`, travado por teste | `CorsIntegrationTest` |

**Requisitos:** RF01–RF06, RF14 (seed), RNF03.
**Critérios de aceite cobertos:** **1, 2, 6, 10** (+ 11 como gate).
**Nota:** os critérios **7 e 8** (401 com header inválido) **não** valem aqui — neste PR o provider ainda
lança `IllegalStateException` (→ 500). T4 asserta apenas que **não autentica como A**; o assert de 401 é do PR 3.

---

### H2 — Ownership nas guias fiscais (#94) · PR 2
> **Como** produtor rural, **quero** que `/api/contador/guias` só aceite operações sobre o meu próprio
> `produtorId`, **para** que ninguém leia ou crie guias fiscais em meu nome informando um id arbitrário.

**Commit/PR:** `fix(guias): exige produtor autenticado no produtorId de /api/contador/guias`

| Task | Escopo | Onde |
|------|--------|------|
| T6 | `exigirProdutorAutenticado(UUID)` → `ResourceNotFoundException` (404); aplicado em `criar` (antes do `findById`) e `listar`; Javadoc reescrito | `service/GuiaFiscalService.java` |
| T7 | `@ApiResponse` 404 no `GET`/descrição do `POST` + teste de controller | `GuiaFiscalController` / `GuiaFiscalControllerTest` |
| T8 | Integração cross-tenant com header de B: `GET`, `POST` (nada gravado) e `PATCH .../pagar` → 404 | `GuiaFiscalIntegrationTest` / teste de T4 |

**Requisitos:** RF07–RF09.
**Critérios de aceite cobertos:** **3, 4, 5** (+ 11 como gate).

---

### H3 — 401 honesto fora de dev + profile homolog (#92) · PR 3
> **Como** operador da API, **quero** que uma requisição sem identidade válida receba **401
> `NAO_AUTENTICADO`** com o envelope de erro padrão (e que exista um profile `homolog` com dois
> produtores semeados), **para** que a ausência de autenticação seja um erro de cliente tratável e não
> um 500 genérico.

**Commit/PR:** `fix(identidade): responde 401 sem usuario autenticado e habilita profile homolog`

| Task | Escopo | Onde |
|------|--------|------|
| T9 | `ErrorCode.NAO_AUTENTICADO` + `NaoAutenticadoException extends DomainException` (401), sem handler novo | `common/ErrorCode.java`, `exception/NaoAutenticadoException.java` (novo) |
| T10 | Provider troca `IllegalStateException` por `NaoAutenticadoException`; Javadoc/`@throws` | `service/AuthenticatedProdutorProvider.java` |
| T11 | 401 ponta a ponta: sem filtro (simula prod) e com filtro + header malformado/UUID inexistente | `IdentidadeMultiUsuarioIntegrationTest` (ou irmã) |
| T12 | `@Profile({"dev","homolog"})` no filtro e no `DataLoader` + `application-homolog.yml` (env vars, sem segredo) | `config/`, `resources/application-homolog.yml` (novo) |
| T13 | Docs: `CLAUDE.md` (Segurança, débito técnico da Sprint 8, nota de integração frontend), `codebase-map`, `project-conventions` | `.claude/skills/`, `CLAUDE.md` |

**Requisitos:** RF10–RF13, RNF02, RNF04.
**Critérios de aceite cobertos:** **7, 8, 9** (só a partir daqui — o 401 nasce no provider, entregue em T10) + **11**.

---

### Mapa de cobertura dos critérios de aceite

| Critério | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|----------|---|---|---|---|---|---|---|---|---|----|----|
| História | H1 | H1 | H2 | H2 | H2 | H1 | **H3** | **H3** | **H3** | H1 | gate de todas |

---

## 3. Estimativa

| História | Tasks | SP | Justificativa (1 linha) |
|----------|-------|----|--------------------------|
| **H1** — Identidade por requisição | T1–T5 | **8** | Reescrita do filtro com armadilha de thread-local (reuso de thread do Tomcat) + novo teste de integração `@SpringBootTest` com filtro registrado à mão, o item de maior incerteza da sprint. |
| **H2** — Ownership nas guias | T6–T8 | **3** | Guard de 4 linhas replicando padrão já existente no `CalendarioFiscalService`; custo real está em ajustar stubs estritos do Mockito e escrever os testes cross-tenant. |
| **H3** — 401 + homolog | T9–T13 | **5** | Exceção e `ErrorCode` são mecânicos (sem handler novo), mas somam yml de profile novo, atualização do teste do provider e varredura de documentação em 3 arquivos. |
| **Total** | T1–T13 | **16** | — |

---

## 4. Definition of Done da sprint

Vale para **cada** PR (checklist global do projeto + específicos desta onda):

- [ ] **1 PR = 1 responsabilidade lógica**, deployável sozinho; Conventional Commits; branch `feature/`/`fix:` em worktree isolado (nunca commit em `main`/`dev`/`prod`).
- [ ] **Code review** aprovado (GitHub Copilot + revisão humana) e CI verde.
- [ ] **Testes:** unitários de service/filtro (Mockito, strict stubs respeitados) + integração do fluxo crítico; `./mvnw test` verde.
- [ ] **RNF01 — os 393 testes atuais continuam verdes**, zero regressão; os novos testes somam a esse total (critério de aceite 11).
- [ ] **RNF02 — nenhum segredo novo no repositório**: `application-homolog.yml` lê 100% de env var (banco, `APP_CORS_ALLOWED_ORIGINS`), nenhum literal.
- [ ] **Segurança / OWASP:** anti-IDOR responde **404** (nunca 403/200) para recurso de outro produtor; 401 não reflete o valor bruto do header em `rejectedValue`; valor do header não logado em INFO (RNF03); `prod` continua sem mecanismo declarativo de identidade (RNF04).
- [ ] **Arquitetura:** `Controller → Service → Repository` preservada; entidade JPA não exposta; erro só pelo `GlobalExceptionHandler` com envelope `errors[]`.
- [ ] **Docs atualizadas:** `CLAUDE.md` (Segurança, débitos técnicos da Sprint 8, nota de integração para o Angular), skills `codebase-map` e `project-conventions` — fechado em T13, no PR 3.
- [ ] **Critérios de aceite** da história correspondente demonstrados por teste automatizado (não por inspeção manual).
- [ ] Nenhum item de escopo da issue **#93** antecipado (JWT, `Contador`, RBAC).

---

## 5. Ordem de execução e dependências

```
  H1 (#91) ──────────────▶ H2 (#94) ──────────────▶ H3 (#92)
  PR 1                     PR 2                      PR 3
  T1 → T2 → {T3, T4, T5}   T6 → T7                   T9 → T10 → T11
                           T6 + T2 → T8              T12 ; T13 (último, depende de T1–T12)
```

| Aresta | Natureza | Por quê |
|--------|----------|---------|
| T1 → T2 | Dados | Sem o produtor B semeado, o cenário multi-usuário do filtro não existe. |
| T2 → T4/T5 | Implementação | Os testes de integração e de preflight precisam do filtro já resolvendo por header. |
| **H1 → H2** | **Testabilidade** | O guard da #94 é escrito em 4 linhas sem a #91, mas **não é demonstrável**: com um único produtor autenticado possível, `produtorProvider.get().getId()` sempre bate com qualquer `produtorId` legítimo, e o cenário "header de B pedindo guia de A" (critérios 3, 4, 5) é impossível de montar. A #91 é o que transforma o guard em código provado por teste em vez de código de fé. |
| T6 + T2 → T8 | Testabilidade | T8 combina o guard (T6) com a identidade por header (T2). |
| **H2 → H3** | Sequencial/escopo | Ordem obrigatória das issues; H3 fecha o pacote "fora de dev" e só então os critérios 7–9 passam a valer. |
| T10 → T11 | Implementação | O 401 assertado em T11 nasce no provider (T10), que por sua vez depende da exceção de T9. |
| T1–T12 → T13 | Documentação | Docs por último, para descrever o que de fato entrou. |

**Paralelizável:** T3 e T5 (independentes entre si após T2); T12 pode andar em paralelo a T9–T11 dentro do PR 3.

---

## 6. Riscos da sprint

### Riscos técnicos (herdados do `spec.md`, seção 7)

| Risco | Mitigação |
|-------|-----------|
| Vazamento de identidade entre requisições pelo reuso de thread do Tomcat | RF06 — `clearContext()` em `finally`; teste de duas requisições seguidas com headers diferentes (critério 10). |
| Frontend Angular quebrar | D2 — fallback para o produtor semente preserva o comportamento atual; o header é opt-in (critério 6). |
| Header ser confundido com autenticação real | Javadoc explícito no filtro (T2) + nota no `CLAUDE.md` (T13): **declara, não prova**; nunca em `prod`. |
| Teste de integração existente que injeta principal na mão parar de funcionar | RF05 — o filtro só age com contexto vazio e só limpa o contexto que ele mesmo montou. |
| `homolog` subir sem `APP_CORS_ALLOWED_ORIGINS` | Env obrigatória no yml, mesmo padrão de `prod` (T12). |

### Riscos de processo (acrescentados no planejamento)

| Risco | Impacto | Mitigação |
|-------|---------|-----------|
| **Tentação de antecipar a #93** (criar `Contador`/RBAC ao mexer no guard das guias) | Estoura o escopo e mistura duas ondas no mesmo PR | Item explícito na DoD; o Javadoc de T6 já registra o limite ("papel Contador fica na #93"). |
| **Janela entre PR 1 e PR 3 com semântica inconsistente**: header inválido devolve 500 até o PR 3 | Se a sprint parar no PR 1/2, a main fica com um comportamento pior de diagnosticar | Merges na mesma sprint; se houver risco de corte, **T9+T10 podem ser promovidos para o PR 1** (decisão explícita, não silenciosa). |
| **Frontend adota o header antes da #94 estar mergeada** | Chamadas a `/api/contador/guias` com `produtorId` divergente passam a falhar com 404 só depois | Comunicar a nota de integração (spec §8) ao time de frontend **junto com o merge do PR 2**, não do PR 1. |
| **PR 1 grande** (5 tasks, 8 SP) frente à regra "PRs pequenos" | Review mais lento e maior chance de regressão escapar | Commits atômicos por task dentro do PR, facilitando review incremental; T3 e T5 são triviais e isoláveis. |
| **T13 (docs) ficar para depois do merge** | `CLAUDE.md` e skills desatualizados viram dívida silenciosa | T13 é bloqueante do PR 3; sem ela, o PR não fecha a DoD. |

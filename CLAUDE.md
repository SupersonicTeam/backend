# CLAUDE.md — Backend AgroContabil

Guia para o Claude Code (e qualquer agente) trabalhar neste repositório. Leia antes de tocar no código.

## O que é

Backend do **AgroContabil** — software de auxílio ao produtor rural e ao escritório de contabilidade na reforma tributária do agronegócio (IBS/CBS — EC 132/2023 + LC 214/2025). TCC 2026, Supersonic Team (Murillo Weiss, Luiz Feliz Gervazio, Herick Eduardo Vavassori).

## Stack fixada

Java 21 · Spring Boot 3.5.14 · Spring Web · Spring Data JPA · PostgreSQL 16 · Liquibase · Spring Security · Bean Validation · Spring Actuator · springdoc-openapi 2.8.x · JUnit 5 + Mockito · Docker Compose.

> Versão do Spring Boot **fixada em 3.5.14** (não usar SNAPSHOT). Linha 3.5 com EOL OSS em 30/06/2026 — aceitável para o escopo do TCC. Migração para 4.x exigiria revisar springdoc e Spring Cloud; só fazer com decisão explícita do Murillo.

## Comandos

```bash
./mvnw spring-boot:run        # roda em dev (sobe o Postgres do compose junto)
./mvnw test                   # testes no profile test (H2 em memória)
./mvnw clean package          # gera o JAR em target/
```

## Arquitetura — regras inegociáveis

- **Camadas:** `Controller → Service → Repository`. Sempre nessa direção.
- **Controllers** apenas orquestram: recebem DTO, chamam service, devolvem resposta. Zero regra de negócio.
- **Regra de negócio** vive só na camada `service`.
- **Entidades JPA nunca são expostas** na API — sempre converter para DTO via `mapper`. Request e response são DTOs (`record`).
- **Validação** de entrada via Bean Validation (`@Valid` + anotações nos DTOs). Nunca validar manualmente o que a anotação resolve.
- **Erros** sempre pelo `GlobalExceptionHandler` (`@RestControllerAdvice`), devolvendo `ApiError`. Nunca tratar exceção dentro do controller nem vazar stack trace ao cliente.

## Estrutura de pacotes

```
br.com.agrocontabil
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig
├── controller/    # REST controllers
├── service/       # regras de negócio
├── repository/    # Spring Data JPA
├── model/         # entidades JPA
├── dto/           # request/response (records)
├── mapper/        # entidade <-> DTO
├── exception/     # handler global + exceções de domínio
└── common/        # ApiError, utilitários
```

## Segurança

- **Auth em stand-by até a Sprint 8.** O `SecurityConfig` está com `permitAll()` em todos os endpoints, mas com CSRF off, sessão stateless e CORS controlado. A estrutura permite plugar JWT sem refatorar controllers — basta trocar o `permitAll()` por regras reais e adicionar o filtro JWT.
- **Nenhum segredo no repositório.** Senha de banco e JWT secret vêm de env var (`${DB_PASSWORD}`, `${JWT_SECRET}`). Ver `.env.example`.
- **CORS** controlado por `app.cors.allowed-origins` (env `APP_CORS_ALLOWED_ORIGINS`). Nunca usar `*` em prod.
- **Logs nunca contêm senha/token.** Ao logar requisições ou erros, não incluir credenciais.

## Banco e migrations

- Schema versionado pelo **Liquibase** (`db/changelog/db.changelog-master.yaml`).
- Hibernate em `ddl-auto: validate` — **proibido** deixar `update`/`create`. O Hibernate nunca altera o schema.
- Cada mudança de schema = um changeSet novo em `db/changelog/changes/`. Nunca editar um changeSet já aplicado.

## Convenções

- **Idioma do código:** inglês (variáveis, métodos, classes). **Comunicação:** português.
- **Estilo:** Google Java Style Guide.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:`).
- **Branches:** nunca commitar direto em `main`/`dev`/`prod`. Usar `feature/`, `fix:`, `chore/` + worktree isolado.
- **PRs pequenos e deployáveis** — uma responsabilidade lógica por PR (programação incremental).
- **Datas:** `YYYY-MM-DD`. Timezone `America/Sao_Paulo`.

## Testes

- Smoke test de contexto já existe (`AgroContabilApplicationTests`, profile `test`, H2).
- Toda feature nova: testes unitários de service (Mockito) + teste de integração do fluxo crítico. Meta de cobertura do TCC: 80% (Sprint 9).
- Mockito com strict stubs: não criar stub de objeto usado só após early return.

## Estado atual — Sprint 0

Entregue: pom limpo e fixado, estrutura de pacotes, `SecurityConfig`/`CorsConfig`/`OpenApiConfig`, `GlobalExceptionHandler` + `ApiError`, `HealthController`, configs por profile, Docker Compose e baseline do Liquibase. **Sem entidades de domínio ainda.**

## Roadmap pós-básico (não implementado)

Quando a fundação estiver validada, nesta ordem aproximada:

1. **Observabilidade:** logs estruturados em JSON (logback + encoder), métricas via Micrometer/Prometheus, expor mais endpoints do Actuator com cautela.
2. **Monitoramento:** Sentry (erros) ou stack Grafana/Loki (logs/métricas).
3. **CI/CD (GitHub Actions):** rodar testes → build do JAR → build da imagem Docker → deploy automático na VPS.
4. **Rate limiting** na API (Sprint 8, junto da segurança).
5. **Auth JWT completa** (Sprint 8): cadastro, login JWT, RBAC produtor/contador, recuperação de senha.

## Referências do domínio

- IBS/CBS — EC 132/2023, LC 214/2025.
- Documentação do projeto: `../Docs/` e nota `projects/TCC - 2026/` no Second Brain.

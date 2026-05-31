# AgroContabil — Backend

Backend do **AgroContabil**, software de auxílio ao produtor rural e ao escritório de contabilidade frente à reforma tributária do agronegócio (IBS/CBS — EC 132/2023 + LC 214/2025). TCC 2026 — Supersonic Team.

## Stack

| Tecnologia | Função |
|---|---|
| Java 21 | Linguagem |
| Spring Boot 3.5.14 | Framework |
| Spring Web | APIs REST |
| Spring Data JPA | Persistência |
| PostgreSQL 16 | Banco de dados |
| Liquibase | Migrations |
| Spring Security | Segurança (auth em stand-by até Sprint 8) |
| Bean Validation | Validação de DTOs |
| Spring Actuator | Health check / observabilidade |
| springdoc-openapi | Documentação Swagger |
| JUnit 5 + Mockito | Testes |
| Docker Compose | Infra local |

## Pré-requisitos

- JDK 21
- Docker + Docker Compose (para o PostgreSQL local)

## Como rodar (dev)

```bash
# 1. Copie o template de variáveis e ajuste a senha
cp .env.example .env

# 2. Suba a aplicação — o Postgres do compose.yaml sobe junto automaticamente
./mvnw spring-boot:run
```

A API sobe em `http://localhost:8080`.

| Recurso | URL |
|---|---|
| Health (app) | http://localhost:8080/api/v1/health |
| Health (Actuator) | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

> No profile `dev`, o `spring-boot-docker-compose` sobe e derruba o container do Postgres junto com a aplicação. Para subir o banco isolado: `docker compose up -d postgres`.

## Testes

```bash
./mvnw test
```

Os testes rodam no profile `test` com banco H2 em memória — não dependem de Docker.

## Build do JAR

```bash
./mvnw clean package
java -jar target/backend-tcc-0.0.1-SNAPSHOT.jar
```

## Profiles e configuração

A configuração é dividida por profile e **nenhum segredo fica no repositório** — senhas e chaves vêm de variáveis de ambiente.

| Profile | Arquivo | Uso |
|---|---|---|
| base | `application.yml` | Comum a todos |
| `dev` | `application-dev.yml` | Local + Docker Compose |
| `prod` | `application-prod.yml` | Tudo via env var |
| `test` | `application-test.yml` | H2 em memória |

Variáveis de ambiente principais (ver `.env.example`): `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `APP_CORS_ALLOWED_ORIGINS`, `JWT_SECRET`.

## Estrutura de pacotes

```
br.com.agrocontabil
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig
├── controller/    # REST controllers (orquestram, sem regra de negócio)
├── service/       # regras de negócio
├── repository/    # Spring Data JPA
├── model/         # entidades JPA
├── dto/           # request/response (records)
├── mapper/        # entidade <-> DTO
├── exception/     # GlobalExceptionHandler + exceções de domínio
└── common/        # ApiError e utilitários compartilhados
```

Arquitetura em camadas (MVC/clean): o fluxo de uma requisição é sempre `Controller → Service → Repository`. Controllers nunca contêm regra de negócio; entidades JPA nunca são expostas diretamente — sempre via DTO.

## Migrations (Liquibase)

O schema é versionado pelo Liquibase. O Hibernate roda em `ddl-auto: validate` — **nunca** altera o banco. Cada mudança de schema vira um changeSet em `src/main/resources/db/changelog/changes/`, incluído no `db.changelog-master.yaml`.

## Status atual — Sprint 0 (Fundação)

Esta entrega é a Sprint 0: configuração, classes transversais e um endpoint de health que prova que a aplicação sobe ponta a ponta. Ainda **não** há entidades de domínio (notas fiscais, tributos) — essas entram a partir da Sprint 2.

## Roadmap pós-básico

Itens planejados, ainda não implementados: logs estruturados (JSON), métricas (Micrometer/Prometheus), Sentry ou Grafana/Loki, e CI/CD via GitHub Actions (testes → build do JAR → build da imagem Docker → deploy). Detalhes no `CLAUDE.md`.

## Time

Murillo Weiss · Luiz Feliz Gervazio · Herick Eduardo Vavassori

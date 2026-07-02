# AgroContabil — Backend

[![CI](https://github.com/SupersonicTeam/backend/actions/workflows/ci.yml/badge.svg)](https://github.com/SupersonicTeam/backend/actions/workflows/ci.yml)

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
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig, certificado, SEFAZ, tributos
├── controller/    # REST controllers (orquestram, sem regra de negócio)
├── service/       # regras de negócio (notas, geração NF-e/NFA, importação, SEFAZ)
├── repository/    # Spring Data JPA
├── model/         # entidades JPA + enums de domínio
├── dto/           # request/response (records)
├── mapper/        # entidade <-> DTO
├── nfe/           # geração/importação de NF-e: JAXB layout 4.00, XML, assinatura digital, XSD
├── danfe/         # DANFE em PDF (JasperReports)
├── sefaz/         # transmissão à SEFAZ (transporte plugável, retry, job de sync)
├── exception/     # GlobalExceptionHandler + exceções de domínio
└── common/        # ApiError (envelope errors[]) e utilitários compartilhados
```

Arquitetura em camadas (MVC/clean): o fluxo de uma requisição é sempre `Controller → Service → Repository`. Controllers nunca contêm regra de negócio; entidades JPA nunca são expostas diretamente — sempre via DTO. Erros saem padronizados pelo `GlobalExceptionHandler` no envelope `ApiError.errors[]`.

## Funcionalidades (módulo Notas Fiscais)

Emissão completa de NF-e modelo 55, exposta em `/api/notas`:

| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/notas` | Lista paginada (do produtor autenticado) |
| GET | `/api/notas/{id}` | Busca por ID (anti-IDOR: 404 para nota de outro produtor) |
| POST | `/api/notas` | Cria nota (status RASCUNHO) |
| POST | `/api/notas/importar` | Importa XML(s) de NF-e recebida (multipart) |
| PUT / DELETE | `/api/notas/{id}` | Atualiza / exclui (só RASCUNHO ou COM_INCONSISTENCIAS) |
| POST | `/api/notas/{id}/gerar-xml` | Gera e assina o XML da NF-e |
| GET | `/api/notas/{id}/xml` | Baixa o XML assinado |
| GET | `/api/notas/{id}/pdf` | Baixa a DANFE em PDF |
| POST | `/api/notas/{id}/enviar-sefaz` | Transmite à SEFAZ (homologação) |
| GET | `/api/notas/{id}/status-sefaz` | Consulta e sincroniza o status na SEFAZ |
| POST | `/api/notas/{id}/cancelar` | Cancela NF-e autorizada (limite de 30 dias) |
| GET | `/api/notas/{id}/validacoes` | Histórico de inconsistências de validação |

Inclui cálculo de IBS/CBS por item, geração de NFA (Nota Fiscal Avulsa do produtor rural), validação de NCM/CFOP do agronegócio, assinatura digital (certificado `.p12`) e validação contra o XSD oficial layout 4.00. A comunicação com a SEFAZ usa um transporte plugável com mock determinístico até a integração real (Sprint 7).

## Migrations (Liquibase)

O schema é versionado pelo Liquibase. O Hibernate roda em `ddl-auto: validate` — **nunca** altera o banco. Cada mudança de schema vira um changeSet em `src/main/resources/db/changelog/changes/`, incluído no `db.changelog-master.yaml`.

## Status atual

O módulo de **Notas Fiscais** está completo: CRUD, geração e assinatura de NF-e modelo 55, DANFE em PDF, importação de XML, geração de NFA, validação de NCM/CFOP, envio/consulta à SEFAZ (homologação, via mock plugável) e cancelamento dentro do prazo. A próxima sprint (Sprint 4) foca em créditos tributários e relatórios de conformidade (apuração mensal de IBS/CBS).

Autenticação está em stand-by até a Sprint 8 — o produtor é injetado por um filtro mock, e a estrutura de segurança permite plugar JWT sem refatorar os controllers. Detalhes de arquitetura, decisões e roadmap completo no `CLAUDE.md`.

## Roadmap pós-básico

Itens planejados, ainda não implementados: logs estruturados (JSON), métricas (Micrometer/Prometheus), Sentry ou Grafana/Loki, e CI/CD via GitHub Actions (testes → build do JAR → build da imagem Docker → deploy). Detalhes no `CLAUDE.md`.

## Time

Murillo Weiss · Luiz Feliz Gervazio · Herick Eduardo Vavassori

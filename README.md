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
| Spring AI 1.1.8 + Ollama + pgvector | Ceres, a assistente fiscal (ver `docs/ceres-assistente.md`) |

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

> **Profile obrigatório.** A aplicação **não sobe** sem exatamente um profile de ambiente (`dev`, `homolog`, `prod` ou `test`) — não existe mais default `dev` (issue #219). O `./mvnw spring-boot:run` já ativa `dev` quando `SPRING_PROFILES_ACTIVE` não está definida; `-Dspring-boot.run.profiles=dev,ingestao` (ou `dev,avaliacao`) sobrepõe. Rodando pela IDE, defina `SPRING_PROFILES_ACTIVE=dev` na configuração de execução.

A API sobe em `http://localhost:8080`.

| Recurso | URL |
|---|---|
| Health (app) | http://localhost:8080/api/v1/health |
| Health (Actuator) | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

> No profile `dev`, o `spring-boot-docker-compose` sobe e derruba o container do Postgres junto com a aplicação. Para subir o banco isolado: `docker compose up -d postgres`.

> **Ceres (assistente fiscal):** o Ollama fica no profile `ia` do compose e não sobe por padrão. Para usar a Ceres em dev: `docker compose --profile ia up -d` (a primeira subida baixa ~4 GB de modelos). Sem ele, o resto do backend funciona normalmente. Detalhes em [`docs/ceres-assistente.md`](docs/ceres-assistente.md).

### Ambiente novo: ingerir a legislação (uma vez)

A base de legislação que a Ceres cita (`legislacao_trecho`) **não** é criada pelo Liquibase nem
pela subida: é carregada por um comando de manutenção, e um banco recém-criado vem vazio. Sem
ela a Ceres não inventa nada, mas responde "não encontrei fundamento legal" a toda pergunta de
lei e o frontend nunca mostra citação. Na subida com a Ceres ligada, a aplicação loga um `WARN`
`Base de legislacao vazia` quando falta esse passo (issue #175).

1. Suba Postgres e Ollama: `docker compose --profile ia up -d`.
2. Salve o texto compilado de cada norma a partir do Planalto, sem converter o charset.
3. Rode o comando de ingestão uma vez por norma (idempotente; comando completo, com os
   parâmetros de cada uma, em [`docs/ceres-assistente.md`](docs/ceres-assistente.md#ingestão-comando-manual-nunca-endpoint)):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="--ingestao.arquivo=/caminho/lcp214.htm --ingestao.norma='LC 214/2025' --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm --ingestao.data-captura=2026-09-29 --ingestao.vigencia-inicio=2025-01-16"
```

| Norma | Página no Planalto | `vigencia-inicio` |
|---|---|---|
| LC 214/2025 | `ccivil_03/leis/lcp/lcp214.htm` | 2025-01-16 |
| EC 132/2023 | `ccivil_03/constituicao/emendas/emc/emc132.htm` | 2023-12-21 |
| LC 227/2026 | `ccivil_03/leis/lcp/lcp227.htm` | 2026-01-14 |
| Lei 8.212/1991 | `ccivil_03/leis/l8212cons.htm` | 1991-07-25 |
| Lei 9.528/1997 (só `--ingestao.somente='art. 6º'`) | `ccivil_03/leis/l9528.htm` | 1997-12-11 |

4. Confira: `SELECT norma, count(*) FROM legislacao_trecho GROUP BY norma;` deve listar as cinco.

## Testes

```bash
./mvnw test
```

Os testes rodam no profile `test` com banco H2 em memória — não dependem de Docker.

## Build do JAR

```bash
./mvnw clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/backend-tcc-0.0.1-SNAPSHOT.jar
```

Sem `SPRING_PROFILES_ACTIVE`, o JAR (e a imagem Docker) recusa a subida com `Nenhum profile de ambiente ativo`. Em `prod` ele também recusa o mock da SEFAZ, que dá toda NF-e como autorizada (#263): defina `NFE_SEFAZ_MOCK_ENABLED=false` e configure o transporte real ([`docs/sefaz-transporte-soap.md`](docs/sefaz-transporte-soap.md)), além dos segredos de produção (`JWT_SECRET`, `CRIPTO_CHAVE`).

## Profiles e configuração

A configuração é dividida por profile e **nenhum segredo fica no repositório** — senhas e chaves vêm de variáveis de ambiente.

| Profile | Arquivo | Uso |
|---|---|---|
| base | `application.yml` | Comum a todos |
| `dev` | `application-dev.yml` | Local + Docker Compose |
| `homolog` | `application-homolog.yml` | Homologação (seed; só login com JWT, sem o mock `X-Usuario-Id` desde a #221) |
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

## Premissas e escopo

O cálculo de IBS/CBS vale para o produtor **no regime regular** (LC 214/2025, arts. 164 e 165:
acima do limite de receita ou optante); o produtor não contribuinte e o crédito presumido do
comprador (art. 168) ficam para a [#281](https://github.com/SupersonicTeam/backend/issues/281).
ICMS e Funrural seguem por guia, com o contador. Detalhes em
[`docs/premissas-e-escopo.md`](docs/premissas-e-escopo.md); resposta preparada para a banca em
[`docs/roteiro-banca-regime-regular.md`](docs/roteiro-banca-regime-regular.md).

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

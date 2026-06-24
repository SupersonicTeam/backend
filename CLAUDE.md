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
- **Erros** sempre pelo `GlobalExceptionHandler` (`@RestControllerAdvice`), devolvendo `ApiError` com envelope **`errors[]`** estruturado (`code`/`field`/`message`/`suggestion`/`rejectedValue`/`expectedValue`). Exceções de domínio estendem `DomainException` (carrega `HttpStatus` + lista de `ErrorDetail`). Nunca tratar exceção dentro do controller, nem vazar stack trace ou detalhe técnico ao cliente (5xx usa mensagem genérica; detalhe só no log).

## Estrutura de pacotes

```
br.com.agrocontabil
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig, MockProdutorFilter,
│                  # TributosProperties/Config, CertificadoDigitalProperties/Config,
│                  # SefazProperties/Config, RetryConfig, SchedulingConfig, DataLoader
├── controller/    # REST controllers (HealthController, NotaFiscalController)
├── service/       # regras de negócio (NotaFiscalService, GeracaoNotaFiscalService,
│                  # SefazService, CalculoIbsCbsService, ChaveAcessoGenerator,
│                  # AuthenticatedProdutorProvider)
├── repository/    # Spring Data JPA
├── model/         # entidades JPA (Produtor, NotaFiscal, NotaFiscalItem, EnderecoDestinatario)
│                  # enums: StatusNotaFiscal (RASCUNHO→XML_GERADO→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ/CANCELADA)
│                  # modelos pré-criados para sprint futura de consultoria AI:
│                  # Conversa, Diagnostico, Feedback, ImpactoGeral, PorteEmpresa, TipoFeedback, RegimeTributario
├── dto/           # request/response (records): NotaFiscalRequest/Response,
│                  # NotaFiscalItemRequest/Response, SefazEnvioResponse, SefazStatusResponse
├── mapper/        # entidade <-> DTO (NotaFiscalMapper)
├── nfe/           # geração de NF-e: model/ (JAXB puras layout 4.00), FiscalRulesValidator,
│                  # NotaFiscalNfeMapper, XmlGenerator, XsdValidator, SecureXmlFactories (XXE),
│                  # NfeAssinador, AssinaturaValidator, KeyStoreCertificadoProvider
├── danfe/         # DANFE em PDF: DanfePdfService (JasperReports), DanfeItemRow
├── sefaz/         # transmissão SEFAZ: SefazTransport (interface plugável), MockSefazTransport,
│                  # SefazClient, SefazRetorno, SituacaoSefaz, SefazAmbiente,
│                  # SefazStatusSyncJob (job agendado de sincronização)
├── exception/     # GlobalExceptionHandler + DomainException (base) + exceções de domínio
└── common/        # ApiError (envelope errors[]), ErrorCode, ErrorDetail, utilitários
```

### Endpoints REST ativos

| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/notas` | Lista paginada (produtor autenticado) |
| GET | `/api/notas/{id}` | Busca por ID (anti-IDOR 404) |
| POST | `/api/notas` | Cria nota (status RASCUNHO) |
| PUT | `/api/notas/{id}` | Atualiza (só RASCUNHO) |
| DELETE | `/api/notas/{id}` | Exclui (só RASCUNHO) |
| POST | `/api/notas/{id}/gerar-xml` | Gera e assina XML NF-e (RASCUNHO→XML_GERADO) |
| GET | `/api/notas/{id}/xml` | Baixa XML assinado (`application/xml`) |
| GET | `/api/notas/{id}/pdf` | Baixa DANFE (`application/pdf`) |
| POST | `/api/notas/{id}/enviar-sefaz` | Transmite à SEFAZ (XML_GERADO→AUTORIZADA/REJEITADA) |
| GET | `/api/notas/{id}/status-sefaz` | Consulta e sincroniza status na SEFAZ |

## Segurança

- **Auth em stand-by até a Sprint 8.** O `SecurityConfig` está com `permitAll()` em todos os endpoints, mas com CSRF off, sessão stateless e CORS controlado. A estrutura permite plugar JWT sem refatorar controllers — basta trocar o `permitAll()` por regras reais e adicionar o filtro JWT. O produtor "autenticado" vem do `MockProdutorFilter` via `AuthenticatedProdutorProvider`.
- **Ownership (anti-IDOR) já aplicado.** Toda operação sobre nota fiscal usa `findByIdAndProdutorId` e responde **404** (não 403) para nota de outro produtor — não revela existência. Mensagens de erro de geração de XML são genéricas ao cliente (detalhe técnico só no log).
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
- **Cobertura atual: 140 testes, todos passando** (JUnit 5 + Mockito; `./mvnw test`).

## Estado atual — Sprint 3 concluída (2026-06-24)

**Sprint 0** (fundação): pom fixado, estrutura de pacotes, `SecurityConfig`/`CorsConfig`/`OpenApiConfig`, `GlobalExceptionHandler` + `ApiError`, `HealthController`, configs por profile, Docker Compose, baseline do Liquibase, `MockProdutorFilter` (auth mockada por produtor até a Sprint 8) e `DataLoader` (seed).

**Sprint 2** — módulo de Notas Fiscais (issues #15 e #16, fechadas; PRs #7–#12):

- **CRUD de notas fiscais (RF008):** entidades `NotaFiscal`/`NotaFiscalItem`/`EnderecoDestinatario`; `NotaFiscalRepository` (paginado + ownership); `NotaFiscalService` (CRUD, anti-IDOR 404, bloqueio fora de RASCUNHO → 409); endpoints `GET/POST/PUT/DELETE /api/notas`; migrations `0002`.
- **Geração de NF-e modelo 55 (RF009):** `CalculoIbsCbsService` (IBS/CBS via `TributosProperties`); pipeline `FiscalRulesValidator` → `NotaFiscalNfeMapper` → JAXB → `XmlGenerator` → `XsdValidator` (layout 4.00); `ChaveAcessoGenerator` (44 dígitos + DV); persistência do XML + endpoints `POST /gerar-xml` e `GET /xml`; migrations `0003`–`0004`.

**Sprint 3** — emissão completa da NF-e (PRs #24–#28, fechada em 2026-06-24):

- **Contrato de erro estruturado (PR #24):** `ApiError.errors[]` com `ErrorDetail` (`code`/`field`/`message`/`suggestion`/`rejectedValue`/`expectedValue`); catálogo `ErrorCode`; `DomainException` (base); `GlobalExceptionHandler` unificado.
- **Assinatura digital NF-e — issue #17 (PR #25):** `NfeAssinador` (assinatura enveloped no `infNFe` via Apache Santuario — C14N inclusiva, RSA-SHA1, `X509Data`); `AssinaturaValidator` (validação + tamper detection); `KeyStoreCertificadoProvider` (`.p12` PKCS#12; cert sandbox em `src/test/resources/certs/`, real via env `NFE_CERT_*`); `SecureXmlFactories` (XXE hardening centralizado). Pipeline: assina → valida → XSD. Ver `docs/nfe-certificado-digital.md`.
- **Envio SEFAZ homologação — issue #15 (PR #26):** `SefazTransport` (interface plugável); `MockSefazTransport` (mock determinístico — autoriza/rejeita com base no cStat); `SefazClient` (orquestra retry via `RetryConfig`); `SefazService` (transita status: XML_GERADO → AUTORIZADA/REJEITADA/PENDENTE_SEFAZ); endpoint `POST /api/notas/{id}/enviar-sefaz`; `SefazProperties` (ambiente, retry); `StatusNotaFiscal` expandido (+ REJEITADA, PENDENTE_SEFAZ).
- **Consulta status SEFAZ — issue #16 (PR #27):** `SefazService.sincronizarStatus()` consulta pela chave de acesso e atualiza status local; `SefazStatusSyncJob` (job agendado via `@Scheduled`, `SchedulingConfig`); endpoint `GET /api/notas/{id}/status-sefaz`.
- **Download DANFE PDF — issue #14 (PR #28):** `DanfePdfService` (JasperReports + template `.jrxml`); `DanfeItemRow`; endpoint `GET /api/notas/{id}/pdf` (`application/pdf`, `Content-Disposition: attachment`); exceções `DanfeIndisponivelException` (409 para RASCUNHO) e `DanfeGenerationException`.

Cobertura: **140 testes** (JUnit + Mockito). Cada PR passou por code review (GitHub Copilot) + auditoria OWASP + CI verde.

### Próxima sprint — issues abertas

- **#22** Persistência de inconsistências de validação + status da nota
- **#21** Cancelamento de NF-e (RN006 — limite 30 dias)
- **#20** Validação de NCM e CFOP (RN008)
- **#19** Importação de XML de nota fiscal (UC002)
- **#18** Geração de NFA (Nota Fiscal Avulsa do produtor rural)

### Débitos técnicos abertos

- **Sprint 7 (SEFAZ real):** `cNF` da chave via `SecureRandom` (hoje determinístico para teste). Substituir `MockSefazTransport` por transporte SOAP real + certificado **ICP-Brasil** em produção.
- **Sprint 8 (segurança/auth real):** mascarar CPF/CNPJ em logs (LGPD); lock pessimista contra geração concorrente; `IllegalStateException`→401 no `AuthenticatedProdutorProvider`; validação de dígitos verificadores de CPF/CNPJ.
- **Modelo:** `Produtor` sem endereço completo nem IE (placeholders no `NotaFiscalNfeMapper`); divisão IBS UF/Município hoje 50/50 (parametrização real pendente).
- **Modelos pré-criados (consultoria AI — sprint não definida):** entidades `Conversa`, `Diagnostico`, `Feedback`, `ImpactoGeral`, `PorteEmpresa`, `TipoFeedback` e respectivos repositories existem no código mas **não têm controller nem service** — são scaffolding de feature futura. Não criar lógica sobre elas sem decisão explícita do Murillo.

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

# CLAUDE.md — Backend AgroContabil

Guia para o Claude Code (e qualquer agente) trabalhar neste repositório. Leia antes de tocar no código.

## Skills do projeto — USE PRIMEIRO (economia de tokens/contexto)

Este repositório tem skills fixas em `.claude/skills/`. **Antes** de ler arquivos, escrever código ou levantar o estado do projeto, invoque a skill correspondente via `Skill` tool — ela já traz o contexto destilado, evitando `find`/`ls`/leituras amplas e releitura deste arquivo inteiro.

| Skill | Quando usar |
|-------|-------------|
| **`codebase-map`** | ANTES de procurar/ler arquivos. Mapa pacote → classe → responsabilidade. Diz exatamente onde está cada coisa. |
| **`project-conventions`** | ANTES de escrever ou revisar código. Padrões inegociáveis: camadas, fluxo de nova feature, contrato de erro, DTO/mapper, validação, testes, migrations, git. |
| **`project-status`** | No início da sessão ou quando precisar do estado atual (issues abertas, último PR, saúde dos testes, próxima ação). |

> **Manutenção:** ao adicionar pacote/classe significativa, atualize `codebase-map`. Ao mudar um padrão de projeto, atualize `project-conventions`. As skills são a fonte de verdade operacional; este CLAUDE.md é o guia de fundo.

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
│                  # GeracaoNfaService, ImportacaoNotaFiscalService, SefazService,
│                  # TributacaoService, CreditoTributarioService, ChaveAcessoGenerator,
│                  # AliquotaIbsCbsResolver + ReducaoNcmResolver (resolvedores únicos débito×crédito),
│                  # NotaFiscalValidacaoService, AuthenticatedProdutorProvider)
├── repository/    # Spring Data JPA (NotaFiscal, NotaFiscalValidacao, Produtor,
│                  # CfopReferencia, NcmReferencia + repos do scaffolding AI)
├── model/         # entidades JPA (Produtor, NotaFiscal, NotaFiscalItem, EnderecoDestinatario,
│                  # NotaFiscalValidacao [#22], CfopReferencia/NcmReferencia [#20])
│                  # enums: StatusNotaFiscal (RASCUNHO→XML_GERADO→[ENVIANDO]→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ;
│                  #                   AUTORIZADA→[CANCELANDO]→CANCELADA; COM_INCONSISTENCIAS),
│                  # OrigemNotaFiscal (PROPRIA/IMPORTADA), TipoDocumentoFiscal (NFE/NFA),
│                  # TipoOperacaoCfop, AmbitoCfop, TipoProdutor
│                  # modelos pré-criados para sprint futura de consultoria AI:
│                  # Conversa, Diagnostico, Feedback, ImpactoGeral, PorteEmpresa, TipoFeedback, RegimeTributario
├── dto/           # request/response (records): NotaFiscalRequest/Response,
│                  # NotaFiscalItemRequest/Response, CancelamentoRequest/Response [#21],
│                  # ImportacaoNotaResultado [#19], SefazEnvioResponse, SefazStatusResponse,
│                  # NotaFiscalValidacaoResponse
├── mapper/        # entidade <-> DTO (NotaFiscalMapper)
├── validation/    # Bean Validation customizada: @ValidCpfCnpj/@ValidNcm/@ValidCfop
│                  # + @CodigoIbgeCoerenteComUf (constraint de classe, issue #88)
│                  # + validators (CpfCnpjValidator, NcmValidator, CfopValidator,
│                  #   CodigoIbgeCoerenteComUfValidator)
├── nfe/           # geração/importação de NF-e: model/ (JAXB puras layout 4.00), FiscalRulesValidator,
│                  # NotaFiscalNfeMapper, NfeToNotaFiscalMapper (reverso), NfeXmlImporter (XXE-safe),
│                  # XmlGenerator, XsdValidator, SecureXmlFactories (XXE),
│                  # NfeAssinador, AssinaturaValidator, KeyStoreCertificadoProvider
├── danfe/         # DANFE em PDF: DanfePdfService (JasperReports), DanfeItemRow
├── sefaz/         # transmissão SEFAZ: SefazTransport (interface plugável), MockSefazTransport,
│                  # SefazClient, SefazRetorno, SituacaoSefaz, SefazAmbiente,
│                  # SefazStatusSyncJob (job agendado de sincronização)
├── exception/     # GlobalExceptionHandler + DomainException (base) + exceções de domínio
└── common/        # ApiError (envelope errors[]), ErrorCode, ErrorDetail, DocumentoFiscalValidator,
                   # UfIbge (cUF por UF + coerencia codigo IBGE x UF)
```

### Endpoints REST ativos

| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/notas` | Lista paginada (produtor autenticado) |
| GET | `/api/notas/{id}` | Busca por ID (anti-IDOR 404) |
| POST | `/api/notas` | Cria nota (status RASCUNHO) |
| POST | `/api/notas/importar` | Importa XML(s) de NF-e (multipart; produtor=destinatário; origem=IMPORTADA) |
| PUT | `/api/notas/{id}` | Atualiza (só RASCUNHO/COM_INCONSISTENCIAS) |
| DELETE | `/api/notas/{id}` | Exclui (só RASCUNHO/COM_INCONSISTENCIAS) |
| POST | `/api/notas/{id}/gerar-xml` | Gera e assina XML NF-e (RASCUNHO/COM_INCONSISTENCIAS→XML_GERADO) |
| GET | `/api/notas/{id}/xml` | Baixa XML assinado (`application/xml`; 409 se ainda nao gerado) |
| GET | `/api/notas/{id}/pdf` | Baixa DANFE (`application/pdf`) |
| POST | `/api/notas/{id}/enviar-sefaz` | Transmite à SEFAZ (XML_GERADO→AUTORIZADA/REJEITADA) |
| GET | `/api/notas/{id}/status-sefaz` | Consulta e sincroniza status na SEFAZ |
| POST | `/api/notas/{id}/cancelar` | Cancela NF-e autorizada (limite 30 dias — RN006) |
| GET | `/api/notas/{id}/validacoes` | Histórico de inconsistências de validação (anti-IDOR 404) |
| POST | `/api/contador/guias` | Cria guia fiscal do Contador (produtorId explícito; sem auth de Contador) |
| GET | `/api/contador/guias?produtorId=` | Lista guias fiscais de um produtor |
| PATCH | `/api/contador/guias/{id}/pagar` | Marca guia como paga (ação do produtor autenticado, anti-IDOR) |
| GET | `/api/calendario-fiscal/{produtorId}/vencimentos` | Vencimentos fiscais do produtor (guias fiscais) |

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

- **Idioma do código (convenção híbrida, refletindo a base real):**
  - **Domínio fiscal → português.** Classes, métodos de negócio e campos do domínio usam português porque os termos são intraduzíveis sem perda de sentido fiscal-BR: `NotaFiscal`, `NotaFiscalItem`, `Produtor`, `ChaveAcessoGenerator`, `CalculoIbsCbsService`, `gerarXml`, `sincronizarStatus`, `registrarInconsistencias`. (`NotaFiscal → Invoice` perde a conotação fiscal brasileira; `SEFAZ`, `DANFE`, `IBS/CBS` não têm tradução.)
  - **Infra e utilitários técnicos genéricos → inglês.** Quando o conceito não é do domínio fiscal: `ApiError`, `ErrorCode`, `ErrorDetail`, `XmlGenerator`, `XsdValidator`, `SecureXmlFactories`, `GlobalExceptionHandler`, `*Config`, `DataLoader`.
  - **API obrigatória do Java/Spring → inglês.** Getters/setters (`getId`/`setStatus`), nomes exigidos por frameworks e anotações permanecem em inglês.
  - **Regra prática:** escreva como o código ao redor. Na dúvida sobre um termo do domínio fiscal, use português; para um utilitário técnico genérico, use inglês.
  - **Comunicação (PR, commits, conversa):** português.
- **Estilo:** Google Java Style Guide (checkstyle `google_checks.xml` no `verify`, severidade *warning* — não bloqueante).
- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:`).
- **Branches:** nunca commitar direto em `main`/`dev`/`prod`. Usar `feature/`, `fix:`, `chore/` + worktree isolado.
- **PRs pequenos e deployáveis** — uma responsabilidade lógica por PR (programação incremental).
- **Datas:** `YYYY-MM-DD`. Timezone `America/Sao_Paulo`.

## Testes

- Smoke test de contexto já existe (`AgroContabilApplicationTests`, profile `test`, H2).
- Toda feature nova: testes unitários de service (Mockito) + teste de integração do fluxo crítico. Meta de cobertura do TCC: 80% (Sprint 9).
- Mockito com strict stubs: não criar stub de objeto usado só após early return.
- **Cobertura atual: 475 testes, todos passando** (JUnit 5 + Mockito; `./mvnw test`).

## Estado atual — Sprint 5 concluída (2026-07-06)

> Histórico das sprints anteriores abaixo (Sprint 0 → Sprint 3); Sprints 4 e 5 no fim desta seção.

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
- **Persistência de inconsistências de validação — issue #22 (PR #29):** entidade `NotaFiscalValidacao` (espelha `ErrorDetail` + `loteValidacao` UUID que agrupa cada tentativa + `validadoEm`); `NotaFiscalValidacaoRepository`; migration `0006`; novo status `COM_INCONSISTENCIAS` (decisão: só este — `VALIDADA`/`AGUARDANDO_CORRECAO` descartados por redundância, pois `XML_GERADO` já implica validação OK). `NotaFiscalValidacaoService` persiste o lote em transação **`REQUIRES_NEW`** (sobrevive ao rollback do 422) e marca a nota `COM_INCONSISTENCIAS`; `NotaFiscalService.gerarXml` captura `NotaFiscalInvalidaParaEmissaoException`/`XmlGenerationException`, registra o histórico e relança. `COM_INCONSISTENCIAS` é **editável** (update/delete/gerar-xml liberados como RASCUNHO). Histórico acumulado por tentativa em `GET /api/notas/{id}/validacoes` (ownership anti-IDOR 404).

Cobertura: **228 testes** (JUnit + Mockito). Cada PR passou por code review (GitHub Copilot) + auditoria OWASP + CI verde.

### Sprint de emissão avançada — concluída (issues #18–#22, PRs #35–#38)

- **#20** Validação de NCM e CFOP do agronegócio (RN008) — `CfopReferencia`/`NcmReferencia` + validação no `FiscalRulesValidator`.
- **#18** Geração de NFA (Nota Fiscal Avulsa do produtor rural) — `GeracaoNfaService`, `TipoDocumentoFiscal` (NFE/NFA), `DocumentoFiscalValidator` (CPF/CNPJ).
- **#19** Importação de XML de NF-e (UC002) — `ImportacaoNotaFiscalService`, `NfeXmlImporter` (XXE-safe), `NfeToNotaFiscalMapper`, `OrigemNotaFiscal` (PROPRIA/IMPORTADA); endpoint `POST /api/notas/importar`.
- **#21** Cancelamento de NF-e (RN006 — limite 30 dias) — `SefazService` cancelamento, `CancelamentoRequest/Response`, `CancelamentoForaPrazoException`; endpoint `POST /api/notas/{id}/cancelar`.

### Sprint 4 — créditos e conformidade (concluída)

- **#30/#31** Cálculo automático de IBS/CBS por operação e alíquota por estado (EC 132/2023 + LC 214/2025, RN002) — `CalculoIbsCbsService` + `TributosProperties`.
- **#41** Apuração mensal com fechamento automático (RN004) — `ApuracaoService`, `ApuracaoMensal`, job agendado; projeção ao vivo do mês corrente antes da consolidação (#53).
- **#42** Reabertura de período pelo contador — máx. 3 meses (RN005) — `ApuracaoNaoReabrivelException`/`ApuracaoPeriodoFechadoException`; guarda contra rebaixar período FECHADO fora do fluxo auditado (#54).
- **#40** Relatório de conformidade tributária (UC004) — `RelatorioConformidadeController` + PDF débito×crédito.
- Créditos por CFOP desacoplados de `ApuracaoMensal`; filtro de status AUTORIZADA no crédito (#51); `ReducaoNcmResolver` compartilhado débito×crédito corrige crédito superestimado (#52).

### Sprint 5 — dashboard real + integração com o Angular (concluída em 2026-07-06)

Épico Agrocontabil#34. Três sub-issues de backend, todas fechadas; PR final #69 mergeado.

- **#64** Recálculo de apuração por evento de domínio ao emitir/cancelar/importar nota — `ApuracaoService` recalcula **apenas períodos em ABERTO** (não reabre FECHADO); testes de integração de evento de domínio.
- **#65** Endpoint agregador `GET /api/dashboard` — `DashboardController`/`DashboardService` consolidam totais, distribuição de impostos e notas por mês para o frontend.
- **#66** Integração CORS + contrato de erro para o Angular (`:8080` → `:4200`):
  - `GlobalExceptionHandler` estendido — erros de infraestrutura HTTP (JSON malformado, tipo inválido no path, rota inexistente, método/Content-Type não suportado, upload acima do limite) respondem o envelope `ApiError.errors[]` com status **400/404/405/415/413** em vez de 500 genérico. Novos `ErrorCode`: `METHOD_NOT_ALLOWED`, `MEDIA_TYPE_NOT_SUPPORTED`, `PAYLOAD_TOO_LARGE`. Headers HTTP `Allow` (405) e `Accept` (415) preservados (RFC 7231 — regressão apontada pelo Copilot e corrigida).
  - `CorsConfig` validado e travado por `CorsIntegrationTest` (origem `localhost:4200`, métodos, credenciais, `Content-Disposition` exposto para downloads).
  - **Decisão do Calendário (revista pela issue #74):** a decisão original desta sprint era derivar o calendário **client-side no Angular**. Na prática o frontend (`CalendarioApi`) já chama `GET /calendario-fiscal/:produtorId/vencimentos` **sem fallback mock** (erro direto se 404) — a decisão não chegou a ser implementada no frontend. A issue #74 fecha essa lacuna no backend (ver abaixo).

### Issue #74 — guias fiscais do Contador + calendário fiscal (concluída)

Epico do painel do Contador (frontend#28). Cobre o registro de guias fiscais (ICMS/FUNRURAL/DARF/GPS/OUTRO) com vencimento e valor, e fecha a lacuna do `/api/calendario-fiscal` deixada em aberto na Sprint 5.

- **`GuiaFiscal`** (model) + migration `0018-guias-fiscais.xml`: tipo, vencimento, valor, status (PENDENTE/PAGA/VENCIDA, persistido) e data de pagamento.
- **`POST`/`GET /api/contador/guias`:** sem autenticação de Contador (entidade não existe no backend ainda) — `produtorId` explícito no payload/query; 404 se o produtor não existir. Débito técnico registrado para a Sprint 8 (RBAC produtor/contador).
- **`PATCH /api/contador/guias/{id}/pagar`:** ação do produtor autenticado — usa `AuthenticatedProdutorProvider` + `findByIdAndProdutorId` (anti-IDOR real, 404 para guia de outro produtor), reaproveitando a infraestrutura de auth mockada já existente. 409 se já paga.
- **`GuiaFiscalStatusSyncJob`:** job diário (`@Scheduled`, 1h, `America/Sao_Paulo`) que transiciona guias PENDENTE vencidas para VENCIDA — mesmo padrão do `SefazStatusSyncJob`/`ApuracaoFechamentoJob`.
- **`GET /api/calendario-fiscal/{produtorId}/vencimentos`:** endpoint que faltava e já quebrava a tela de Calendário do Angular. Reúsa `GuiaFiscalRepository` como única fonte de vencimento (`tipo="imposto"`); outras fontes (obrigações/declarações) ficam para quando o backend as modelar.
- **Nota de integração para o frontend:** `calendario.service.ts` hoje só reconhece `status === 'vencido'`; uma guia `pago` aparece como `pendente` no calendário até o frontend ajustar esse mapeamento — não é um bug do backend.

### Onda 2 — Correção fiscal (em andamento; spec em `.specs/features/onda-2-correcao-fiscal/`)

Ordem obrigatória #86 → #85 → #88, um PR por issue.

- **#86 Simetria débito×crédito (PR 1):** o crédito (`CreditoTributarioService`) usava a alíquota fixa do yml enquanto o débito (`TributacaoService`) resolvia por UF em `aliquota_ibs_uf` — coincidiam só pelo seed uniforme. Novo `AliquotaIbsCbsResolver` (`@Component`, padrão do `ReducaoNcmResolver`) é o **único** ponto de resolução de alíquota IBS (por UF, RN002) e CBS, consumido pelos dois serviços; devolve `AliquotasVigentes` (parcelas estadual/municipal, CBS, origem TABELA_UF/FALLBACK_YML). Nota importada usa a UF do destinatário (= produtor, princípio do destino — EC 132 art. 156-A §1.º VII); `TributosProperties` passa a ser apenas fallback conservador (+ `credito.percentualAproveitamento`). Teste de simetria `creditoUsaAliquotaPorUfDivergenteDoYml` foi escrito vermelho antes da correção.
- **#85 Vigência temporal das alíquotas (PR 2):** changeSet `0020-aliquota-vigencia.xml` — `aliquota_ibs_uf` ganha `data_inicio`/`data_fim` (NULL = aberta) com PK composta `(uf, data_inicio)` (`AliquotaIbsUfId`); as 27 linhas da 0013 migram via `defaultValueDate=2026-01-01` (0013 intacta). Nova tabela/entidade `AliquotaCbs` (`aliquota_cbs`, vigência 2026 = 0,0090) substitui o valor fixo do yml como fonte primária. `AliquotaIbsCbsResolver` seleciona a vigência pela `dataEmissao` em `America/Sao_Paulo` (`findVigente(uf, data)` / `findVigente(data)`, `ORDER BY data_inicio DESC` — sobreposição acidental → mais recente vence); data fora de vigência → fallback yml + WARN, sem lançar. `TributacaoService`/`CreditoTributarioService`/`ApuracaoService` sem diff (vigência entrou só no resolvedor; apuração soma valores persistidos). Espelho H2 em `schema-nota-fiscal-test.sql`.
- **#88 Emitente real + limites de nNF/série (PR 3):** changeSet `0021-produtor-endereco-ie.xml` — `Produtor` ganha `EnderecoProdutor` (`@Embeddable`, 7 campos) e `inscricaoEstadual`, todos nullable (obrigatoriedade é regra de emissão). `NotaFiscalNfeMapper` perde os placeholders do **emitente** (`SEM ENDERECO`, `S/N`, `cUF+99999`, `MUNICIPIO NAO INFORMADO`) e o fallback `0000000` do destinatário e **`cMunFG` passa a ser o município do emitente** (era o do destinatário — bug encontrado no caminho). `UF_CODE` vira `common/UfIbge` (`codigo()` devolve `Optional`, sem o default silencioso `"35"`, + `prefixoCoerente()`). `FiscalRulesValidator` exige IE (só para NFE — NFA mantém `ISENTO`), os 6 campos de endereço do emitente, **logradouro do destinatário** (o XSD exige `xLgr` com 2+ caracteres) e coerência IBGE×UF de emitente e destinatário (UF `EX` do exterior isenta nas duas camadas). Limites do layout com defesa em profundidade: `@Max(999_999_999)` em `numero` e `@Max(999)` em `serie` (400) + regra no validador (422, alcança notas importadas/legadas); `ChaveAcessoGenerator.zeroPad` **lança** em overflow em vez de truncar — o truncamento fazia o `nNF` da chave divergir do XML. Nova constraint de classe `@CodigoIbgeCoerenteComUf` no endereço do destinatário. `DataLoader` semeia Cuiabá/MT com IE.

### Onda 1 — Fundação de escalabilidade (concluída em 2026-09-22)

Primeira das 7 ondas do plano de 2026-09-21 (21 issues abertas). Só config, migration de índice, schema de teste e contrato de erro — nenhuma regra de negócio alterada. Spec e tasks em `.specs/features/onda-1-fundacao/`.

- **#90 FKs de produção no schema H2 (PR #104):** `schema-nota-fiscal-test.sql` ganhou as 4 FKs do Liquibase com as mesmas ações `ON DELETE` (`fk_nota_fiscal_produtor` RESTRICT, `fk_nota_fiscal_item_nota` CASCADE, `fk_apuracao_produtor`, `fk_guia_fiscal_produtor`), via `ALTER TABLE … ADD CONSTRAINT IF NOT EXISTS` — idempotente e válido tanto no `@DataJpaTest` (`ddl-auto=none`) quanto nos `@SpringBootTest` (`create-drop`, onde os `CREATE TABLE IF NOT EXISTS` viram no-op). Entidades **não** mudaram (seguem com `@ForeignKey(NO_CONSTRAINT)`). As FKs expuseram 6 classes de integração que só quebravam na suíte completa (H2 compartilhado entre contextos por `DB_CLOSE_DELAY=-1`): nasceu o `@TestComponent` **`TestDatabaseCleaner`** (`src/test/java/br/com/agrocontabil/support/`), que limpa filho→pai (validações → apurações → guias → notas → produtores) e é importado com `@Import` pelos 8 testes que apagam `produtores`. **Todo teste novo que apague produtores deve usá-lo.**
- **#102 Índices compostos (PR #108):** changeSet **0019** com `nota_fiscal (produtor_id, status, data_emissao)`, `guias_fiscais (status, data_vencimento)` e `guias_fiscais (produtor_id, data_vencimento)`, replicados no H2 e travados por `SchemaTestIndicesTest` (verifica nome **e ordem** das colunas via `INFORMATION_SCHEMA`). Medido com `EXPLAIN (ANALYZE, BUFFERS)` em Postgres com 200k notas/100k guias: dashboard 4265→605 buffers (7×), job de guias 1620→92 (17,6×).
- **#98 HikariCP (PR #109):** pool, `connection-timeout`, `max-lifetime`, `validation-timeout` e **`leak-detection-threshold` de 30s** no `application.yml` base, todos por env; `spring.transaction.default-timeout` de **30s** (não 10s — importação de lote, DANFE e fechamento mensal podem passar disso); `statement_timeout` do Postgres apenas em `application-dev/prod.yml` (**o driver H2 rejeita propriedades JDBC desconhecidas — nunca colocar no base**). `TransactionTimedOutException` já caía na rede de segurança do `GlobalExceptionHandler`; só ganhou teste.
- **#101 Scheduler e shutdown (PR #110):** `spring.task.scheduling` com pool de 3 (uma thread por job) e prefixo `agro-sched-`, `await-termination` de 30s, `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`, e `stop_grace_period: 35s` no compose (o default de 10s do Docker mataria o processo antes da janela). `SchedulingConfig` continua sem bean próprio — o `ThreadPoolTaskScheduler` é autoconfigurado pelas propriedades. Verificado com SIGTERM real via `docker stop`, com requisição em voo respondendo 200.
- **#89 (parte rápida — PR #111):** `GET /api/notas/{id}/xml` responde **409** (`XmlIndisponivelException` / `ErrorCode.XML_INDISPONIVEL`) quando a nota existe mas o XML não foi gerado, simétrico ao `/pdf`; 404 fica só para nota inexistente ou de outro produtor. **Mudança de contrato para o Angular.** A issue segue aberta para o preview de DANFE de rascunho.

Cobertura ao fim da onda: **393 testes**, todos verdes (inclui os PRs da Onda 2 ja mergeados em paralelo).

### Débitos técnicos abertos

- **Onda 1 — pendências deixadas conscientemente:**
  - `nota_fiscal_validacao.nota_fiscal_id` **não tem FK** — o changeSet 0006 não define nenhuma em produção; replicar no teste divergiria do schema real. Criar a FK é decisão em aberto.
  - `idx_guia_fiscal_produtor_vencimento` (0019) **só é usado com `LIMIT`**: na listagem completa o planner prefere o índice antigo + sort. Ele entrega valor (55× menos I/O) quando a **#103** (paginação das guias) for implementada.
  - `idx_nota_fiscal_produtor_id` (0002) ficou redundante para consultas por produtor; remover exigiria outro changeSet e outra decisão.
  - O leak-detection vai emitir `Connection leak detection triggered` para os vazamentos das issues **#97/#99** (Onda 3) — é o efeito pretendido, não regressão.
  - Lock distribuído dos jobs (`ShedLock`) é a issue **#87** (Onda 5): hoje a coordenação é por instância.
- **Sprint 7 (SEFAZ real):** `cNF` da chave via `SecureRandom` (hoje determinístico para teste). Substituir `MockSefazTransport` por transporte SOAP real + certificado **ICP-Brasil** em produção.
- **Sprint 8 (segurança/auth real):** mascarar CPF/CNPJ em logs (LGPD); lock pessimista contra geração concorrente; `IllegalStateException`→401 no `AuthenticatedProdutorProvider`. (Validação de dígitos verificadores de CPF/CNPJ já entregue na #18 via `DocumentoFiscalValidator`.) Inclui a entidade `Contador` (hoje inexistente) + vínculo Contador↔Produtor + RBAC produtor/contador — `POST`/`GET /api/contador/guias` (issue #74) aceitam `produtorId` explícito sem checagem de ownership até lá.
- **IE de produtor sem dígito verificador (#88):** não há algoritmo nacional padronizado de DV de Inscrição Estadual (cada UF tem o seu). Só o formato é validado; validação por UF fica para quando houver necessidade real.
- **Bairro do destinatário (#88):** `EnderecoDestinatario` não modela o bairro e o XSD exige `xBairro` no `enderDest` — o literal `"CENTRO"` permanece nesse grupo. Resolver modelando o campo no domínio e no DTO. (`"S/N"` em `nro` não é placeholder: é a convenção do layout para imóvel sem número.)
- **Tabela IBGE de municípios (Sprint 7):** a coerência município×UF é verificada só pelo prefixo (2 primeiros dígitos = cUF). A tabela completa dos 5.570 municípios validaria a existência do código.
- **Alíquotas 2027–2033 (Onda 2, #85):** só a vigência de 2026 está semeada em `aliquota_ibs_uf`/`aliquota_cbs`. Quando RFB/CONFAZ/Comitê Gestor publicarem as alíquotas da transição (LC 214/2025, arts. 87–92), inserir vigências por **novo changeSet com `insert`** (fechando a anterior com `data_fim`); nunca `update` em linha existente. Parcela municipal de IBS continua por UF (não por município).
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

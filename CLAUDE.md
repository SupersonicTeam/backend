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

Java 21 · Spring Boot 3.5.14 · Spring Web · Spring Data JPA · PostgreSQL 16 (+ pgvector) · Liquibase · Spring Security · Bean Validation · Spring Actuator · springdoc-openapi 2.8.x · Spring AI 1.1.8 (Ollama local + API compatível com a da OpenAI) · JUnit 5 + Mockito · Docker Compose.

> Spring AI **fixado na linha 1.1.x** (a 2.x exige Boot 4). Detalhes da Ceres em `docs/ceres-assistente.md`.

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
├── config/        # SecurityConfig, CorsConfig, OpenApiConfig, MockIdentidadeFilter,
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
├── assistente/    # Ceres, a assistente fiscal (Sprint 6). MÓDULO ISOLADO (issue #141, ArchUnit):
│                  # fala com o backend só pelas interfaces de porta/ (DadosProdutorPorta,
│                  # IdentidadePorta, AuditoriaPorta), implementadas em adaptador/ (os únicos que
│                  # chamam services; nunca repository). Nada fora do módulo depende dele, exceto
│                  # controller e config; nada nele é @Transactional. AssistenteService (entrada:
│                  # bulkhead global + 1 por produtor, pool agro-ceres-, timeout, SSE — #157),
│                  # auditoria/ (AuditoriaAssistente), orquestracao/ (ClassificadorIntencao +
│                  # OrquestradorService: DADOS/LEGISLACAO/MISTO/FORA_DE_ESCOPO), memoria/ (esqueleto).
│                  # llm/ (ModeloLinguagem = porta única
│                  # para o modelo, falha → 503 ASSISTENTE_INDISPONIVEL; OllamaHealthIndicator
│                  # → DEGRADED, nunca DOWN); ferramentas/ (FerramentasDadosProdutor: @Tool sem
│                  # parâmetro de produtor, produtor só pelo ContextoAssistente no ToolContext);
│                  # RotaDados (laço de funções do backend, com teto); ValidadorNumeros;
│                  # rag/ (FatiadorDispositivos, IngestaoLegislacaoService + runner no profile
│                  # "ingestao", BuscaLegislacaoService, ValidadorCitacao); RotaLegislacao
├── security/      # papéis e principal: PapelUsuario, UsuarioAutenticado(+Provider),
│                  # ProdutorEmContextoResolver, SecurityErrorResponder [#93]
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
| POST | `/api/contador/guias` | Cria guia fiscal (produtorId deve ser o do usuário autenticado; 404 se divergir) |
| GET | `/api/contador/guias?produtorId=&page=&size=` | Lista paginada (padrão 100, máx. 200) das guias do usuário autenticado (404 se o id for de outro); corpo array + total em `X-Total-Count` (#103) |
| PATCH | `/api/contador/guias/{id}/pagar` | Marca guia como paga (só papel PRODUTOR; contador recebe 403) |
| GET | `/api/calendario-fiscal/{produtorId}/vencimentos?de=&ate=` | Vencimentos fiscais do produtor na janela `de`/`ate` (padrão: mês corrente ±12 meses; máx. 36 meses) (#103) |
| POST | `/api/assistente/chat` | Pergunta à Ceres (`{pergunta}`, máx. 1000 caracteres); resposta `{resposta, rota, citacoes[], fontesDados[], modoFallback}`; rota decidida pelo orquestrador: DADOS/LEGISLACAO/MISTO/FORA_DE_ESCOPO (#157); só produtor (contador 403); 503 `ASSISTENTE_OCUPADO` + `Retry-After` sem vaga (limite global ou pergunta anterior do mesmo produtor em andamento), 503 `ASSISTENTE_TEMPO_ESGOTADO`, 503 `ASSISTENTE_INDISPONIVEL` com o modelo fora |
| POST | `/api/assistente/chat/stream` | Mesma pergunta em SSE (#157): eventos `rota` → `resposta` → `fim`, ou `erro` (envelope `errors[]`); sem tokens parciais (só a resposta já validada) |

## Segurança

- **Papéis reais, login ainda não (Onda 7, issue #93).** O `SecurityConfig` **não usa mais `permitAll()`**: a cadeia exige identidade em todo `/api/**` (público apenas health, OpenAPI e Actuator) e o token carrega a authority do papel — `ROLE_PRODUTOR` ou `ROLE_CONTADOR`. O que falta para "auth de verdade" é o login: cadastro, JWT e recuperação de senha são issues próprias.
  - **Principal:** `security/UsuarioAutenticado` (papel + produtor **ou** contador), servido por `UsuarioAutenticadoProvider`. `AuthenticatedProdutorProvider` continua como atalho para os ~9 services que só fazem sentido no contexto de um produtor — um contador ali recebe **403** `PAPEL_NAO_AUTORIZADO` (não 401: ele está autenticado; o que falta é o produtor-alvo).
  - **Produtor-alvo:** `security/ProdutorEmContextoResolver` é o ponto único de autorização dos endpoints que nomeiam um `produtorId`. Produtor só alcança a si mesmo; contador alcança os produtores da carteira (`contador_produtor`). Nega com **404**, nunca 403.
  - **Negativa da cadeia de filtros:** `security/SecurityErrorResponder` escreve o mesmo envelope `ApiError.errors[]` (401 com `WWW-Authenticate`, 403 `PAPEL_NAO_AUTORIZADO`) — sem ele o cliente receberia a página de erro do container, porque filtro roda antes do `@RestControllerAdvice`.
- **Matriz endpoint × papel:**

  | Rota | Produtor | Contador |
  |------|----------|----------|
  | `/api/v1/health`, `/v3/api-docs`, `/swagger-ui`, `/actuator` | público | público |
  | `/api/notas/**`, `/api/dashboard`, `/api/apuracao/**`, `/api/creditos`, `/api/relatorios/**`, `/api/assistente/**` | próprios dados | **403** no service — esses endpoints ainda não recebem produtor-alvo |
  | `POST`/`GET /api/contador/guias` | próprias guias (404 para outro produtor) | guias dos produtores da carteira (404 fora dela) |
  | `PATCH /api/contador/guias/{id}/pagar` | sim (anti-IDOR 404) | **403** — quitar a guia é ato do produtor |
  | `GET /api/calendario-fiscal/{produtorId}/vencimentos` | próprio calendário | calendário dos produtores da carteira |
- **Identidade por requisição via header `X-Usuario-Id` (Onda 4, issue #91; papéis na #93).** O `MockIdentidadeFilter` (profiles **`dev` e `homolog`**, nunca `prod`) resolve a cada requisição a identidade cujo **UUID** vem nesse header — **produtor ou contador**, buscados nessa ordem. O header **declara** a identidade, não a prova — é mock multi-usuário, não autenticação; serve para exercitar o anti-IDOR e o escopo por papel até o login real chegar. Contrato:
  - **sem header** → produtor semente (o produtor A do `DataLoader`), para o Angular atual não quebrar. O fallback é sempre **produtor**: cair em contador por omissão trocaria as permissões da requisição;
  - **UUID de produtor existente** → esse produtor, papel `PRODUTOR`;
  - **UUID de contador existente** → esse contador, papel `CONTADOR`;
  - **UUID malformado ou inexistente** → **401 `NAO_AUTENTICADO`** (não 404: não confirma quais ids existem);
  - **fora de `dev`/`homolog`** → filtro ausente, nenhuma identidade, **401** em todo endpoint que dependa dela (antes da issue #92 era 500).
  - O 401 nasce na cadeia (`SecurityErrorResponder`) ou no provider, nunca em exceção lançada dentro do filtro: exceção lançada em filtro não passa pelo `@RestControllerAdvice` e escaparia do envelope `errors[]`. Efeito colateral aceito: endpoint sem identidade (health, Swagger) responde normal mesmo com header inválido.
- **Ownership nas guias fiscais (issues #94 e #93).** `POST`/`GET /api/contador/guias` e o calendário tratam o `produtorId` do payload/query/path como afirmação do cliente e a submetem ao `ProdutorEmContextoResolver` antes de ler ou gravar; não autorizado, **404**. No `POST` a decisão vem antes da escrita, então nada é gravado.
- **Ownership (anti-IDOR) já aplicado.** Toda operação sobre nota fiscal usa `findByIdAndProdutorId` e responde **404** (não 403) para nota de outro produtor — não revela existência. Mensagens de erro de geração de XML são genéricas ao cliente (detalhe técnico só no log).
- **Nenhum segredo no repositório.** Senha de banco e JWT secret vêm de env var (`${DB_PASSWORD}`, `${JWT_SECRET}`). Ver `.env.example`.
- **CORS** controlado por `app.cors.allowed-origins` (env `APP_CORS_ALLOWED_ORIGINS`). Nunca usar `*` em prod.
- **Logs nunca contêm senha/token.** Ao logar requisições ou erros, não incluir credenciais.
- **Ceres (assistente fiscal, Sprint 6):** tudo que vai ao modelo passa pelo `DataSanitizer`
  (pergunta e resultado de toda função); DTOs das funções sem campo pessoal; auditoria em
  `assistente_interacao` **sem o texto da pergunta nem da resposta** (changeSet 0025). Isolamento
  de recursos (#157): nenhuma transação durante a chamada ao modelo, bulkhead (503
  `ASSISTENTE_OCUPADO` + `Retry-After`), pool próprio e timeouts por pergunta e por função.
  Detalhes em `docs/ceres-assistente.md`.
- **Erro para cliente SSE sai em JSON:** o envelope acompanha o `Accept` (JSON ou XML), mas para
  cliente que pede `Accept: text/event-stream` a negociação falhava dentro do próprio handler e
  virava 500; para ele o `GlobalExceptionHandler.tipoDoErro()` fixa `application/json` (#157).

### Nota de integração para o frontend (papéis — issue #93)

- **Nada muda para o produtor.** Sem header, ou com `X-Usuario-Id` de produtor, todos os endpoints respondem como antes.
- **Para agir como contador**, envie `X-Usuario-Id` com o **UUID do contador** (o `DataLoader` loga o id na subida, em dev/homolog). Ele alcança `GET`/`POST /api/contador/guias` e `GET /api/calendario-fiscal/{produtorId}/vencimentos` **somente** dos produtores vinculados; fora da carteira a resposta é **404** — por design, para não revelar a existência do produtor.
- **Dois erros novos a tratar:** `401 NAO_AUTENTICADO` agora também pode vir da própria cadeia de filtros (mesmo envelope `errors[]`, com `WWW-Authenticate`); `403 PAPEL_NAO_AUTORIZADO` aparece quando o papel não faz aquela operação — contador em `PATCH /api/contador/guias/{id}/pagar`, ou contador em `/api/dashboard`, `/api/apuracao`, `/api/creditos` e `/api/relatorios`, que ainda derivam tudo do produtor autenticado.
- **Rotas públicas:** `/api/v1/health`, `/v3/api-docs`, `/swagger-ui`, `/actuator`.

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
- **Arquitetura travada por ArchUnit (issue #141):** `ArquiteturaCamadasTest` (Controller → Service → Repository no projeto inteiro, `FreezingArchRule` com linha de base em `src/test/resources/archunit_store` — hoje vazia) e `ArquiteturaAssistenteTest` (fronteira do módulo da Ceres). Violação nova quebra o build.
- Mockito com strict stubs: não criar stub de objeto usado só após early return.
- **Cobertura atual: 856 testes, todos passando** (JUnit 5 + Mockito; `./mvnw test`). Com Docker local, os testes contra PostgreSQL 16 (Testcontainers: Liquibase e pgvector) também rodam; sem Docker ficam *skipped*.
- **JaCoCo 0.8.15** (Onda 4): a 0.8.12 abortava com `Unsupported class file major version` ao instrumentar os mocks que o Byte Buddy gera em runtime — eles nascem na versão do JDK em execução, não no `release 21` do compilador. Qualquer teste com Mockito falhava em JDK novo.

## Estado atual — Sprint 6 concluída (2026-09-30)

> Histórico das sprints anteriores abaixo (Sprint 0 → Sprint 3); Sprints 4 e 5, as Ondas e a Sprint 6 (Ceres) no fim desta seção.

**Sprint 0** (fundação): pom fixado, estrutura de pacotes, `SecurityConfig`/`CorsConfig`/`OpenApiConfig`, `GlobalExceptionHandler` + `ApiError`, `HealthController`, configs por profile, Docker Compose, baseline do Liquibase, `MockIdentidadeFilter` (auth mockada por produtor até a Sprint 8) e `DataLoader` (seed).

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

### Onda 3 — Concorrência e escopo transacional (em andamento; spec em `.specs/features/onda-3-concorrencia/`)

Ordem obrigatória #95 → #96 → #99 + #97. Spec aprovado em 2026-09-23.

- **#95 Lock otimista (PR #115, mergeado):** `NotaFiscal` ganha `@Version` (changeSet **0022**, `BIGINT NOT NULL DEFAULT 0`). `OptimisticLockingFailureException` → **409** com `ErrorCode.CONFLITO_CONCORRENCIA`, mensagem de ação ("recarregue e tente de novo") sem detalhe do ORM. Só `NotaFiscal` recebe `@Version`: é a única entidade com transições concorrentes por requisição HTTP (decisão D-4 do spec, justificada no Javadoc do campo). Nasceu aqui o **`LiquibaseChangelogIntegrationTest`** — sobe PostgreSQL 16 via Testcontainers, aplica o changelog inteiro e valida com `ddl-auto: validate`. **Era a primeira vez que uma migration era executada por teste** (o profile `test` desabilita o Liquibase); é ignorado sem Docker e roda na CI. **Toda migration nova deve ganhar asserção nesse arquivo.**
- **#96 Lock e escopo transacional na transmissão (PR #119):** `SefazService` **deixa de ser `@Transactional` na classe**. `enviar`/`cancelar` rodam em **três transações curtas** via `TransactionTemplate` (padrão do `ImportacaoNotaFiscalService` — `@Transactional` por método seria ignorado em silêncio na autoinvocação): reserva sob lock pessimista (`findByIdAndProdutorIdForUpdate`), I/O da SEFAZ **fora de transação**, desfecho + evento. Novos estados transitórios **`ENVIANDO`/`CANCELANDO`** (sem changeSet — `status` é `VARCHAR(20)` sem CHECK). Compensação assimétrica: falha no envio → `PENDENTE_SEFAZ` (a consulta pela chave estabelece a verdade); falha no cancelamento → `AUTORIZADA` (`mapStatus` não conhece `CANCELADA` e o job reautorizaria a nota — regressão da #79). `SefazStatusSyncJob` reconcilia notas presas além de `app.nfe.sefaz.transmissao.timeout-ms` (10 min, corte pelo `updatedAt` do `@PreUpdate`). `sincronizarStatus`/`sincronizarPendentes` adotam o mesmo desenho de três fases. **`MockSefazTransport` passa a lembrar as chaves transmitidas** e responde cStat **217** para chave desconhecida: antes autorizava qualquer chave, e uma nota que falhou antes de chegar ao fisco era "autorizada" pelo job, publicando evento de débito sobre NF-e inexistente. Nova `CancelamentoRejeitadoException` (409, `CANCELAMENTO_REJEITADO`) com cStat/`xMotivo` do fisco.
  - **Achado que vale lembrar:** com a I/O fora da transação, o `@Version` da #95 já impede a transmissão dupla (barra a segunda thread no commit da **reserva**, antes da rede). O lock pessimista agrega a **semântica do erro** — `NotaFiscalNaoEnviavelException` em vez do 409 genérico de concorrência. Os testes fixam esse tipo de propósito; aceitar o genérico fazia a falsificação passar.
  - **Nota de integração para o frontend (decidida com o Murillo em 2026-09-24):** `ENVIANDO` e `CANCELANDO` são estados **curtos e não-operáveis** (toda operação responde 409 enquanto duram). O Angular deve tratar **status desconhecido como "em processamento"** e desabilitar as ações da nota — abordagem genérica, que já cobre estados transitórios futuros sem precisar de novo deploy do frontend. Sem isso, uma tela que receba esses valores mostra estado vazio.
- **#99 + #97 Escopo transacional da emissão e fim do aninhamento (PR 3):** `NotaFiscalService` **deixa de ser `@Transactional` na classe**. `gerarXml` roda em **três fases** via `TransactionTemplate` — tx curta `readOnly` (ownership + `STATUS_EDITAVEIS` + carga do grafo), **assinatura RSA e XSD fora de transação**, tx curta de escrita que recarrega sob lock, **recheca o status** e persiste. `gerarPdf` tem só a fase de leitura (gerar DANFE não altera a nota). Os métodos CRUD seguem com `@Transactional` declarativo.
  - **Divergência deliberada do spec (RF-99.1):** a materialização é por **`JOIN FETCH` de itens e produtor** (`findByIdAndProdutorIdComGrafoDeEmissao`), não pelo DTO `NotaFiscalSnapshot` que o spec descrevia. A entidade sai da tx1 **destacada mas completa** e o pipeline a percorre sem tocar em proxy — mesmo efeito, sem trocar a assinatura de `FiscalRulesValidator`, `NotaFiscalNfeMapper`, `GeracaoNotaFiscalService`, `GeracaoNfaService` e `DanfePdfService`. Os dois primeiros são onde vivem a regra fiscal e o layout do XML; mexer neles no maior PR da onda trocaria um problema de pool por risco de regressão fiscal, e os 32 testes que os cobrem seguem valendo. **Ao acrescentar acesso a relação LAZY no pipeline de emissão ou DANFE, inclua-a no `JOIN FETCH`** — fora da sessão o acesso falha, e o teste de nota completa existe para pegar isso.
  - **#97 é consequência da #99:** `registrarInconsistencias` deixa de ser `REQUIRES_NEW` e passa a `@Transactional` simples. Ele exigia **duas conexões simultâneas** (a externa presa + a nova), e dez requisições de nota inválida travavam o pool. Com a validação fora de qualquer transação não há rollback a sobreviver, então uma transação única basta. **Invariante nova, documentada no Javadoc:** o método só pode ser chamado fora de transação; quem precisar chamá-lo de dentro deve extrair a chamada, não reintroduzir `REQUIRES_NEW`.
  - `NotaFiscalEscopoTransacionalIntegrationTest` roda com **pool de 1 conexão** e `connection-timeout` curto (exaustão falha rápido em vez de pendurar a suíte). Falsificado repondo o defeito: os testes de pool falham com `Connection is not available, request timed out`.

### Onda 4 — Identidade multi-usuário sem JWT (concluída em 2026-09-24)

Quarta das 7 ondas do plano de 2026-09-21. Spec, design, tasks e sprint em `.specs/features/onda-4-identidade/`. Ordem obrigatória #91 → #94 → #92, um PR por issue.

- **#91 Identidade por requisição (PR 1):** o `MockIdentidadeFilter` deixou de injetar sempre o mesmo produtor cacheado (`volatile cachedProdutor`, removido) e passou a resolver o UUID declarado em **`X-Usuario-Id`** a cada requisição; sem header, cai no produtor semente. Header malformado ou de produtor inexistente **não** cai no fallback — mascarar isso esconderia bug de integração do cliente. O contexto de segurança é limpo em `finally`: o Tomcat reusa threads do pool e a identidade vazaria para a requisição seguinte (tem teste). O `DataLoader` passou a semear **dois** produtores (A = semente, B = "o outro dono"), ambos com IE e endereço próprios de municípios diferentes — desde a #88 isso é pré-condição da emissão, e valores distintos fazem uma troca de produtor aparecer no XML. `CorsIntegrationTest` trava o preflight do header (a `CorsConfig` usa `allowedHeaders("*")`, então já passava; o teste impede que um aperto futuro quebre o Angular em silêncio).
- **#94 Ownership nas guias fiscais (PR 2):** `POST`/`GET /api/contador/guias` aceitavam **qualquer** `produtorId` — a criação só checava existência e a listagem devolvia as guias de quem fosse informado. Novo guard `exigirProdutorAutenticado` (mesmo do `CalendarioFiscalService`) confronta o id com o produtor autenticado antes de qualquer leitura/escrita → **404**, nunca 403. No `POST` o guard vem antes da consulta, então nada é gravado. Dois testes antigos mudaram de significado e foram reescritos: o `fluxoCompleto` chamava `criar`/`listar` **sem autenticar ninguém** (passava por não haver ownership) e o `criarLancaNotFoundParaProdutorInexistente` testava o 404 com id aleatório — caminho que agora morre no guard, antes do banco; foi reapontado para o único cenário que ainda exercita o 404 real (produtor autenticado apagado do banco).
- **#92 401 honesto + profile `homolog` (PR 3):** `NaoAutenticadoException extends DomainException` (401) + `ErrorCode.NAO_AUTENTICADO`; o `AuthenticatedProdutorProvider` troca `IllegalStateException` por ela e o `GlobalExceptionHandler` **não** ganhou handler novo (`DomainException` já era tratado). O 401 nasce no provider, não no filtro — exceção lançada em filtro roda antes do `DispatcherServlet` e escaparia do envelope `errors[]`. Filtro e `DataLoader` passam a `@Profile({"dev","homolog"})` e nasce o `application-homolog.yml`, espelhando o prod pós-#98 (pool no base, `statement_timeout` no profile) e diferindo só no seed e na verbosidade de log. **`health.show-details: never` em homolog** (achado do security review): o base usa `when-authorized`, que ali não protege nada — o filtro autentica toda requisição sem header como o produtor semente, então "autorizado" viraria "qualquer visitante" e o health detalhado ficaria aberto.
- **Build:** JaCoCo 0.8.12 → **0.8.15**, que destrava `./mvnw test` em JDK 26 (ver seção Testes).

**Nota de integração para o frontend Angular:**
- Header **`X-Usuario-Id`** = UUID do produtor — o **mesmo** id que o frontend já passa em `/api/calendario-fiscal/:produtorId/vencimentos` e no payload de `/api/contador/guias`.
- **Nada quebra hoje:** sem o header, o backend responde como o produtor semente. A adoção é opt-in.
- Ao adotar, enviar em **todas** as chamadas (interceptor HTTP) — inclusive nas de `/api/contador/guias`, onde o `produtorId` do payload/query precisa **bater** com o do header, sob pena de 404.
- Novo status possível: **401** com `errors[0].code == "NAO_AUTENTICADO"` (header presente e inválido, ou ambiente sem identidade). Tratar como sessão inválida — é o mesmo 401 que o JWT devolverá na Sprint 8.

### Onda 5 — Jobs e volume (concluída em 2026-09-25; spec em `.specs/features/onda-5-jobs-volume/`)

Quinta das 7 ondas. Ordem #100 → #87 → #103, um PR por issue.

- **#100 Fechamento da apuração por produtor (PR 1):** `fecharPeriodo` fazia `findAll()` de todos os produtores e consolidava tudo numa **única** transação, de modo que um produtor inconsistente desfazia o fechamento de todos. Agora `fecharPeriodo` e `refecharPeriodosReabertos` são `NOT_SUPPORTED`: leem só IDs (produtores em lotes por **keyset**, `app.apuracao.fechamento.lote-tamanho`, padrão 100) e fecham cada item num `TransactionTemplate` **`REQUIRES_NEW`** com o timeout padrão. A falha de um item faz rollback só dele, é logada em `ERROR` e entra no `ResumoLoteApuracao` (concluídas + IDs com falha), que o `ApuracaoFechamentoJob` loga. A propriedade `app.apuracao.fechamento.transaction-timeout` saiu: não há mais transação de lote. RN004/RN005/#54/#64 inalterados. Os testes que passam pelo fechamento vivem em `ApuracaoFechamentoIntegrationTest`, **sem** `@Transactional` de classe: numa transação de teste o `REQUIRES_NEW` não enxergaria os dados do teste e o rollback esconderia justamente os commits que o teste precisa provar. A falha é injetada com `@MockitoSpyBean` no `NotaFiscalRepository`. Consolidação por SQL agregado (item 3 da issue) ficou de fora de propósito; ver o spec.
  - **Achado do review:** a corrida listener de evento (#64) × fechamento podia regravar `ABERTO` por cima de um período recém-`FECHADO` (a guarda da #54 via a cópia velha). Todo caminho que **grava** `ApuracaoMensal` (consolidar/fechar, listener, refechar, reabrir) agora lê a linha com **lock pessimista** (`...ForUpdate`). Não é `@Version` de propósito: com lock otimista, dois listeners concorrentes do mesmo produtor fariam um recálculo falhar e se perder; com o lock eles se serializam e o segundo relê o estado commitado. O refechamento reconfere `ABERTO` sob lock, e os dois laços param entre itens se a thread for interrompida (shutdown, #101).
- **#87 Lock distribuído com ShedLock (PR 2):** com 2+ instâncias os três jobs rodavam em dobro. `shedlock-spring` + `shedlock-provider-jdbc-template` **6.10.0** (a 7.x compila contra Spring 7 / Boot 4). `SchedulingConfig` ganhou `@EnableSchedulerLock(order = HIGHEST_PRECEDENCE)` e o bean `LockProvider`: `JdbcTemplateLockProvider` com `usingDbTime()` (relógio do banco, imune a desvio entre réplicas) e `withThrowUnexpectedException(true)` (erro de storage sobe ao scheduler em vez de pular o job em silêncio), envolvido num **`KeepAliveLockProvider`** que renova o lock enquanto o job roda — a duração não é previsível (a sincronização SEFAZ faz uma chamada com retry por nota), e um `lockAtMostFor` fixo expiraria no meio, deixando outra réplica consultar as mesmas chaves (risco de cStat 656). O teto passa a valer só para réplica morta. **O executor do keep-alive não é bean de propósito:** um `ScheduledExecutorService` no contexto viraria o scheduler do `@EnableScheduling` e desligaria o pool da #101 (o `SchedulingConfigTest` pega isso). O `order` importa: o default do ShedLock empata com o advice transacional e a ordem fica indefinida; com o lock por dentro, ele seria liberado antes do commit do `GuiaFiscalStatusSyncJob`. Cada job tem `@SchedulerLock` com nome na constante `LOCK` e durações por propriedade (`app.<job>.lock.at-most-for`/`at-least-for`): SEFAZ `PT14M`/`PT10M` (cada réplica agenda o próprio `fixedDelay`, o piso impede sincronizar de novo logo depois da outra), fechamento `PT50M`/`PT5M`, guias `PT30M`/`PT5M` (o piso cobre o desvio de relógio entre réplicas que disparam o mesmo cron). Tabela `shedlock` no changeSet `0023` e em `schema-shedlock-test.sql` (não é entidade: o `create-drop` do H2 não a cria). `SchedulerLockConfigTest` exige `@SchedulerLock` em **todo** `@Scheduled` dos jobs — um job novo sem lock quebra o teste. **Armadilha de teste:** o `StorageBasedLockProvider` guarda em memória os nomes que já inseriu e, para eles, só tenta `UPDATE`; apagar a linha da `shedlock` no meio da suíte torna o lock impossível. Para resetar, expire (`UPDATE ... SET lock_until = <passado>`), nunca `DELETE`.
- **#103 Guias paginadas e calendário por janela (PR 3):** `GuiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc` devolvia **todas** as guias do produtor e foi removido. `GET /api/contador/guias` aceita `page` (padrão 0) e `size` (padrão 100, 1..200, Bean Validation com `@Validated` no controller) e ordena por `dataVencimento DESC, id DESC` (o desempate impede guia pulada ou repetida entre páginas). `GET /vencimentos` aceita `de`/`ate` (`YYYY-MM-DD`, inclusivos): ausentes, a janela é do 1º dia do mês corrente −12 meses ao último dia do mês corrente +12 meses (`Clock` do projeto); `de > ate` ou janela acima de 36 meses → **400 `JANELA_CALENDARIO_INVALIDA`** (`JanelaCalendarioInvalidaException`). Sem changeSet: o índice `(produtor_id, data_vencimento)` já existia (#102). **Decisão de compatibilidade:** o corpo dos dois endpoints **continua array**, porque o Angular lê `GuiaFiscal[]`/`CalendarioVencimento[]` e navega os meses do calendário no cliente depois de uma carga só; trocar para `Page` quebraria as duas telas. O total vai no header **`X-Total-Count`**, exposto no `CorsConfig`. Testes de calendário com data fixa usam janela explícita: a padrão é relativa a hoje e viraria bomba-relógio.
  - **Achados do review:** `page` ganhou teto (`PAGINA_MAXIMA` = 10.000) — sem ele `page × size` passava de `Integer.MAX_VALUE` e o Spring Data falhava ao converter o offset para o `setFirstResult(int)` do JPA, devolvendo 500 para um erro do cliente. A checagem da janela usa `inicio.until(fim, MONTHS)` em vez de `plusMonths`, que estourava com datas extremas (500) e rejeitava janelas de 36 meses iniciadas no fim do mês (29/02). A consulta da janela virou `@Query` `findVencimentosNaJanela`.

**Nota de integração para o frontend Angular (Onda 5):**
- **Nada quebra:** os dois endpoints seguem devolvendo array.
- `ContadorApi.getGuias`: no máximo 100 guias por chamada, das mais recentes para as mais antigas. Para paginar, envie `page`/`size` (máx. 200) e leia o total em `X-Total-Count`.
- `CalendarioApi.getVencimentos`: sem parâmetros vêm os vencimentos do mês corrente −12 a +12 meses. Ao navegar para fora dessa faixa, envie `de`/`ate` (janela máxima de 36 meses).
- Novos 400 no envelope `errors[]`: `JANELA_CALENDARIO_INVALIDA` (campos `de`/`ate`) e `VALIDATION_ERROR` para `page`/`size` fora da faixa.

### Onda 6 — Sprint 7, SEFAZ real (spec em `.specs/features/onda-6-sefaz-real/`)

Prepara o `SefazService` para o transporte SOAP real. Issues **#84** (PR #124), **#125** (PR #126, achado da onda), **#83** (PRs #127 e #130) e **#129**. Concluída em 2026-09-25; o transporte SOAP real ficou fora (issue #128).

- **Cancelamento recusado (#84):** `CancelamentoRejeitadoException` responde **422** `CANCELAMENTO_REJEITADO` com `rejectedValue` = cStat e `message` = xMotivo do fisco, sem prefixo.
- **Retry pela idempotência (#83):** `SefazComunicacaoException` ganhou `SefazConexaoException` (a requisição não saiu → retry seguro) e `SefazRespostaIndeterminadaException` (pode ter sido processada → nunca re-tentar). `SefazClient.enviar`/`cancelar` só re-tentam conexão; `consultarStatus` re-tenta qualquer falha. Instância direta da base conta como indeterminada.
- **Reconciliação pela chave (#83):** resposta indeterminada, cStat 204 (duplicidade de NF-e) no envio ou 218/420/573 (já cancelada) no cancelamento disparam uma consulta pela chave antes de decidir; nunca reenvio cego. Falha de conexão devolve a nota a `XML_GERADO`. `SituacaoSefaz` ganhou `NAO_ENCONTRADA` (217 → pendente volta a `XML_GERADO`, exceto depois de um 204) e `CANCELADA` (101/151). A reserva grava o ambiente antes da rede. Máquinas de estado no spec.
- **Sync paginado com disjuntor (#83):** `sincronizarPendentes` lê só IDs por keyset (`app.nfe.sefaz.sync.lote-tamanho`), processa cada nota isolada e para após `max-falhas-consecutivas` falhas de comunicação seguidas.
- **Timeouts e invariante (#83):** `app.nfe.sefaz.timeout.connect-ms`/`read-ms`. A aplicação **não sobe** se `transmissao.timeout-ms` não superar o pior caso de uma transmissão (envio + consulta de reconciliação, com retry).
- **`application.yml` (#125):** o bloco `server:` (PR #110) estava no meio de `spring:` e desligava em silêncio `open-in-view: false`, `ddl-auto: validate`, `default_batch_fetch_size` (#81), `jdbc.time_zone` e os limites de multipart. Corrigido e travado por `ApplicationYamlEstruturaTest`, que varre todos os `application*.yml`.
- **`cNF` aleatório (#129):** `ChaveAcessoGenerator` sorteia o `cNF` com `SecureRandom` (antes derivado de número/série, o que permitia deduzir a chave de acesso) e evita as formas que a SEFAZ recusa (rejeição 897: igual ao `nNF`, dígitos iguais, sequências triviais).
- **Testes de referência:** `SefazEntregaIncertaIntegrationTest` (HTTP ponta a ponta com o transporte espiado; inclui a prova de que nenhuma chamada à SEFAZ acontece com transação ativa ou conexão do pool presa), `SefazClientRetryTest`, `SefazServiceReconciliacaoTest`, `SefazServiceSincronizacaoTest`, `SefazPropertiesTest`.

### Sprint 6 — Ceres, a assistente fiscal, Fase 1 (concluída em 2026-09-30)

Issues #141–#144, #156 e #157; PRs #159–#165, #168 e #171. Desenho completo em `docs/ceres-assistente.md`.

- **Infra (#141):** Spring AI 1.1.8, Ollama local (`qwen3:4b-instruct` + embedding `bge-m3`) ou provedor por API compatível com a OpenAI. Falha do modelo é sempre 503 `ASSISTENTE_INDISPONIVEL`; health do Ollama `DEGRADED`, nunca `DOWN`. **Módulo isolado** travado por ArchUnit (portas e adaptadores, nada `@Transactional`).
- **Tool Calling (#142, #143):** cinco funções de dados sem parâmetro de produtor (o produtor vem do `ContextoAssistente` no `ToolContext`); laço de funções do backend com teto; `ValidadorNumeros` descarta resposta com número sem fonte.
- **Sanitização e auditoria (#144):** `DataSanitizer` na pergunta e em todo resultado de função; `assistente_interacao` sem o texto da pergunta nem da resposta.
- **RAG (#156):** `legislacao_trecho` (pgvector), um trecho por dispositivo, citação obrigatória validada em código. **Base ingerida:** LC 214/2025 compilada (5.615 trechos, 907 itens de anexo), EC 132/2023 (538) e LC 227/2026 (2.211) — 8.364 trechos, textos compilados capturados do Planalto em 2026-09-30. Comando de ingestão só pelo profile `ingestao` (nunca endpoint).
- **Orquestração e streaming (#157):** classificador de intenção → DADOS / LEGISLACAO / MISTO / FORA_DE_ESCOPO; `POST /api/assistente/chat` e `/chat/stream` (SSE, sem tokens parciais). Bulkhead (global + 1 por produtor, 503 `ASSISTENTE_OCUPADO` + `Retry-After`), pool `agro-ceres-`, timeouts por pergunta e por função, provado sem conexão presa por teste com pool de 1 conexão.
- **Achados da execução real (#165, #168):** a LC 214 não declara charset (windows-1252); o comando de ingestão não subia sem servidor web (`SecurityConfig` agora `@ConditionalOnWebApplication`); o último artigo engolia os anexos (91.820 caracteres); na LC 227 o Planalto omite a aspa antes de um artigo citado e 48 trechos saíam como artigos dela (#171); a tag `qwen3:4b` virou a variante *thinking* (ignora `think=false`, 80–120 s por classificação) — padrão trocado para `qwen3:4b-instruct`.
- **Teste de ponta a ponta (2026-09-30, CPU):** fora de escopo 4 s, dados 24 s, legislação 45 s, misto 41 s. Qualidade do MISTO e perguntas de soma viraram #169 e #170 (Sprint 7).

### Débitos técnicos abertos

- **Ceres (Sprint 6):**
  - **Vigência por dispositivo:** cada norma entra com uma vigência só. Os dispositivos da LC 214 com redação dada pela LC 227/2026 aparecem vigentes desde 16/01/2025; pergunta sobre data anterior a 2026 receberia a redação atual. Derivar a vigência das anotações "(Redação dada pela...)" resolve.
  - **Anexo XVIII da LC 214** (tabelas do Simples Nacional, sem itens numerados) não vira trecho.
  - Contrato do chat ainda não consumido pelo Angular (SupersonicTeam/Agrocontabil#40).

- **Onda 1 — pendências deixadas conscientemente:**
  - `nota_fiscal_validacao.nota_fiscal_id` **não tem FK** — o changeSet 0006 não define nenhuma em produção; replicar no teste divergiria do schema real. Criar a FK é decisão em aberto.
  - `idx_guia_fiscal_produtor_vencimento` (0019) **só é usado com `LIMIT`**: na listagem completa o planner prefere o índice antigo + sort. Ele entrega valor (55× menos I/O) quando a **#103** (paginação das guias) for implementada.
  - `idx_nota_fiscal_produtor_id` (0002) ficou redundante para consultas por produtor; remover exigiria outro changeSet e outra decisão.
  - Lock distribuído dos jobs (`ShedLock`) entregue na Onda 5, issue **#87**.
- **Sprint 7 (SEFAZ real):** Transporte SOAP + mTLS real com certificado **ICP-Brasil** — issue **#128**; o contrato que ele deve cumprir (classificação de falhas, mapeamento de cStat da consulta, timeouts) está no javadoc de `SefazTransport` e de `SefazRetorno`.
- **Sprint 8 — o que a #93 NÃO cobriu:** login e cadastro, **JWT**, recuperação de senha e rate limiting continuam abertos. A #93 entregou papéis, entidade `Contador`, vínculo e RBAC, mas a identidade ainda é *declarada* no header, não provada. Também abertos: mascarar CPF/CNPJ em logs (LGPD) e lock pessimista contra geração concorrente. (DV de CPF/CNPJ entregue na #18 via `DocumentoFiscalValidator`; `IllegalStateException`→401 entregue na #92.)
- **Contador ainda não alcança dashboard/apuração/relatório (#93):** esses endpoints não recebem `produtorId` — derivam tudo do produtor autenticado, e um contador ali recebe 403. Liberá-los exige decidir *como* o produtor-alvo entra na requisição (padronizar `produtorId` no path, como no calendário) e ajustar o Angular; fica para a issue do painel do Contador.
- **Identidade declarada, não provada (Onda 4):** o header `X-Usuario-Id` não é verificado — qualquer cliente pode afirmar ser qualquer produtor. É aceitável porque só existe em `dev`/`homolog`, **nunca em prod**, e some quando o JWT entrar (issue própria de login). Homologação, portanto, não deve receber dados reais de produtor.
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
6. **Extrair a Ceres (módulo `assistente`) como serviço separado — só sob critério.** Decisão de 2026-09-29: hoje ela é módulo do monólito, e só a inferência roda à parte (Ollama; serviço Python dos classificadores na fase 2). A fronteira é travada pelo `ArquiteturaAssistenteTest` (backend#141): o módulo só fala com o resto do backend pelas interfaces de `assistente.porta`, implementadas em `assistente.adaptador`. Extrair = trocar o adaptador por um cliente HTTP. **Gatilhos:** (a) o chat precisar escalar independente da emissão; (b) outra equipe assumir a IA, com ritmo de deploy próprio; (c) a orquestração migrar para Python; (d) o bulkhead da backend#157 deixar de proteger a emissão em produção. Antes de algum gatilho se confirmar, não extrair: um serviço separado duplicaria a regra fiscal ou exigiria propagar identidade a cada chamada de ferramenta.
7. **Planos de assinatura com créditos semanais da Ceres (épico SupersonicTeam/Agrocontabil#72).** Decisão de 2026-09-30, no estilo dos planos do Claude: **cobrança mensal, uso limitado por semana, sem limite de janela curta** (nada de reset a cada 5 horas nem teto diário). O uso é medido em **créditos** = (tokens de entrada × peso + tokens de saída × peso) × fator do modelo, então pergunta complexa e modelo maior gastam mais. A **etapa A** (backend#166) entrega o mecanismo de cota com plano padrão por configuração: reserva do teto antes da chamada ao modelo, acerto pelo consumo real, 429 `LIMITE_USO_ASSISTENTE`. A **etapa B** (o épico) traz `Plano` versionado (créditos por semana + modelos permitidos), `Assinatura`, gateway de pagamento e, provavelmente, a entidade `Escritorio` para cota compartilhada entre contadores. Não confundir com o bulkhead (#157) nem com o rate limit (#150), que são proteção técnica.

## Referências do domínio

- IBS/CBS — EC 132/2023, LC 214/2025.
- Documentação do projeto: `../Docs/` e nota `projects/TCC - 2026/` no Second Brain.

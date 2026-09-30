---
name: codebase-map
description: >
  Mapa estático do repositório AgroContabil: pacotes → classes-chave → responsabilidade.
  Use ANTES de ler arquivos para saber exatamente onde procurar, economizando tokens.
  Atualizar este mapa ao adicionar pacotes ou classes significativas.
---

# Codebase Map — AgroContabil Backend

Use este mapa para navegar sem precisar fazer `find` ou `ls` em cada pasta.

## Raiz: `src/main/java/br/com/agrocontabil/`

### `config/`
| Classe | Responsabilidade |
|--------|-----------------|
| `SecurityConfig` | Regras por papel (#93): público só health/OpenAPI/Actuator, `/api/**` exige papel; CSRF off, stateless |
| `CorsConfig` | CORS via `APP_CORS_ALLOWED_ORIGINS` |
| `OpenApiConfig` | springdoc / Swagger UI |
| `MockIdentidadeFilter` | Resolve o produtor por requisição pelo header `X-Usuario-Id` (profiles dev/homolog); sem header usa o semente (#91) |
| `TributosProperties/Config` | Alíquotas IBS/CBS do `application.yml` |
| `CertificadoDigitalProperties/Config` | Caminho/senha do `.p12` via env `NFE_CERT_*` |
| `SefazProperties/Config` | Ambiente (HOMOLOGACAO/PRODUCAO), timeouts |
| `RetryConfig` | Spring Retry para chamadas SEFAZ |
| `SchedulingConfig` | Habilita `@Scheduled` e o lock distribuído ShedLock (`@EnableSchedulerLock`, `JdbcTemplateLockProvider` na tabela `shedlock`, relógio do banco — issue #87) |
| `DataLoader` | Seed em dev/homolog: **dois** produtores (A = semente/fallback, B = outro dono) + referências NCM/CFOP |

### `controller/`
| Classe | Endpoints |
|--------|-----------|
| `HealthController` | `GET /api/v1/health` |
| `NotaFiscalController` | `/api/notas/**` — todos os 12 endpoints (ver tabela abaixo) |
| `ApuracaoController` | `/api/apuracao` — consulta mensal (RN004) + `/{id}/reabrir` (RN005) |
| `DashboardController` | `GET /api/dashboard?periodo=YYYY-MM` — painel agregador do produtor (issue #65) |
| `GuiaFiscalController` | `/api/contador/guias` — CRUD de guias fiscais do Contador (issue #74) |
| `CalendarioFiscalController` | `GET /api/calendario-fiscal/{produtorId}/vencimentos` — calendário fiscal do produtor (issue #74) |

### `service/`
| Classe | Responsabilidade |
|--------|-----------------|
| `NotaFiscalService` | CRUD de notas + orquestra geração XML, DANFE, envio, cancelamento (anti-IDOR 404). **Não é `@Transactional` na classe** (issue #99): `gerarXml` roda em 3 fases via `TransactionTemplate` (lê e carrega o grafo → assina/XSD **fora de transação** → recarrega sob lock, recheca e persiste); `gerarPdf` só na fase de leitura. CRUD segue declarativo |
| `GeracaoNotaFiscalService` | Pipeline NF-e mod. 55: validar → mapear → gerar XML → assinar → validar XSD |
| `GeracaoNfaService` | Geração de NFA (Nota Fiscal Avulsa do produtor rural — issue #18) |
| `ImportacaoNotaFiscalService` | Importa XML(s) de NF-e recebida (multipart; produtor=destinatário; issue #19) |
| `NotaFiscalValidacaoService` | Persiste lote de inconsistências em transação **única** (`REQUIRED` desde a issue #97 — era `REQUIRES_NEW` e exigia 2 conexões); marca nota `COM_INCONSISTENCIAS`. **Só pode ser chamado fora de transação** |
| `SefazService` | Envio, consulta de status e cancelamento na SEFAZ. **Nao e `@Transactional` na classe** (issue #96): cada transmissao roda em 3 transacoes curtas via `TransactionTemplate` — reserva sob lock pessimista, I/O fora de transacao, desfecho — mais a compensacao e `reconciliarTransmissoesPresas` |
| `TributacaoService` | Débito: calcula IBS/CBS por item, classifica natureza (DEBITO/CREDITO) e agrega totais; alíquota via `AliquotaIbsCbsResolver`, redução via `ReducaoNcmResolver` |
| `CreditoTributarioService` | Crédito nas notas IMPORTADAS (RN003): mesma base e mesma alíquota do débito (resolvedores compartilhados); só lê `credito.percentualAproveitamento` das properties |
| `AliquotaIbsCbsResolver` | **Único** ponto de resolução de alíquota (issues #86/#85): IBS por UF aplicável (RN002; nota importada = UF do destinatário) + CBS, ambos pela **vigência** na `dataEmissao` em BRT; devolve `AliquotasVigentes` com origem (TABELA_UF/TABELA_VIGENCIA/FALLBACK_YML); fallback conservador para `TributosProperties` com WARN |
| `ReducaoNcmResolver` | **Único** ponto de resolução da redução de base por NCM (issue #52): item → NCM → zero |
| `ChaveAcessoGenerator` | Chave 44 dígitos + DV módulo 11 |
| `AuthenticatedProdutorProvider` | Extrai produtor do `SecurityContext`; sem identidade lança `NaoAutenticadoException` (401) |
| `ApuracaoService` | Consolida/consulta/reabre apuração mensal de IBS/CBS (RN004/RN005); reúsa agregações do `NotaFiscalRepository`; fechamento do job por produtor em transação própria, lendo IDs em lotes por keyset (issue #100) |
| `ResumoLoteApuracao` | Record interno com o resultado do lote do job de fechamento: concluídas + IDs com falha (issue #100) |
| `DashboardService` | Agrega o painel do produtor (issue #65): reúsa `ApuracaoService` (KPIs) + `NotaFiscalRepository` (série mensal, últimas notas); zero-fill de 6 meses; anti-IDOR |
| `GuiaFiscalService` | CRUD de guias fiscais (issues #74/#94): `criar`/`listar`/`marcarComoPaga` exigem que o `produtorId` seja o do produtor autenticado — 404 se divergir (anti-IDOR) |
| `GuiaFiscalStatusSyncJob` | `@Scheduled` diário — marca `PENDENTE` vencida como `VENCIDA` (issue #74) |
| `CalendarioFiscalService` | Monta o calendário fiscal do produtor (issue #74): reúsa `GuiaFiscalRepository` como única fonte de vencimento nesta entrega |

### `nfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `FiscalRulesValidator` | Valida regras de negócio fiscal antes de gerar XML (inclui NCM/CFOP — issue #20) |
| `NotaFiscalNfeMapper` | Entidade → classes JAXB (formatação fiscal) |
| `NfeToNotaFiscalMapper` | Mapper reverso: XML NF-e importado → entidade (issue #19) |
| `NfeXmlImporter` | Parser XXE-safe do XML importado |
| `XmlGenerator` | JAXB marshall → String XML |
| `XsdValidator` | Valida XML contra XSD oficial layout 4.00 |
| `SecureXmlFactories` | Fábrica XML com hardening XXE (centralizado) |
| `NfeAssinador` | Assinatura enveloped (Apache Santuario, RSA-SHA1) |
| `AssinaturaValidator` | Verifica assinatura + tamper detection |
| `KeyStoreCertificadoProvider` | Carrega `.p12` PKCS#12; lazy + validação |
| `nfe/model/` | Classes JAXB puras do layout NF-e 4.00 (NFe, InfNFe, Emit, Dest…) |

### `assistente/` — Ceres, a assistente fiscal (Sprint 6)

**Módulo isolado (issue #141, `ArquiteturaAssistenteTest`):** o módulo só enxerga o backend por `porta/`; só `adaptador/` chama services (nunca repository); fora do módulo só `controller`/`config` dependem dele; nada no módulo é `@Transactional`.

| Classe | Responsabilidade |
|--------|-----------------|
| `AssistenteService` | Entrada da Ceres: identidade (porta) → vaga (semáforo) → rota → auditoria |
| `porta/DadosProdutorPorta` | Leitura dos dados do produtor (compras, apuração, créditos, notas, vencimentos) com records próprios, sem entidade nem projeção de repository |
| `porta/IdentidadePorta` | Produtor autenticado, resolvido uma vez na thread da requisição |
| `porta/AuditoriaPorta` | Gravação do `RegistroInteracao` (sem texto da pergunta/resposta) |
| `adaptador/*Adaptador` | Implementam as portas sobre `ConsultaDadosProdutorService`, `ApuracaoService`, `CreditoService`, `AuthenticatedProdutorProvider` e `service/AssistenteInteracaoService` |
| `auditoria/AuditoriaAssistente` | Monta o registro de auditoria (parâmetros mascarados) e o entrega à porta |

| Classe | Responsabilidade |
|--------|-----------------|
| `llm/ModeloLinguagem` | **Porta única** para o `ChatModel` (Ollama ou API compatível com a da OpenAI, por `spring.ai.model.chat`). Falha do provedor → `AssistenteIndisponivelException` (503); `identificacao()` = `provedor/modelo` para auditoria (issue #141) |
| `llm/OllamaHealthIndicator` | Health `ollama`: modelos baixados? Inacessível/ausente → `DEGRADED` (HTTP 200), nunca `DOWN`; cliente próprio com timeout curto + cache (issue #141) |

| `ContextoAssistente` | Produtor + "hoje" + fontes da pergunta, entregue às funções pelo `ToolContext` (nunca visto pelo modelo). `de(toolContext)` **falha fechado** (issue #143) |
| `RotaDados` | Laço modelo → função → modelo **do backend** (`internalToolExecutionEnabled=false` + `ToolCallingManager`), teto `max-rodadas-ferramentas`; resposta passa pelo `ValidadorNumeros` (issue #142) |
| `ValidadorNumeros` | Todo número/data da resposta tem de aparecer nos resultados das funções, na pergunta ou no prompt de sistema; senão a resposta é descartada (issue #142) |
| `DataSanitizer` | Máscara de CPF/CNPJ/chave/e-mail/telefone/CEP em tudo que vai ao modelo (pergunta + resultados). Aplicado nos resultados por `ferramentas/ResultadoSanitizadoConverter`, declarado em cada `@Tool` (issue #144) |
| `ResultadoRota` | Texto + `DesfechoInteracao` da rota; fora de `RESPONDIDA` a resposta foi descartada |
| `RotaLegislacao` | RAG (issue #156): trechos vigentes → modelo sem funções, trechos só com marca `[Tn]` → `ValidadorCitacao` + `ValidadorNumeros` contra os trechos. Sem trecho: "não encontrei fundamento legal" sem chamar o modelo |
| `rag/FatiadorDispositivos` | HTML do Planalto (jsoup) → um `DispositivoLegal` por artigo/§/inciso/alínea, com o texto do caput; descarta tachado e anotações; nova redação de emenda vira "art. 1º (redação do art. 156-A, § 1º)" |
| `rag/IngestaoLegislacaoService` | `PgVectorStore.add` idempotente pelo hash; nova redação = nova vigência (fecha só o `vigenciaFim` da anterior, ordem que se repara sozinha). `IngestaoLegislacaoRunner` = comando no profile `ingestao` |
| `rag/BuscaLegislacaoService` | Top-k vigentes na data, com limiar de similaridade (`app.assistente.rag.*`); filtro de vigência via `FilterExpressionBuilder` |
| `rag/ValidadorCitacao` | Marca `[Tn]` inexistente ou ausente → "não encontrei fundamento legal"; senão troca a marca pela referência dos metadados e devolve `CitacaoLegal` com link |
| `PromptsCeres` | Instruções de sistema (orientam; as garantias estão em código) |
| `ferramentas/FerramentasDadosProdutor` | 5 funções `@Tool` (`totalComprasPorInsumo`, `consultarApuracao`, `consultarCreditos`, `listarNotas`, `proximosVencimentos`). **Nenhum parâmetro de produtor**; valida parâmetros antes de consultar; DTOs enxutos em `RespostasFerramentas` (issues #142/#143) |
| `ferramentas/ProcessadorErroFerramenta` | O que volta ao modelo quando a função falha: mensagem de parâmetro (nossa) ou genérica; contexto ausente aborta |

`service/AssistenteInteracaoService` (chamado pelo `AuditoriaAdaptador`): grava `AssistenteInteracao` (tabela `assistente_interacao`, changeSet 0025) — rota, desfecho, funções mascaradas, modelo, latência, tokens, tamanho da pergunta; **nunca o texto** (issue #144). `config/LimiteCorpoAssistenteFilter`: 413 acima de 16 KB em `/api/assistente/**`, antes do Jackson.

`service/ConsultaDadosProdutorService`: consultas fixas da Ceres com `produtorId` explícito (compras por NCM/descrição com `LIKE` escapado, notas top-N, guias não pagas). `ApuracaoService.consultarParaProdutor` e `CreditoService.somarCreditosNoIntervalo` são as variantes com produtor explícito (a antiga delega). `controller/AssistenteController`: `POST /api/assistente/chat` → `assistente/AssistenteService`.

`config/AssistenteProperties` (+ `AssistenteConfig`): `app.assistente.*` (timeout/cache do health, `max-rodadas-ferramentas`, `max-conversas-simultaneas`).
Testes: `support/ModelosSimuladosConfig` registra `ChatModelSimulado` (programável) e `EmbeddingModelSimulado` (1024 dim) no profile `test`.

### `security/` (issue #93)
| Classe | Responsabilidade |
|--------|-----------------|
| `PapelUsuario` | Enum PRODUTOR/CONTADOR + authority `ROLE_*` |
| `UsuarioAutenticado` | Principal da requisição: papel + produtor **ou** contador (invariante na construção) |
| `UsuarioAutenticadoProvider` | Porta única para a identidade autenticada (`get()` → 401; `tentar()` → `Optional`) |
| `ProdutorEmContextoResolver` | Autoriza o `produtorId` alvo: produtor só a si, contador só a carteira → 404 |
| `SecurityErrorResponder` | Envelope `ApiError` nas negativas da cadeia de filtros (401 + `WWW-Authenticate`, 403) |

### `danfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `DanfePdfService` | Gera PDF via JasperReports (`.jrxml` em resources) |
| `DanfeItemRow` | DTO de linha de item para o relatório Jasper |

### `sefaz/`
| Classe | Responsabilidade |
|--------|-----------------|
| `SefazTransport` | Interface plugável de transmissão |
| `MockSefazTransport` | Mock fiel à SEFAZ nos casos da reconciliação: 204 no reenvio, 573 no evento repetido, 217 → `NAO_ENCONTRADA`, 101 → `CANCELADA` (issue #83) |
| `SefazClient` | Retry pela idempotência: `enviar`/`cancelar` só re-tentam `SefazConexaoException`; `consultarStatus` re-tenta qualquer falha de comunicação (issue #83) |
| `SefazRetorno` | Record com cStat, motivo, protocolo, situação |
| `SituacaoSefaz` | Enum: AUTORIZADA, REJEITADA, PENDENTE, DENEGADA; só na consulta: NAO_ENCONTRADA, CANCELADA (issue #83) |
| `SefazAmbiente` | Enum: HOMOLOGACAO, PRODUCAO |
| `SefazStatusSyncJob` | `@Scheduled` — reconcilia transmissões presas e sincroniza notas PENDENTE_SEFAZ (keyset por IDs + disjuntor — issue #83) |

### `model/`
| Classe | Responsabilidade |
|--------|-----------------|
| `NotaFiscal` | Entidade principal (tabela `notas_fiscais`) |
| `NotaFiscalItem` | Itens da nota (tabela `nota_fiscal_itens`) |
| `EnderecoDestinatario` | Embedded address do destinatário |
| `Produtor` | Produtor rural autenticado |
| `NotaFiscalValidacao` | Histórico de inconsistências de validação (issue #22) |
| `CfopReferencia` / `NcmReferencia` | Tabelas de referência p/ validação NCM/CFOP (issue #20) |
| `AliquotaIbsUf` (+ `AliquotaIbsUfId`) | Alíquota de IBS (estadual/municipal) por UF **e vigência** — PK composta `(uf, data_inicio)`; `data_fim` NULL = aberta (issues #31/#85) |
| `AliquotaCbs` | Alíquota de CBS por vigência (`aliquota_cbs`, issue #85); substitui o valor fixo do yml como fonte primária |
| `EnderecoProdutor` | `@Embeddable` com o endereço completo do emitente (issue #88) — alimenta `<enderEmit>` e o `cMunFG`; a UF fica em `Produtor.estado` |
| `GuiaFiscal` | Guia fiscal (ICMS/FUNRURAL/DARF/GPS/OUTRO) anexada pelo Contador a um produtor (issue #74) |
| `Contador` | Escritório de contabilidade — 2ª identidade do sistema; vínculo N:N com `Produtor` via `contador_produtor` (issue #93) |
| **Enums** | `StatusNotaFiscal` (RASCUNHO→XML_GERADO→[ENVIANDO]→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ; AUTORIZADA→[CANCELANDO]→CANCELADA; COM_INCONSISTENCIAS — `ENVIANDO`/`CANCELANDO` sao transitorios e bloqueiam toda operacao, issue #96) · `OrigemNotaFiscal` (PROPRIA/IMPORTADA) · `TipoDocumentoFiscal` (NFE/NFA) · `TipoOperacaoCfop` · `AmbitoCfop` · `TipoProdutor` · `TipoGuiaFiscal` (ICMS/FUNRURAL/DARF/GPS/OUTRO) · `StatusGuiaFiscal` (PENDENTE/PAGA/VENCIDA) |
| `Conversa`, `Diagnostico`, `Feedback`, `ImpactoGeral`, `PorteEmpresa`, `TipoFeedback`, `RegimeTributario` | **Scaffolding de sprint futura (AI consultoria)** — sem controller/service |

### `dto/`
- `NotaFiscalRequest/Response` — CRUD principal
- `NotaFiscalItemRequest/Response` — itens da nota
- `CancelamentoRequest/Response` — cancelamento de NF-e (issue #21)
- `ImportacaoNotaResultado` — resultado da importação de XML (issue #19)
- `NotaFiscalValidacaoResponse` — histórico de inconsistências
- `SefazEnvioResponse` / `SefazStatusResponse` — retornos SEFAZ
- `GuiaFiscalRequest/Response` — guia fiscal do Contador (issue #74)
- `VencimentoFiscalResponse` — item do calendário fiscal do produtor (issue #74)

### `mapper/`
- `NotaFiscalMapper` — entidade ↔ DTO (request/response)
- `GuiaFiscalMapper` — entidade ↔ DTO de guia fiscal e item do calendário (issue #74)

### `validation/`
Bean Validation customizada (anotação + validator):
| Anotação | Validator | Valida |
|----------|-----------|--------|
| `@ValidCpfCnpj` | `CpfCnpjValidator` | Dígitos verificadores de CPF/CNPJ (issue #18) |
| `@ValidNcm` | `NcmValidator` | NCM contra tabela de referência (issue #20) |
| `@ValidCfop` | `CfopValidator` | CFOP contra tabela de referência (issue #20) |
| `@CodigoIbgeCoerenteComUf` | `CodigoIbgeCoerenteComUfValidator` | Constraint de **classe** no endereço do destinatário: prefixo do código IBGE = cUF da UF (issue #88) |

### `exception/`
| Classe | HTTP | Quando |
|--------|------|--------|
| `ResourceNotFoundException` | 404 | Nota não encontrada / outro produtor |
| `NotaFiscalNaoEditavelException` | 409 | Edição fora de RASCUNHO/COM_INCONSISTENCIAS |
| `NotaFiscalNaoEnviavelException` | 409 | Envio sem XML gerado |
| `NotaFiscalNaoConsultavelException` | 409 | Consulta sem chave de acesso |
| `NotaFiscalNaoCancelavelException` | 409 | Cancelamento em status inválido |
| `CancelamentoForaPrazoException` | 422 | Cancelamento após 30 dias (RN006) |
| `CancelamentoRejeitadoException` | 422 | SEFAZ recusou o evento de cancelamento (`rejectedValue`=cStat, `message`=xMotivo — issue #84) |
| `NotaFiscalInvalidaParaEmissaoException` | 422 | Dados inválidos para emissão |
| `NfaInvalidaParaEmissaoException` | 422 | Dados inválidos para NFA |
| `XmlImportacaoInvalidaException` | 422 | XML importado inválido/malformado |
| `DanfeIndisponivelException` | 409 | PDF pedido antes do XML |
| `XmlIndisponivelException` | 409 | XML pedido antes de ser gerado (issue #89) |
| `DanfeGenerationException` | 500 (genérico) | Falha interna no JasperReports |
| `AssinaturaDigitalException` | 500 (genérico) | Falha na assinatura/cert |
| `SefazComunicacaoException` | 502 | Base das falhas de comunicação; instância direta = indeterminada |
| `SefazConexaoException` | 502 | Requisição comprovadamente não chegou (conexão/DNS/TLS) — retry seguro (issue #83) |
| `SefazRespostaIndeterminadaException` | 502 | Requisição pode ter sido processada (read-timeout etc.) — nunca re-tentar; consultar pela chave (issue #83) |
| `XmlGenerationException` | 422/500 | Falha na geração ou validação XSD |
| `GuiaJaPagaException` | 409 | Guia fiscal já está PAGA (issue #74) |
| `JanelaCalendarioInvalidaException` | 400 | `de > ate` ou janela > 36 meses no calendário fiscal (issue #103) |
| `AssistenteIndisponivelException` | 503 | Modelo da Ceres não respondeu / não configurado (issue #141) |
| `AssistenteOcupadoException` | 429 | Acima de `app.assistente.max-conversas-simultaneas` (issue #142) |
| `NaoAutenticadoException` | 401 | Requisição sem identidade válida: header `X-Usuario-Id` que não resolve, ou profile sem filtro de identidade (issue #92) |
| `DomainException` | configurável | Base para exceções de domínio |
| `GlobalExceptionHandler` | — | `@RestControllerAdvice` → envelope `ApiError.errors[]` |

### `common/`
- `ApiError` — envelope `{ errors: [] }` de resposta de erro
- `ErrorDetail` — `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`
- `ErrorCode` — catálogo de códigos de erro
- `UfIbge` — cUF das 27 UFs (`Optional`, sem default silencioso) + `prefixoCoerente(codigoIbge, uf)` (issue #88)
- `DocumentoFiscalValidator` — utilitário de validação de documento fiscal (CPF/CNPJ)

### `repository/`
`NotaFiscalRepository` (paginado + ownership `findByIdAndProdutorId`), `NotaFiscalValidacaoRepository`, `ProdutorRepository`, `CfopReferenciaRepository`, `NcmReferenciaRepository`, `AliquotaIbsUfRepository`/`AliquotaCbsRepository` (`findVigente` — lista ordenada por `data_inicio DESC`; só o resolvedor consome), `ContadorRepository` (`findByCpfCnpj` + `existsByIdAndProdutoresId` — vínculo Contador↔Produtor, issue #93), `GuiaFiscalRepository` (ownership `findByIdAndProdutorId` + `findByStatusAndDataVencimentoBefore` p/ job — issue #74; listagem paginada `findByProdutorId(Pageable)` e janela do calendário — issue #103) · e os do scaffolding AI: `ConversaRepository`, `DiagnosticoRepository`, `FeedbackRepository`.

## Endpoints REST (`/api/notas`)
| Método | Path | Descrição |
|--------|------|-----------|
| GET | `/api/notas` | Lista paginada (produtor autenticado) |
| GET | `/api/notas/{id}` | Busca por ID (anti-IDOR 404) |
| POST | `/api/notas` | Cria nota (status RASCUNHO) |
| POST | `/api/notas/importar` | Importa XML(s) de NF-e (multipart; origem=IMPORTADA) |
| PUT | `/api/notas/{id}` | Atualiza (só RASCUNHO/COM_INCONSISTENCIAS) |
| DELETE | `/api/notas/{id}` | Exclui (só RASCUNHO/COM_INCONSISTENCIAS) |
| POST | `/api/notas/{id}/gerar-xml` | Gera e assina XML NF-e (RASCUNHO→XML_GERADO) |
| GET | `/api/notas/{id}/xml` | Baixa XML assinado (`application/xml`; 409 se ainda nao gerado) |
| GET | `/api/notas/{id}/pdf` | Baixa DANFE (`application/pdf`) |
| POST | `/api/notas/{id}/enviar-sefaz` | Transmite à SEFAZ (XML_GERADO→AUTORIZADA/REJEITADA) |
| GET | `/api/notas/{id}/status-sefaz` | Consulta e sincroniza status na SEFAZ |
| POST | `/api/notas/{id}/cancelar` | Cancela NF-e autorizada (limite 30 dias — RN006) |
| GET | `/api/notas/{id}/validacoes` | Histórico de inconsistências (anti-IDOR 404) |

## Endpoints REST (`/api/contador/guias`, `/api/calendario-fiscal` — issue #74)
| Método | Path | Descrição |
|--------|------|-----------|
| POST | `/api/contador/guias` | Cria guia fiscal (produtorId explícito, sem auth de Contador) |
| GET | `/api/contador/guias?produtorId=&page=&size=` | Lista paginada das guias do produtor (corpo array + `X-Total-Count`; padrão 100, máx. 200 — issue #103) |
| PATCH | `/api/contador/guias/{id}/pagar` | Marca guia como paga (produtor autenticado, anti-IDOR real) |
| GET | `/api/calendario-fiscal/{produtorId}/vencimentos?de=&ate=` | Vencimentos do produtor na janela (padrão mês corrente ±12 meses; máx. 36 — issue #103) |

## Recursos em `src/main/resources/`
- `xsd/` — XSD oficial NF-e layout 4.00
- `reports/` — templates `.jrxml` do JasperReports (DANFE)
- `application.yml` / `application-test.yml` / `application-dev.yml` / `application-homolog.yml` / `application-prod.yml`

## Testes em `src/test/`
- `resources/certs/` — certificado autoassinado para testes de assinatura
- Padrão: `*Test.java` (unitário Mockito) + teste de integração (Spring + H2)
- `ProdutorTestFixtures` — emitente com IE e endereço (`produtorCompleto`, `enderecoParaUf`, variantes inválidas) exigido pelos fluxos de emissão desde a issue #88

## Migrations (Liquibase)
- `db/changelog/db.changelog-master.yaml` — master changelog
- `db/changelog/changes/` — changesets `.xml` individuais: `0001-initial-schema` … `0026-legislacao-trecho` (próximo número livre: **0027**; a 0026 é só PostgreSQL — extensão `vector`)
- **Nunca editar changesets já aplicados.** Nova mudança = novo arquivo.

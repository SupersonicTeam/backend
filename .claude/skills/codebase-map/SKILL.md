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
| `SecurityConfig` | `permitAll()` até Sprint 8; CSRF off, stateless |
| `CorsConfig` | CORS via `APP_CORS_ALLOWED_ORIGINS` |
| `OpenApiConfig` | springdoc / Swagger UI |
| `MockProdutorFilter` | Injeta produtor fake no `SecurityContext` |
| `TributosProperties/Config` | Alíquotas IBS/CBS do `application.yml` |
| `CertificadoDigitalProperties/Config` | Caminho/senha do `.p12` via env `NFE_CERT_*` |
| `SefazProperties/Config` | Ambiente (HOMOLOGACAO/PRODUCAO), timeouts |
| `RetryConfig` | Spring Retry para chamadas SEFAZ |
| `SchedulingConfig` | Habilita `@Scheduled` (job de sync SEFAZ) |
| `DataLoader` | Seed de dados em dev/test (inclui referências NCM/CFOP) |

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
| `NotaFiscalService` | CRUD de notas + orquestra geração XML, DANFE, envio, cancelamento (anti-IDOR 404) |
| `GeracaoNotaFiscalService` | Pipeline NF-e mod. 55: validar → mapear → gerar XML → assinar → validar XSD |
| `GeracaoNfaService` | Geração de NFA (Nota Fiscal Avulsa do produtor rural — issue #18) |
| `ImportacaoNotaFiscalService` | Importa XML(s) de NF-e recebida (multipart; produtor=destinatário; issue #19) |
| `NotaFiscalValidacaoService` | Persiste lote de inconsistências em `REQUIRES_NEW`; marca nota `COM_INCONSISTENCIAS` |
| `SefazService` | Envio, consulta de status e cancelamento na SEFAZ |
| `TributacaoService` | Débito: calcula IBS/CBS por item, classifica natureza (DEBITO/CREDITO) e agrega totais; alíquota via `AliquotaIbsCbsResolver`, redução via `ReducaoNcmResolver` |
| `CreditoTributarioService` | Crédito nas notas IMPORTADAS (RN003): mesma base e mesma alíquota do débito (resolvedores compartilhados); só lê `credito.percentualAproveitamento` das properties |
| `AliquotaIbsCbsResolver` | **Único** ponto de resolução de alíquota (issue #86): IBS por UF aplicável (RN002; nota importada = UF do destinatário) + CBS; devolve `AliquotasVigentes` com origem (TABELA_UF/FALLBACK_YML); fallback conservador para `TributosProperties` com WARN |
| `ReducaoNcmResolver` | **Único** ponto de resolução da redução de base por NCM (issue #52): item → NCM → zero |
| `ChaveAcessoGenerator` | Chave 44 dígitos + DV módulo 11 |
| `AuthenticatedProdutorProvider` | Extrai produtor do `SecurityContext` |
| `ApuracaoService` | Consolida/consulta/reabre apuração mensal de IBS/CBS (RN004/RN005); reúsa agregações do `NotaFiscalRepository` |
| `DashboardService` | Agrega o painel do produtor (issue #65): reúsa `ApuracaoService` (KPIs) + `NotaFiscalRepository` (série mensal, últimas notas); zero-fill de 6 meses; anti-IDOR |
| `GuiaFiscalService` | CRUD de guias fiscais do Contador (issue #74): criar/listar sem auth de Contador (produtorId explícito no request); `marcarComoPaga` usa `AuthenticatedProdutorProvider` + anti-IDOR real |
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

### `danfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `DanfePdfService` | Gera PDF via JasperReports (`.jrxml` em resources) |
| `DanfeItemRow` | DTO de linha de item para o relatório Jasper |

### `sefaz/`
| Classe | Responsabilidade |
|--------|-----------------|
| `SefazTransport` | Interface plugável de transmissão |
| `MockSefazTransport` | Mock determinístico (substituto até Sprint 7) |
| `SefazClient` | Orquestra retry + chama `SefazTransport` |
| `SefazRetorno` | Record com cStat, motivo, protocolo, situação |
| `SituacaoSefaz` | Enum: AUTORIZADA, REJEITADA, PENDENTE |
| `SefazAmbiente` | Enum: HOMOLOGACAO, PRODUCAO |
| `SefazStatusSyncJob` | `@Scheduled` — sincroniza notas PENDENTE_SEFAZ |

### `model/`
| Classe | Responsabilidade |
|--------|-----------------|
| `NotaFiscal` | Entidade principal (tabela `notas_fiscais`) |
| `NotaFiscalItem` | Itens da nota (tabela `nota_fiscal_itens`) |
| `EnderecoDestinatario` | Embedded address do destinatário |
| `Produtor` | Produtor rural autenticado |
| `NotaFiscalValidacao` | Histórico de inconsistências de validação (issue #22) |
| `CfopReferencia` / `NcmReferencia` | Tabelas de referência p/ validação NCM/CFOP (issue #20) |
| `GuiaFiscal` | Guia fiscal (ICMS/FUNRURAL/DARF/GPS/OUTRO) anexada pelo Contador a um produtor (issue #74) |
| **Enums** | `StatusNotaFiscal` (RASCUNHO→XML_GERADO→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ/CANCELADA; COM_INCONSISTENCIAS) · `OrigemNotaFiscal` (PROPRIA/IMPORTADA) · `TipoDocumentoFiscal` (NFE/NFA) · `TipoOperacaoCfop` · `AmbitoCfop` · `TipoProdutor` · `TipoGuiaFiscal` (ICMS/FUNRURAL/DARF/GPS/OUTRO) · `StatusGuiaFiscal` (PENDENTE/PAGA/VENCIDA) |
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

### `exception/`
| Classe | HTTP | Quando |
|--------|------|--------|
| `ResourceNotFoundException` | 404 | Nota não encontrada / outro produtor |
| `NotaFiscalNaoEditavelException` | 409 | Edição fora de RASCUNHO/COM_INCONSISTENCIAS |
| `NotaFiscalNaoEnviavelException` | 409 | Envio sem XML gerado |
| `NotaFiscalNaoConsultavelException` | 409 | Consulta sem chave de acesso |
| `NotaFiscalNaoCancelavelException` | 409 | Cancelamento em status inválido |
| `CancelamentoForaPrazoException` | 422 | Cancelamento após 30 dias (RN006) |
| `NotaFiscalInvalidaParaEmissaoException` | 422 | Dados inválidos para emissão |
| `NfaInvalidaParaEmissaoException` | 422 | Dados inválidos para NFA |
| `XmlImportacaoInvalidaException` | 422 | XML importado inválido/malformado |
| `DanfeIndisponivelException` | 409 | PDF pedido antes do XML |
| `XmlIndisponivelException` | 409 | XML pedido antes de ser gerado (issue #89) |
| `DanfeGenerationException` | 500 (genérico) | Falha interna no JasperReports |
| `AssinaturaDigitalException` | 500 (genérico) | Falha na assinatura/cert |
| `SefazComunicacaoException` | 502 | Timeout/falha após retry |
| `XmlGenerationException` | 422/500 | Falha na geração ou validação XSD |
| `GuiaJaPagaException` | 409 | Guia fiscal já está PAGA (issue #74) |
| `DomainException` | configurável | Base para exceções de domínio |
| `GlobalExceptionHandler` | — | `@RestControllerAdvice` → envelope `ApiError.errors[]` |

### `common/`
- `ApiError` — envelope `{ errors: [] }` de resposta de erro
- `ErrorDetail` — `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`
- `ErrorCode` — catálogo de códigos de erro
- `DocumentoFiscalValidator` — utilitário de validação de documento fiscal (CPF/CNPJ)

### `repository/`
`NotaFiscalRepository` (paginado + ownership `findByIdAndProdutorId`), `NotaFiscalValidacaoRepository`, `ProdutorRepository`, `CfopReferenciaRepository`, `NcmReferenciaRepository`, `GuiaFiscalRepository` (ownership `findByIdAndProdutorId` + `findByStatusAndDataVencimentoBefore` p/ job — issue #74) · e os do scaffolding AI: `ConversaRepository`, `DiagnosticoRepository`, `FeedbackRepository`.

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
| GET | `/api/contador/guias?produtorId=` | Lista guias fiscais do produtor |
| PATCH | `/api/contador/guias/{id}/pagar` | Marca guia como paga (produtor autenticado, anti-IDOR real) |
| GET | `/api/calendario-fiscal/{produtorId}/vencimentos` | Vencimentos do produtor (hoje: guias fiscais mapeadas) |

## Recursos em `src/main/resources/`
- `xsd/` — XSD oficial NF-e layout 4.00
- `reports/` — templates `.jrxml` do JasperReports (DANFE)
- `application.yml` / `application-test.yml` / `application-prod.yml`

## Testes em `src/test/`
- `resources/certs/` — certificado autoassinado para testes de assinatura
- Padrão: `*Test.java` (unitário Mockito) + teste de integração (Spring + H2)

## Migrations (Liquibase)
- `db/changelog/db.changelog-master.yaml` — master changelog
- `db/changelog/changes/` — changesets `.xml` individuais: `0001-initial-schema` … `0019-indices-compostos-escalabilidade` (próximo número livre: **0020**)
- **Nunca editar changesets já aplicados.** Nova mudança = novo arquivo.

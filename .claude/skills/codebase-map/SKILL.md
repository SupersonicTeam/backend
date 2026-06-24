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
| `DataLoader` | Seed de dados em dev/test |

### `controller/`
| Classe | Endpoints |
|--------|-----------|
| `HealthController` | `GET /health` |
| `NotaFiscalController` | `/api/notas/**` — todos os 9 endpoints |

### `service/`
| Classe | Responsabilidade |
|--------|-----------------|
| `NotaFiscalService` | CRUD de notas + geração XML + DANFE |
| `GeracaoNotaFiscalService` | Orquestra pipeline: validar → mapear → gerar XML → assinar → validar XSD |
| `SefazService` | Envio e consulta de status na SEFAZ |
| `CalculoIbsCbsService` | Cálculo puro de IBS/CBS por item |
| `ChaveAcessoGenerator` | Chave 44 dígitos + DV módulo 11 |
| `AuthenticatedProdutorProvider` | Extrai produtor do `SecurityContext` |

### `nfe/`
| Classe | Responsabilidade |
|--------|-----------------|
| `FiscalRulesValidator` | Valida regras de negócio fiscal antes de gerar XML |
| `NotaFiscalNfeMapper` | Entidade → classes JAXB (formatação fiscal) |
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
| `MockSefazTransport` | Mock determinístico (Sprint 3; substituto até Sprint 7) |
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
| `StatusNotaFiscal` | Enum: RASCUNHO→XML_GERADO→AUTORIZADA/REJEITADA/PENDENTE_SEFAZ/CANCELADA |
| `Conversa`, `Diagnostico`, `Feedback`… | **Scaffolding de sprint futura (AI consultoria)** — sem controller/service |

### `dto/`
- `NotaFiscalRequest/Response` — CRUD principal
- `NotaFiscalItemRequest/Response` — itens da nota
- `SefazEnvioResponse` — retorno do envio SEFAZ
- `SefazStatusResponse` — retorno da consulta de status

### `exception/`
| Classe | HTTP | Quando |
|--------|------|--------|
| `ResourceNotFoundException` | 404 | Nota não encontrada / outro produtor |
| `NotaFiscalNaoEditavelException` | 409 | Edição fora de RASCUNHO |
| `NotaFiscalNaoEnviavelException` | 409 | Envio sem XML gerado |
| `NotaFiscalNaoConsultavelException` | 409 | Consulta sem chave de acesso |
| `NotaFiscalInvalidaParaEmissaoException` | 422 | Dados inválidos para emissão |
| `DanfeIndisponivelException` | 409 | PDF pedido antes do XML |
| `DanfeGenerationException` | 500 (genérico) | Falha interna no JasperReports |
| `AssinaturaDigitalException` | 500 (genérico) | Falha na assinatura/cert |
| `SefazComunicacaoException` | 502 | Timeout/falha após retry |
| `XmlGenerationException` | 422/500 | Falha na geração ou validação XSD |
| `DomainException` | configurável | Base para exceções de domínio |

### `common/`
- `ApiError` — envelope `{ errors: [] }` de resposta de erro
- `ErrorDetail` — `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`
- `ErrorCode` — catálogo de códigos de erro

## Recursos em `src/main/resources/`
- `xsd/` — XSD oficial NF-e layout 4.00
- `reports/` — templates `.jrxml` do JasperReports (DANFE)
- `application.yml` / `application-test.yml` / `application-prod.yml`

## Testes em `src/test/`
- `resources/certs/` — certificado autoassinado para testes de assinatura
- Padrão: `*Test.java` (unitário Mockito) + `* integração*` (Spring + H2)

## Migrations (Liquibase)
- `db/changelog/db.changelog-master.yaml` — master changelog
- `db/changelog/changes/` — changesets individuais (0001 → 0005+)
- **Nunca editar changesets já aplicados.** Nova mudança = novo arquivo.

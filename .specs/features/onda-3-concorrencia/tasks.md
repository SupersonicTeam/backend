# Tasks — Onda 3: Concorrência e escopo transacional

Sequência de PRs e tasks atômicas. Referências: [spec.md](spec.md), [design.md](design.md).

## Sequência de PRs

| # | PR | Issues | Branch | Deployável isolado? |
|---|----|--------|--------|---------------------|
| 1 | Controle de versão otimista em NotaFiscal | #95 | `fix/95-nota-fiscal-version` | Sim — `@Version` + 409 funcionam sozinhos |
| 2 | Lock e escopo transacional em enviar/cancelar | #96 | `fix/96-lock-transmissao-sefaz` | Sim — depende do `@Version` do PR 1 |
| 3 | Transação curta em gerarXml/gerarPdf + fim do REQUIRES_NEW | #99, #97 | `fix/99-97-escopo-transacional-geracao` | Sim |

Ordem obrigatória: **1 → 2 → 3**. O PR 2 apoia-se no `@Version` do PR 1 (o 409 de conflito é a rede
de segurança quando o lock não cobre). O PR 3 é o maior e fecha duas issues (decisão D-3).

**Pré-requisito:** Ondas 1 e 2 confirmadas na `origin/main` (até `cc258e5`, #114) em 2026-09-23.
Cada PR parte de um worktree isolado, rebaseado na `origin/main`.

---

## PR 1 — #95 Controle de versão otimista

| Task | O que | Onde | Feito quando |
|---|---|---|---|
| 1.1 | `@Version private Long version` | `model/NotaFiscal.java` | Campo com Javadoc explicando o lock otimista |
| 1.2 | ChangeSet `0022-nota-fiscal-version.xml` + registro no master | `db/changelog/changes/`, `db.changelog-master.yaml` | `addColumn` com `defaultValueNumeric="0"`, `nullable=false`, `rollback` |
| 1.3 | Coluna `version` no schema H2 de teste | `src/test/resources/schema-nota-fiscal-test.sql` | `NotaFiscalRepositoryTest` verde |
| 1.4 | `ErrorCode.CONFLITO_CONCORRENCIA` | `common/ErrorCode.java` | Enum + Javadoc no padrão dos vizinhos |
| 1.5 | Handler de `OptimisticLockingFailureException` → 409 | `exception/GlobalExceptionHandler.java` | Envelope `errors[]`; nada técnico no corpo; `log.warn` com `notaId` |
| 1.6 | Teste unitário do handler | `exception/GlobalExceptionHandlerTest` | CA-6 |
| 1.7 | Teste de concorrência de `gerar-xml` | `service/…ConcorrenciaIntegrationTest` (novo) | CA-1: um 200, um 409, **uma** chave persistida |
| 1.8 | Registrar D-4 (por que `ApuracaoMensal`/`GuiaFiscal` ficam de fora) | Javadoc de `NotaFiscal.version` + spec | Rastreável sem reabrir a discussão |

**Gate:** `./mvnw test` verde · CA-1, CA-6, CA-11, CA-12.

---

## PR 2 — #96 Lock e escopo transacional em enviar/cancelar

| Task | O que | Onde | Feito quando |
|---|---|---|---|
| 2.1 | `+ ENVIANDO`, `+ CANCELANDO` | `model/StatusNotaFiscal.java` | Javadoc marcando-os transitórios e não-operáveis |
| 2.2 | `findByIdAndProdutorIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) | `repository/NotaFiscalRepository.java` | Javadoc no padrão; ownership dentro da query |
| 2.3 | `findByStatusInAndUpdatedAtBefore` para reconciliação | `repository/NotaFiscalRepository.java` | Uso restrito a job de sistema, documentado |
| 2.4 | `app.nfe.sefaz.transmissao.timeout-ms` (default 600000) | `config/SefazProperties.java`, `application.yml` | Parametrizado, sem número mágico |
| 2.5 | `enviar` em 3 blocos `TransactionTemplate` + compensação (D-8) | `service/SefazService.java` | RF-96.3, RF-96.7; I/O comprovadamente fora da tx |
| 2.6 | `cancelar` em 3 blocos `TransactionTemplate` + compensação (D-8) | `service/SefazService.java` | RF-96.4, RF-96.8; RN006 permanece na tx1 |
| 2.7 | 409 para operações sobre `ENVIANDO`/`CANCELANDO` | `NotaFiscalService`, `SefazService` | RF-96.10, CA-10 |
| 2.8 | `reconciliarTransmissoesPresas` + chamada no job | `service/SefazService.java`, `sefaz/SefazStatusSyncJob.java` | RF-96.9, CA-9 |
| 2.9 | Testes unitários das transições e compensações | `SefazServiceTest`, `SefazStatusSyncJobTest` | Strict stubs respeitados |
| 2.10 | Testes de concorrência de envio e cancelamento | `…ConcorrenciaIntegrationTest` | CA-2, CA-3, CA-4: **uma** chamada no transporte, **um** evento |
| 2.11 | Nota de integração para o frontend | corpo do PR + CLAUDE.md | Angular deve tratar status desconhecido como "em processamento" |

**Gate:** `./mvnw test` verde · CA-2, CA-3, CA-4, CA-7, CA-8, CA-9, CA-10, CA-11 · regressão de
`ApuracaoEventoDominioIntegrationTest` intacta.

---

## PR 3 — #99 + #97 Transação curta na geração e fim do REQUIRES_NEW

| Task | O que | Onde | Feito quando |
|---|---|---|---|
| 3.1 | `NotaFiscalSnapshot` (`record`) materializando nota + produtor + itens + endereço + totais | `service/NotaFiscalSnapshot.java` (novo) | Nenhum proxy Hibernate atravessa a fronteira |
| 3.2 | Injetar `TransactionTemplate` (+ variante readOnly) e converter as fases em blocos `execute(...)` | `service/NotaFiscalService.java` | D-8; mesmo desenho de `ImportacaoNotaFiscalService`; CRUD segue com `@Transactional` declarativo |
| 3.3 | Remover `@Transactional` da classe | `service/NotaFiscalService.java` | CRUD com comportamento idêntico |
| 3.4 | `gerarXml` em 2 fases + recheca de status na tx de escrita | `service/NotaFiscalService.java` | RF-99.2, RF-99.7 |
| 3.5 | `gerarPdf` em 1 fase readOnly + Jasper fora da tx | `service/NotaFiscalService.java` | RF-99.3 |
| 3.6 | Pipeline de geração recebe snapshot | `GeracaoNotaFiscalService`, `GeracaoNfaService`, `FiscalRulesValidator`, `NotaFiscalNfeMapper` | Zero mudança de regra fiscal ou de layout do XML |
| 3.7 | `DanfePdfService` recebe snapshot | `danfe/DanfePdfService.java` | PDF byte-a-byte equivalente ao atual nos testes |
| 3.8 | `REQUIRES_NEW` → `REQUIRED` + Javadoc da invariante | `service/NotaFiscalValidacaoService.java` | RF-97.1, RF-97.4 |
| 3.9 | Teste de pool=1 com nota inválida | `…ConcorrenciaIntegrationTest` | CA-5; o `connection-timeout: 5s` da #109 já faz um deadlock **falhar** em vez de pendurar a suíte |
| 3.10 | Teste de ausência de `LazyInitializationException` (nota completa: produtor + vários itens + endereço) | integração de `gerarXml` e `gerarPdf` | CA-14 |
| 3.11 | Atualizar `codebase-map` e CLAUDE.md | `.claude/skills/codebase-map/`, `CLAUDE.md` | `NotaFiscalSnapshot` e os novos status documentados |

**Gate:** `./mvnw test` verde · CA-5, CA-12, CA-13, CA-14, CA-15 · regressão de
`NotaFiscalValidacaoFlowTest`, `GeracaoNfaFlowTest`, `NotaFiscalUpdateItemFlowTest` intacta.

---

## Definition of Done da onda

- [ ] Ondas 1 e 2 confirmadas na `origin/main` (feito: `cc258e5`, 2026-09-23)
- [ ] Três PRs mergeados na ordem 1 → 2 → 3, cada um deployável isolado
- [ ] Conventional Commits; worktree isolado por PR; nada direto em `main`
- [ ] Todos os critérios de aceite CA-1 … CA-15 verificados
- [ ] `./mvnw test` verde, acima da baseline de 478 testes, sem aviso de leak do Hikari
- [ ] Code review (Copilot) + auditoria OWASP por PR, sem crítico aberto
- [ ] CLAUDE.md e `codebase-map` atualizados
- [ ] Débitos remanescentes registrados: reconciliação de evento de cancelamento (depende de #83)

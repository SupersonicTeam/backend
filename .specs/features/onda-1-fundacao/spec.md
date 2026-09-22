# Onda 1 — Fundação de escalabilidade — Specification

**Data:** 2026-09-21 · **Repo:** SupersonicTeam/backend · **Autor:** Claude (dev-workflow Fase 2) · **Aprovação:** pendente (Murillo)
**Issues:** #90 → #102 → #98 → #101 → #89 (parte rápida, aprovada como 5º PR)
**Suite atual:** 367 testes verdes (`./mvnw test`, profile `test`, H2).

## Problem Statement

As ondas seguintes do plano de 2026-09-21 (17 issues restantes) vão mexer em transações, jobs e
queries. Hoje faltam três redes de segurança: (1) os testes H2 não têm as FKs de produção, então
comportamento dependente de RESTRICT/CASCADE passa em falso verde; (2) o pool JDBC não tem
timeouts nem leak-detection, então vazamentos de conexão (issues #97/#99) ficam invisíveis;
(3) o scheduler tem 1 thread e o app não faz graceful shutdown, então um job lento atrasa os
outros e um deploy pode matar um fechamento de apuração no meio. Além disso as agregações do
dashboard/apuração e o job de guias fazem full scan por falta de índices compostos.

Esta onda é só config, migration de índice, schema de teste e uma exceção nova. Nenhuma regra de
negócio muda (exceto o status HTTP do `GET /{id}/xml`, #89).

## Goals

- [ ] Schema H2 de teste reproduz as 4 FKs do Liquibase com as mesmas ações `ON DELETE` (#90).
- [ ] Índices compostos cobrindo as queries de dashboard/apuração e do job de guias, via novos changeSets (#102).
- [ ] HikariCP com pool, timeouts, leak-detection e statement timeout parametrizados por env; timeout padrão de transação (#98).
- [ ] Scheduler com pool dedicado (3 threads, prefixo próprio) e shutdown graceful de ~30s (#101).
- [ ] `GET /api/notas/{id}/xml` responde **409** quando o XML ainda não foi gerado; 404 só para nota inexistente/de outro produtor (#89, parte rápida).
- [ ] Cada correção tem teste que falharia sem ela; suíte 100% verde ao final de cada PR.

## Out of Scope

| Item | Motivo |
|------|--------|
| ShedLock / lock distribuído dos jobs | Issue #87 (Onda 5). |
| Corrigir os vazamentos de conexão que o leak-detection vai revelar | Issues #97/#99 (Onda 3) — aqui só instrumentamos. |
| Preview de DANFE de rascunho (#89) | Muda regra de negócio; fica para a issue completa. |
| Ordenação/paginação da listagem de guias (#103) | Só o índice `(produtor_id, data_vencimento)` entra aqui como apoio. |
| FK em `nota_fiscal_validacao.nota_fiscal_id` | O Liquibase (0006) **não** define FK nessa tabela; replicar algo inexistente divergiria de produção. Registrar como débito. |
| Índice único parcial `origem='PROPRIA'` no H2 | H2 não suporta índice parcial — decisão já documentada no script. |
| Trocar `NO_CONSTRAINT` nas entidades | Decisão de 2026-09-21: FKs só no Liquibase + script SQL (opção A). |
| Upgrade Spring Boot 4.x / Micrometer / logs JSON | Roadmap pós-básico. |

---

## Decisões já tomadas (2026-09-21, Murillo)

| # | Decisão | Motivo |
|---|---------|--------|
| D1 | #90 via `ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS ... ON DELETE ...` no `schema-nota-fiscal-test.sql`, sem tocar entidades. | 8 dos 9 testes que usam o script rodam com `ddl-auto=create-drop` — o Hibernate cria as tabelas antes do `@Sql` e os `CREATE TABLE IF NOT EXISTS` viram no-op. Só `ALTER TABLE` idempotente vale nos dois caminhos (`@DataJpaTest` com `ddl-auto=none` e `@SpringBootTest` create-drop). |
| D2 | #89 (parte rápida) entra como 5º PR desta onda. | Baixo esforço; muda contrato para o Angular (404→409) — avisar frontend. |
| D3 | Statement timeout do Postgres **não** vai no `application.yml` base. | O driver H2 rejeita propriedades JDBC desconhecidas (`UNSUPPORTED_SETTING`) — quebraria a suíte. Vai em `application-dev.yml` e `application-prod.yml`. |
| D4 | Scheduler configurado por propriedades `spring.task.scheduling.*`, não por bean manual. | Boot 3.5 autoconfigura `ThreadPoolTaskScheduler` quando há `@EnableScheduling` e nenhum `TaskScheduler` bean. Menos código, mesmo resultado, parametrizável por env. |
| D5 | `spring.transaction.default-timeout` padrão **30s** (issue sugeria 10s), env `DB_TX_TIMEOUT`. | Importação de lote de XML (até 20MB), DANFE Jasper e fechamento mensal podem passar de 10s em máquina fraca. 30s ainda bloqueia transação zumbi. Ajustável sem recompilar. |

---

## User Stories

### P1: #90 — FKs de produção replicadas no schema H2 ⭐

**User Story**: Como dev do backend, quero que o H2 dos testes tenha as mesmas FKs do Postgres, para que um teste que viola integridade referencial falhe no CI em vez de só em produção.

**Why P1**: É a rede de segurança das Ondas 2–7 (que mexem em delete, cascata e transações).

**FKs a replicar (fonte: Liquibase):**

| Constraint | Tabela filha → pai | ON DELETE | Changeset |
|-----------|-------------------|-----------|-----------|
| `fk_nota_fiscal_produtor` | `nota_fiscal.produtor_id → produtores.id` | RESTRICT | 0002 |
| `fk_nota_fiscal_item_nota` | `nota_fiscal_item.nota_fiscal_id → nota_fiscal.id` | CASCADE | 0002 |
| `fk_apuracao_produtor` | `apuracao_mensal.produtor_id → produtores.id` | (default = NO ACTION) | 0014 |
| `fk_guia_fiscal_produtor` | `guias_fiscais.produtor_id → produtores.id` | (default = NO ACTION) | 0018 |

**Arquivos:**
- `src/test/resources/schema-nota-fiscal-test.sql` — bloco novo ao final (após todos os `CREATE TABLE`):
  ```sql
  ALTER TABLE nota_fiscal      ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_produtor   FOREIGN KEY (produtor_id)    REFERENCES produtores (id)   ON DELETE RESTRICT;
  ALTER TABLE nota_fiscal_item ADD CONSTRAINT IF NOT EXISTS fk_nota_fiscal_item_nota  FOREIGN KEY (nota_fiscal_id) REFERENCES nota_fiscal (id)  ON DELETE CASCADE;
  ALTER TABLE apuracao_mensal  ADD CONSTRAINT IF NOT EXISTS fk_apuracao_produtor      FOREIGN KEY (produtor_id)    REFERENCES produtores (id);
  ALTER TABLE guias_fiscais    ADD CONSTRAINT IF NOT EXISTS fk_guia_fiscal_produtor   FOREIGN KEY (produtor_id)    REFERENCES produtores (id);
  ```
  + comentário explicando D1 e "manter sincronizado com 0002/0014/0018".
- `src/test/java/br/com/agrocontabil/repository/SchemaTestIntegridadeReferencialTest.java` — novo `@DataJpaTest` (mesmo setup do `NotaFiscalRepositoryTest`: `ddl-auto=none` + `@Sql`), usando `JdbcTemplate` para DML nativo (o Hibernate mascararia o CASCADE/RESTRICT com cascade próprio).

**Acceptance Criteria**:
1. WHEN um `Produtor` com pelo menos uma `NotaFiscal` é excluído via `DELETE FROM produtores` THEN o H2 SHALL rejeitar com violação de FK (`DataIntegrityViolationException`) — RESTRICT.
2. WHEN uma `NotaFiscal` com itens é excluída via `DELETE FROM nota_fiscal` (SQL nativo, sem Hibernate) THEN os `nota_fiscal_item` SHALL ser removidos automaticamente (count = 0) — CASCADE.
3. WHEN se insere `nota_fiscal_item` com `nota_fiscal_id` inexistente THEN o H2 SHALL rejeitar (filho órfão).
4. WHEN se insere `apuracao_mensal` ou `guias_fiscais` com `produtor_id` inexistente THEN o H2 SHALL rejeitar.
5. WHEN um `Produtor` com `apuracao_mensal`/`guias_fiscais` é excluído THEN o H2 SHALL rejeitar (NO ACTION ≡ RESTRICT sem deferimento).
6. WHEN o script é executado duas vezes na mesma conexão (`@Sql` por método) THEN SHALL ser idempotente (`IF NOT EXISTS`).
7. WHEN a suíte inteira roda THEN os 367 testes existentes SHALL continuar verdes (fixtures já apagam filhos antes de `produtores` — verificado em 9 testes).

**Independent Test**: `./mvnw test -Dtest=SchemaTestIntegridadeReferencialTest` falha sem o bloco `ALTER TABLE` e passa com ele.

**Risco conhecido:** um teste `@SpringBootTest` **sem** `@Transactional` que deixe `apuracao_mensal`/`guias_fiscais` órfãos no contexto compartilhado pode fazer o `produtorRepository.deleteAll()` de outro teste falhar por RESTRICT. Tratamento: corrigir a ordem/abrangência do cleanup do teste, **nunca** afrouxar a FK.

---

### P1: #102 — Índices compostos ⭐

**User Story**: Como produtor usando o dashboard, quero que os totais do mês respondam rápido mesmo com milhares de notas, e como operador quero que o job de guias não faça full scan diário.

**Why P1**: Migration barata, sem risco funcional, e as ondas seguintes vão adicionar mais consultas sobre as mesmas colunas.

**Queries cobertas (verificadas em `NotaFiscalRepository` / `GuiaFiscalRepository`):**

| Índice | Query | Predicado |
|--------|-------|-----------|
| `idx_nota_fiscal_produtor_status_emissao (produtor_id, status, data_emissao)` | `somarDebitosPorPeriodo`, `somarCreditosPorPeriodo`, `contarNotasPorMesEOrigem`, `somarValorNotasPorOrigem` | `produtor_id = ? AND status = 'AUTORIZADA' AND data_emissao >= ? AND < ?` (igualdade, igualdade, range — ordem ótima) |
| `idx_guia_fiscal_status_vencimento (status, data_vencimento)` | `findByStatusAndDataVencimentoBefore` (`GuiaFiscalStatusSyncJob`) | `status = 'PENDENTE' AND data_vencimento < ?` |
| `idx_guia_fiscal_produtor_vencimento (produtor_id, data_vencimento)` | `findByProdutorIdOrderByDataVencimentoDesc` (listagem/calendário; base da #103) | `produtor_id = ? ORDER BY data_vencimento DESC` |

**Arquivos:**
- `src/main/resources/db/changelog/changes/0019-indices-compostos-escalabilidade.xml` — **novo**, 3 changeSets (um por índice, cada um com `<rollback><dropIndex/></rollback>`), cabeçalho no padrão do `0016`. IDs: `0019-index-nota-fiscal-produtor-status-emissao`, `0019-index-guia-fiscal-status-vencimento`, `0019-index-guia-fiscal-produtor-vencimento`. Author `supersonic-team`.
- `src/main/resources/db/changelog/db.changelog-master.yaml` — `include` do 0019 no final.
- `src/test/resources/schema-nota-fiscal-test.sql` — os 3 `CREATE INDEX IF NOT EXISTS` (padrão já usado para `idx_apuracao_status_periodo`).
- `src/test/java/br/com/agrocontabil/repository/SchemaTestIndicesTest.java` — `@DataJpaTest` que consulta `INFORMATION_SCHEMA.INDEX_COLUMNS` do H2 e afirma nome + ordem das colunas dos 3 índices (prova que o script está sincronizado com o changeset).
- `.claude/skills/codebase-map/SKILL.md` — atualizar "próximo número livre" para **0020**.

**Acceptance Criteria**:
1. WHEN o Liquibase roda em Postgres limpo (`./mvnw spring-boot:run` em dev) THEN SHALL criar os 3 índices sem erro e sem tocar changeSets anteriores (`git diff` de 0001–0018 vazio).
2. WHEN `rollback` do 0019 é executado THEN os 3 índices SHALL ser removidos.
3. WHEN o schema H2 de teste é carregado THEN `INFORMATION_SCHEMA.INDEX_COLUMNS` SHALL conter os 3 índices com as colunas na ordem especificada.
4. WHEN a suíte roda THEN SHALL continuar verde (índice não muda resultado de query).

**Independent Test**: `SchemaTestIndicesTest` + verificação manual do `EXPLAIN` em dev para `somarDebitosPorPeriodo` (Index Scan em vez de Seq Scan com dados do `DataLoader`) — registrar a saída no PR.

**Não incluído (consciente):** índice em `nota_fiscal(status)` para `findByStatus(PENDENTE_SEFAZ)` do `SefazStatusSyncJob` — a issue não pede; deixar como sugestão no PR se o `EXPLAIN` mostrar Seq Scan relevante.

---

### P1: #98 — HikariCP com tuning, timeouts e leak-detection ⭐

**User Story**: Como operador, quero que uma conexão presa ou vazada apareça no log em 30s e que uma query/transação zumbi seja cortada, em vez de esgotar o pool silenciosamente.

**Why P1**: Instrumentação necessária para a Onda 3 (#97/#99) — sem leak-detection não há como provar o fix.

**Arquivos e valores (todos por env, ver `.env.example`):**

`application.yml` (base, vale para dev/test/prod):
```yaml
spring:
  datasource:
    hikari:
      pool-name: agrocontabil-pool
      maximum-pool-size: ${DB_POOL_SIZE:10}
      minimum-idle: ${DB_POOL_MIN_IDLE:2}
      connection-timeout: ${DB_CONNECTION_TIMEOUT_MS:5000}       # espera por conexão do pool
      idle-timeout: ${DB_IDLE_TIMEOUT_MS:600000}                 # 10 min
      max-lifetime: ${DB_MAX_LIFETIME_MS:1800000}                # 30 min (< timeout de infra)
      leak-detection-threshold: ${DB_LEAK_DETECTION_MS:30000}    # 30 s — expõe #97/#99
      validation-timeout: ${DB_VALIDATION_TIMEOUT_MS:3000}
  transaction:
    default-timeout: ${DB_TX_TIMEOUT:30s}                        # D5
```
`application-dev.yml` e `application-prod.yml` (Postgres apenas — D3):
```yaml
spring:
  datasource:
    hikari:
      data-source-properties:
        options: -c statement_timeout=${DB_STATEMENT_TIMEOUT_MS:30000}
```
- `application-prod.yml` — remover o `hikari.maximum-pool-size` duplicado (passa a vir do base).
- `.env.example` — bloco "Pool de conexoes" com todas as envs acima e comentário de cada uma.
- `compose.yaml` — só se houver env de pool lá (verificar); provavelmente nada.

**Testes:**
- `src/test/java/br/com/agrocontabil/config/DataSourceConfigTest.java` — `@SpringBootTest` (profile `test`, H2) que injeta `DataSource`, faz `unwrap(HikariDataSource.class)` e afirma `maximumPoolSize=10`, `minimumIdle=2`, `connectionTimeout=5000`, `maxLifetime=1800000`, `leakDetectionThreshold=30000`, `poolName`; injeta `PlatformTransactionManager` (`JpaTransactionManager`) e afirma `getDefaultTimeout()==30`.
- Statement timeout (Postgres) não é testável em H2 — critério manual em dev: `SELECT pg_sleep(60)` via `psql` na mesma config de conexão retorna `canceling statement due to statement timeout`. Documentar no PR.

**Acceptance Criteria**:
1. WHEN o contexto sobe em qualquer profile THEN o `HikariDataSource` SHALL ter os valores default acima, sobrescritíveis por env.
2. WHEN uma conexão fica fora do pool > 30s THEN o Hikari SHALL logar `Connection leak detection triggered` com stack trace (WARN) — sem interromper a request.
3. WHEN uma transação Spring ultrapassa 30s THEN SHALL falhar com `TransactionTimedOutException` (mapeada pelo `GlobalExceptionHandler` para 5xx genérico — verificar se cai no handler de fallback; se cair em 500 sem `errors[]`, ajustar o handler no mesmo PR).
4. WHEN uma query no Postgres (dev/prod) excede 30s THEN o driver SHALL abortar com `statement_timeout`.
5. WHEN a suíte roda em H2 THEN SHALL continuar verde (nenhuma propriedade Postgres no base).

**Independent Test**: `DataSourceConfigTest` falha na branch anterior (defaults do Hikari: 30000/1800000/0) e passa com o PR.

---

### P1: #101 — Scheduler com pool dedicado e graceful shutdown ⭐

**User Story**: Como operador, quero que `SefazStatusSyncJob`, `ApuracaoFechamentoJob` e `GuiaFiscalStatusSyncJob` não disputem uma única thread, e que um deploy espere os jobs/requests em andamento terminarem (até 30s) antes de derrubar o processo.

**Why P1**: Um fechamento mensal interrompido no meio por SIGTERM é o pior cenário de integridade da apuração (RN004).

**Arquivos:**

`application.yml`:
```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:3}          # 1 por job hoje; ShedLock (#87) cuida da concorrência entre instâncias
      thread-name-prefix: agro-sched-
      shutdown:
        await-termination: true
        await-termination-period: ${SCHEDULER_SHUTDOWN_TIMEOUT:30s}
  lifecycle:
    timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:30s}
server:
  shutdown: graceful
```
- `config/SchedulingConfig.java` — sem código novo (D4); só atualizar o Javadoc apontando que o pool é o `ThreadPoolTaskScheduler` autoconfigurado pelo Boot com `spring.task.scheduling.*`, e listar os 3 jobs.
- `.env.example` — `SCHEDULER_POOL_SIZE`, `SCHEDULER_SHUTDOWN_TIMEOUT`, `SHUTDOWN_TIMEOUT`.
- `compose.yaml` (serviço `backend`, profile `full`) — adicionar `stop_grace_period: 35s` (default do Docker é 10s, que mataria o processo antes dos 30s de graceful). O `Dockerfile` já usa `ENTRYPOINT` em exec-form (`["java", "-jar", "app.jar"]`), então o JVM é PID 1 e recebe o SIGTERM — nada a mudar lá.

**Testes:**
- `src/test/java/br/com/agrocontabil/config/SchedulingConfigTest.java` — `@SpringBootTest` (profile `test`; os jobs estão desligados por propriedade, mas o scheduler existe): injeta `ThreadPoolTaskScheduler` e afirma `getPoolSize()==3`, `getThreadNamePrefix()=="agro-sched-"`; injeta `ServerProperties` e afirma `getShutdown()==Shutdown.GRACEFUL`; lê `spring.lifecycle.timeout-per-shutdown-phase` e `spring.task.scheduling.shutdown.await-termination` via `Environment`.
- Graceful real (SIGTERM com request em voo) — não automatizável no CI do TCC; critério manual: subir em dev, disparar `GET /api/notas/{id}/pdf` e `kill -TERM`; o log SHALL mostrar `Commencing graceful shutdown. Waiting for active requests to complete` e a resposta chegar.

**Acceptance Criteria**:
1. WHEN o contexto sobe THEN SHALL existir um `ThreadPoolTaskScheduler` com 3 threads e prefixo `agro-sched-`.
2. WHEN dois jobs disparam no mesmo instante THEN SHALL rodar em threads distintas (`agro-sched-1`, `agro-sched-2`) — visível no log.
3. WHEN o processo recebe SIGTERM THEN o Tomcat SHALL parar de aceitar conexões e esperar até 30s pelas requests ativas; o scheduler SHALL esperar até 30s pelos jobs ativos; após isso encerra.
4. WHEN a suíte roda THEN SHALL continuar verde (jobs desabilitados em `application-test.yml` continuam desabilitados).

**Independent Test**: `SchedulingConfigTest` falha na branch anterior (pool 1, prefixo `scheduling-`, shutdown IMMEDIATE) e passa com o PR.

---

### P2: #89 (parte rápida) — `GET /{id}/xml` responde 409 quando XML não gerado

**User Story**: Como dev do Angular, quero que "nota existe mas recurso ainda não gerado" seja sempre **409** (XML e PDF), e 404 signifique só "nota não existe / não é sua", para tratar os dois casos de forma uniforme.

**Why P2**: Consistência de contrato; baixo esforço; aprovado como encaixe (D2).

**Arquivos:**
- `common/ErrorCode.java` — novo `XML_INDISPONIVEL` (doc: "XML da NF-e ainda não gerado. HTTP 409."), ao lado de `DANFE_INDISPONIVEL`.
- `exception/XmlIndisponivelException.java` — **nova**, espelho de `DanfeIndisponivelException` (409, `ErrorDetail.of(XML_INDISPONIVEL, "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de baixá-lo.")`).
- `service/NotaFiscalService.obterXml` (linha ~316) — trocar `ResourceNotFoundException` por `XmlIndisponivelException(id, nota.getStatus())`; atualizar Javadoc `@throws`.
- `controller/NotaFiscalController` — Javadoc/`@ApiResponse` do endpoint `/xml` (409 em vez de 404 para "não gerado"), se houver anotação OpenAPI.
- Testes: `NotaFiscalServiceTest.obterXmlShouldThrowWhenXmlNotYetGenerated` (linha 777) passa a esperar `XmlIndisponivelException`; `NotaFiscalControllerTest` (linha ~453) passa a esperar `409` + `errors[0].code == "XML_INDISPONIVEL"`; manter o teste de 404 para nota de outro produtor.
- `CLAUDE.md` + `codebase-map` — registrar `XmlIndisponivelException` na tabela de exceções.

**Acceptance Criteria**:
1. WHEN `GET /api/notas/{id}/xml` para nota do produtor em RASCUNHO/COM_INCONSISTENCIAS (sem `xml_gerado`) THEN SHALL responder **409** com `errors[0].code = XML_INDISPONIVEL` e `suggestion` apontando `gerar-xml`.
2. WHEN `GET /api/notas/{id}/xml` para nota inexistente ou de outro produtor THEN SHALL responder **404** (anti-IDOR inalterado).
3. WHEN `GET /api/notas/{id}/xml` para nota com XML THEN SHALL responder 200 `application/xml` (inalterado).
4. WHEN `GET /api/notas/{id}/pdf` THEN SHALL manter 409 `DANFE_INDISPONIVEL` (inalterado — só documentar a simetria).

**Independent Test**: os dois testes ajustados falham na branch anterior e passam com o PR.

**Aviso ao frontend (registrar no PR e na issue #89):** se `NotasApi` do Angular trata 404 em `/xml` como "ainda não gerado", precisa passar a tratar 409.

---

## Edge Cases

- WHEN o script SQL roda em teste `@Transactional` THEN o DDL (`ALTER TABLE`) SHALL commitar implicitamente no H2 — mesmo comportamento dos `CREATE TABLE IF NOT EXISTS` de hoje; nada novo vaza entre testes.
- WHEN `DB_LEAK_DETECTION_MS` < 2000 THEN o Hikari ignora silenciosamente (mínimo 2s) — documentar no `.env.example`.
- WHEN `DB_MAX_LIFETIME_MS` ≤ `DB_LEAK_DETECTION_MS` THEN o Hikari desliga o leak-detection — documentar a ordem `leak < max-lifetime`.
- WHEN `DB_TX_TIMEOUT` é menor que uma importação de lote grande THEN a importação SHALL falhar com 5xx genérico e log detalhado — comportamento aceito; ajustar env, não o código.
- WHEN `SCHEDULER_POOL_SIZE` = 1 THEN volta ao comportamento atual (jobs serializados) — válido para ambientes mínimos.
- WHEN o índice `(produtor_id, status, data_emissao)` existe THEN `idx_nota_fiscal_produtor_id` fica redundante para queries por produtor — **não remover** nesta onda (changeSet 0002 não se edita; drop seria outro changeSet e outra decisão).

---

## Requirement Traceability

| ID | Story | Arquivo(s) principal(is) | Teste que prova | Status |
|----|-------|--------------------------|-----------------|--------|
| FK-01 | #90 RESTRICT produtor←nota | `schema-nota-fiscal-test.sql` | `SchemaTestIntegridadeReferencialTest#deveRejeitarExclusaoDeProdutorComNotas` | Pending |
| FK-02 | #90 CASCADE nota→item | idem | `#deveRemoverItensAoExcluirNotaPorSql` | Pending |
| FK-03 | #90 órfão item/apuração/guia rejeitado | idem | `#deveRejeitarFilhoOrfao*` (parametrizado) | Pending |
| FK-04 | #90 idempotência do script | idem | qualquer teste com 2+ métodos `@Sql` (já existe) | Pending |
| IDX-01 | #102 nota_fiscal composto | `0019-…xml`, master, script | `SchemaTestIndicesTest` + EXPLAIN manual | Pending |
| IDX-02 | #102 guias (status, vencimento) | idem | `SchemaTestIndicesTest` | Pending |
| IDX-03 | #102 guias (produtor, vencimento) | idem | `SchemaTestIndicesTest` | Pending |
| POOL-01 | #98 hikari defaults + env | `application.yml`, `.env.example` | `DataSourceConfigTest` | Pending |
| POOL-02 | #98 tx default-timeout 30s | `application.yml` | `DataSourceConfigTest` | Pending |
| POOL-03 | #98 statement_timeout Postgres | `application-dev.yml`, `application-prod.yml` | manual (`pg_sleep`) | Pending |
| POOL-04 | #98 `TransactionTimedOutException` → envelope `errors[]` | `GlobalExceptionHandler` (só se necessário) | `GlobalExceptionHandlerTest` | Pending |
| SCHED-01 | #101 pool 3 + prefixo | `application.yml` | `SchedulingConfigTest` | Pending |
| SCHED-02 | #101 await-termination 30s | `application.yml` | `SchedulingConfigTest` | Pending |
| SCHED-03 | #101 `server.shutdown=graceful` + lifecycle 30s | `application.yml` | `SchedulingConfigTest` + manual SIGTERM | Pending |
| ERR-01 | #89 `/xml` 409 XML_INDISPONIVEL | `XmlIndisponivelException`, `ErrorCode`, `NotaFiscalService` | `NotaFiscalServiceTest`, `NotaFiscalControllerTest` | Pending |
| ERR-02 | #89 `/xml` 404 só p/ nota inexistente/alheia | `NotaFiscalService` | testes existentes de anti-IDOR | Pending |

**Coverage:** 16 requisitos, 13 automatizados, 3 com verificação manual documentada no PR (POOL-03, SCHED-03 parcial, IDX EXPLAIN).

---

## Sequência de PRs (Fase 3/4 — após aprovação)

Um PR por issue, cada um em worktree `.claude/.worktrees/<branch>`, base `main`, Conventional Commits, `./mvnw test` verde antes de abrir, review Copilot + auditoria OWASP como nos PRs #78–#82.

| Ordem | Branch | Título do PR | Commits previstos | Fecha |
|-------|--------|--------------|-------------------|-------|
| 1 | `fix/90-fks-schema-teste` | `test(schema): replica FKs do Liquibase no schema H2 com ON DELETE` | `test:` script + teste | Closes #90 |
| 2 | `chore/102-indices-compostos` | `perf(db): indices compostos para dashboard/apuracao e job de guias` | `perf:` changeset 0019 + master; `test:` script + teste; `docs:` codebase-map | Closes #102 |
| 3 | `chore/98-hikari-timeouts` | `chore(datasource): tuning HikariCP, leak-detection e timeouts de transacao/statement` | `chore:` yml + env.example; `test:` DataSourceConfigTest; (`fix:` handler se POOL-04 precisar) | Closes #98 |
| 4 | `chore/101-scheduler-graceful-shutdown` | `chore(scheduler): pool dedicado de 3 threads e graceful shutdown de 30s` | `chore:` yml + Javadoc + env.example; `test:` SchedulingConfigTest | Closes #101 |
| 5 | `fix/89-xml-409-nao-gerado` | `fix(nota-fiscal): GET /{id}/xml responde 409 quando XML ainda nao foi gerado` | `fix:` exceção + service; `test:` ajustes; `docs:` CLAUDE.md/codebase-map | Refs #89 (parte rápida; issue continua aberta para o preview de DANFE) |

Dependências: 1 → 2 (mesmo arquivo `schema-nota-fiscal-test.sql`; rebase simples). 3, 4 e 5 são independentes entre si e de 1–2; podem ser paralelizados se o Murillo quiser, mas a ordem acima evita conflitos em `application.yml` (3 e 4 tocam o mesmo arquivo — fazer 4 após merge de 3).

Cada PR atualiza o `CLAUDE.md` (seção "Estado atual"/"Débitos técnicos") só se mudar algo que ele descreve; o fechamento da onda adiciona um parágrafo "Onda 1 — Fundação (concluída)".

---

## Success Criteria

- [ ] 5 PRs mergeados em `main`, issues #90, #102, #98, #101 fechadas; #89 com comentário "parte rápida entregue no PR #N".
- [ ] Suíte ≥ 367 + novos testes (estimativa: +10 a +14), 100% verde em cada PR.
- [ ] `git diff main -- src/main/resources/db/changelog/changes/` só adiciona o `0019` (nenhum changeSet antigo editado).
- [ ] `.env.example` documenta todas as 12 envs novas com default e unidade.
- [ ] Nenhuma propriedade específica de Postgres no `application.yml` base (suíte H2 intacta).
- [ ] Log de dev mostra threads `agro-sched-N` e, ao `kill -TERM`, `Commencing graceful shutdown`.

# Onda 1 — Fundação de escalabilidade — Plano de Sprint (tasks)

**Spec:** `.specs/features/onda-1-fundacao/spec.md` (aprovado por Murillo em 2026-09-21 — fonte de verdade; este arquivo NÃO altera escopo, valores nem decisões D1–D5).
**Sprint:** única, "Onda 1" · **Duração:** 2 semanas (padrão acadêmico) · **Início:** 2026-09-22 · **Fim previsto:** 2026-10-05.
**Time:** Murillo Weiss (dev) + agentes Claude Code.
**Base:** `main` · suíte atual 367 testes verdes (`./mvnw test`, profile `test`, H2).

## Objetivo da sprint

Entregar, em 5 PRs pequenos e independentes, as redes de segurança (FKs no H2, índices compostos, pool/timeouts do Hikari, scheduler com pool e graceful shutdown) que as Ondas 2–7 exigem, mais a correção de contrato `GET /{id}/xml` 404→409, sem mudar nenhuma regra de negócio.

## Definition of Done da sprint

Reaproveita os *Success Criteria* do spec + regras do `CLAUDE.md`:

- [ ] 5 PRs mergeados em `main`; issues #90, #102, #98, #101 fechadas; #89 com comentário "parte rápida entregue no PR #N" (issue continua aberta para o preview de DANFE).
- [ ] Suíte ≥ 367 + novos testes (estimativa +10 a +14), **100% verde em cada PR** (`./mvnw test`).
- [ ] Cada correção tem teste que falharia sem ela (TDD: teste vermelho → implementação → verde).
- [ ] `git diff main -- src/main/resources/db/changelog/changes/` só adiciona o `0019` — **nenhum changeSet antigo editado** (regra do CLAUDE.md).
- [ ] `.env.example` documenta todas as **12 envs novas** com default e unidade (9 do #98 + 3 do #101).
- [ ] Nenhuma propriedade específica de Postgres no `application.yml` base (D3 — suíte H2 intacta).
- [ ] Log de dev mostra threads `agro-sched-N` e, ao `kill -TERM`, `Commencing graceful shutdown`.
- [ ] Cada PR: 1 responsabilidade lógica, deployável por si só; branch `fix/`/`chore/` em worktree isolado; Conventional Commits em português; review GitHub Copilot + auditoria OWASP + CI verde (como nos PRs #78–#82).
- [ ] Hibernate continua em `ddl-auto: validate` em dev/prod (nenhuma task mexe nisso).
- [ ] `CLAUDE.md` atualizado só quando o PR muda algo que ele descreve; ao fechar a onda, parágrafo "Onda 1 — Fundação (concluída)".

## Estimativas (resumo)

| Épico | Issue | SP | T-shirt | Justificativa |
|-------|-------|----|---------|---------------|
| E1 | #90 FKs no H2 | 3 | S | Só SQL + 1 classe de teste; risco moderado de cleanup de fixtures quebrar por RESTRICT (AC7 / risco conhecido). |
| E2 | #102 Índices compostos | 3 | S | Changeset + script + teste de `INFORMATION_SCHEMA`; verificação manual de EXPLAIN/rollback em dev. |
| E3 | #98 HikariCP | 5 | M | 3 arquivos yml + `.env.example` + teste de config; POOL-04 pode exigir ajuste no `GlobalExceptionHandler`; verificação manual `pg_sleep`. Mais incerteza que os demais. |
| E4 | #101 Scheduler/graceful | 3 | S | Só propriedades (D4) + Javadoc + compose; verificação manual SIGTERM. |
| E5 | #89 `/xml` 409 | 2 | S | Exceção espelho de `DanfeIndisponivelException` + 1 linha no service + 2 testes ajustados; muda contrato do Angular. |
| **Total** | | **16** | | Cabe com folga em 2 semanas; sobra serve para lidar com o risco de fixtures (E1) e POOL-04 (E3). |

Priorização por valor: E1 e E2 primeiro (rede de segurança e compartilham o script SQL); E3 antes de E4 (compartilham `application.yml`); E5 por último (independente, menor valor, aprovado como encaixe — D2).

---

## Épico E1 — #90: FKs de produção replicadas no schema H2 (P1)

**História de usuário:** Como dev do backend, quero que o H2 dos testes tenha as mesmas FKs do Postgres, para que um teste que viola integridade referencial falhe no CI em vez de só em produção.

**Estimativa:** 3 SP · **S**
**Branch:** `fix/90-fks-schema-teste`
**PR:** `test(schema): replica FKs do Liquibase no schema H2 com ON DELETE` · Closes #90
**Decisão aplicada:** D1 (`ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS ... ON DELETE ...`, sem tocar entidades).

### T1.1 — Criar o teste de integridade referencial (vermelho)

- **What:** Criar `SchemaTestIntegridadeReferencialTest` como `@DataJpaTest` (`ddl-auto=none` + `@Sql`) usando `JdbcTemplate` para DML nativo, cobrindo RESTRICT, CASCADE e filho órfão.
- **Where:** `src/test/java/br/com/agrocontabil/repository/SchemaTestIntegridadeReferencialTest.java`
- **Depends on:** —
- **Reuses:** setup de `NotaFiscalRepositoryTest` (`ddl-auto=none` + `@Sql` do `schema-nota-fiscal-test.sql`); `JdbcTemplate` para não deixar o Hibernate mascarar o CASCADE/RESTRICT com cascade próprio.
- **Done when:** existem os métodos `deveRejeitarExclusaoDeProdutorComNotas` (FK-01), `deveRemoverItensAoExcluirNotaPorSql` (FK-02), `deveRejeitarFilhoOrfao*` parametrizado para `nota_fiscal_item`/`apuracao_mensal`/`guias_fiscais` (FK-03) e um caso para exclusão de produtor com `apuracao_mensal`/`guias_fiscais` (AC5); a classe **falha** na branch atual (sem FKs no H2).
- **Tests:** `SchemaTestIntegridadeReferencialTest#deveRejeitarExclusaoDeProdutorComNotas`, `#deveRemoverItensAoExcluirNotaPorSql`, `#deveRejeitarFilhoOrfao*`.
- **Gate:** `./mvnw test -Dtest=SchemaTestIntegridadeReferencialTest` → **deve falhar** (vermelho esperado).
- **Commit:** `test(schema): adiciona teste de integridade referencial do schema H2`

### T1.2 — Adicionar o bloco `ALTER TABLE` ao script H2

- **What:** Acrescentar, ao final do script (após todos os `CREATE TABLE`), as 4 constraints `fk_nota_fiscal_produtor` (RESTRICT), `fk_nota_fiscal_item_nota` (CASCADE), `fk_apuracao_produtor` e `fk_guia_fiscal_produtor` (default NO ACTION), com `IF NOT EXISTS`, mais comentário explicando D1 e "manter sincronizado com 0002/0014/0018".
- **Where:** `src/test/resources/schema-nota-fiscal-test.sql`
- **Depends on:** T1.1
- **Reuses:** changeSets `0002`, `0014`, `0018` do Liquibase como fonte dos nomes e ações `ON DELETE`; padrão `IF NOT EXISTS` já usado nos `CREATE TABLE`/`CREATE INDEX` do script.
- **Done when:** FK-01, FK-02, FK-03 verdes; FK-04 (idempotência) coberto por qualquer teste existente com 2+ métodos `@Sql` continuando verde; nenhuma entidade JPA alterada (`git diff src/main` vazio).
- **Tests:** `SchemaTestIntegridadeReferencialTest` (todos os métodos) + `NotaFiscalRepositoryTest` (idempotência, FK-04).
- **Gate:** `./mvnw test -Dtest=SchemaTestIntegridadeReferencialTest,NotaFiscalRepositoryTest`
- **Commit:** `test(schema): replica FKs do Liquibase no schema H2 com ON DELETE`

### T1.3 — Suíte completa e correção de cleanup de fixtures (se necessário)

- **What:** Rodar a suíte inteira; se algum `@SpringBootTest` sem `@Transactional` deixar `apuracao_mensal`/`guias_fiscais` órfãos e quebrar o `produtorRepository.deleteAll()` de outro teste por RESTRICT, corrigir a **ordem/abrangência do cleanup daquele teste** — nunca afrouxar a FK.
- **Where:** `src/test/java/**` (somente o teste ofensor, se houver); `schema-nota-fiscal-test.sql` **não** é alterado nesta task.
- **Depends on:** T1.2
- **Reuses:** os 9 testes que já apagam filhos antes de `produtores` (verificados no spec) como modelo de ordem de cleanup.
- **Done when:** AC7 — 367 testes existentes + novos verdes; `git diff main -- src/main` vazio.
- **Tests:** suíte completa.
- **Gate:** `./mvnw test`
- **Commit:** (só se houver ajuste) `test(fixtures): ajusta ordem de cleanup para respeitar FK RESTRICT` — caso contrário, sem commit.

### T1.4 — Abrir PR #90

- **What:** Abrir o PR com o título da tabela do spec, descrição citando D1, a tabela de FKs e o débito registrado (sem FK em `nota_fiscal_validacao` — Liquibase 0006 não define); passar por Copilot + OWASP + CI.
- **Where:** GitHub (PR) — sem alteração de código.
- **Depends on:** T1.3
- **Reuses:** fluxo dos PRs #78–#82.
- **Done when:** CI verde, review Copilot respondido, auditoria OWASP sem achado, PR mergeado em `main`, issue #90 fechada.
- **Tests:** CI (`./mvnw test`).
- **Gate:** CI verde.
- **Commit:** — (merge)

---

## Épico E2 — #102: Índices compostos (P1)

**História de usuário:** Como produtor usando o dashboard, quero que os totais do mês respondam rápido mesmo com milhares de notas, e como operador quero que o job de guias não faça full scan diário.

**Estimativa:** 3 SP · **S**
**Branch:** `chore/102-indices-compostos` (criar a partir de `main` **após** merge de E1 — mesmo arquivo `schema-nota-fiscal-test.sql`)
**PR:** `perf(db): indices compostos para dashboard/apuracao e job de guias` · Closes #102

### T2.1 [P] — Criar o teste de índices do H2 (vermelho)

- **What:** Criar `SchemaTestIndicesTest` (`@DataJpaTest`) que consulta `INFORMATION_SCHEMA.INDEX_COLUMNS` do H2 e afirma nome + ordem das colunas dos 3 índices.
- **Where:** `src/test/java/br/com/agrocontabil/repository/SchemaTestIndicesTest.java`
- **Depends on:** — (E1 mergeado)
- **Reuses:** setup de `NotaFiscalRepositoryTest` / `SchemaTestIntegridadeReferencialTest` (`ddl-auto=none` + `@Sql`).
- **Done when:** o teste afirma `idx_nota_fiscal_produtor_status_emissao (produtor_id, status, data_emissao)`, `idx_guia_fiscal_status_vencimento (status, data_vencimento)` e `idx_guia_fiscal_produtor_vencimento (produtor_id, data_vencimento)` e **falha** na branch atual.
- **Tests:** `SchemaTestIndicesTest`.
- **Gate:** `./mvnw test -Dtest=SchemaTestIndicesTest` → **deve falhar**.
- **Commit:** `test(schema): adiciona teste de indices compostos no schema H2`

### T2.2 [P] — Criar o changelog 0019 com 3 changeSets

- **What:** Criar o arquivo `0019-indices-compostos-escalabilidade.xml` com 3 changeSets (um por índice), cada um com `<rollback><dropIndex/></rollback>`, cabeçalho no padrão do `0016`, author `supersonic-team`, IDs `0019-index-nota-fiscal-produtor-status-emissao`, `0019-index-guia-fiscal-status-vencimento`, `0019-index-guia-fiscal-produtor-vencimento`.
- **Where:** `src/main/resources/db/changelog/changes/0019-indices-compostos-escalabilidade.xml`
- **Depends on:** —
- **Reuses:** `0016-apuracao-status-periodo-index.xml` (cabeçalho, estrutura de `createIndex` + `rollback`).
- **Done when:** IDX-01/02/03 — arquivo existe com os 3 changeSets e rollbacks; nenhum changeSet 0001–0018 tocado.
- **Tests:** — (validado por T2.3 e T2.6).
- **Gate:** `git diff main -- src/main/resources/db/changelog/changes/` mostra apenas o 0019.
- **Commit:** `perf(db): cria changeset 0019 com indices compostos de dashboard e guias`

### T2.3 — Incluir o 0019 no changelog master

- **What:** Adicionar o `include` do 0019 ao final do master.
- **Where:** `src/main/resources/db/changelog/db.changelog-master.yaml`
- **Depends on:** T2.2
- **Reuses:** `include` existente do 0018.
- **Done when:** o Liquibase em Postgres limpo (`./mvnw spring-boot:run` em dev) cria os 3 índices sem erro (AC1).
- **Tests:** subida do contexto em dev (manual, ver T2.6).
- **Gate:** `./mvnw spring-boot:run` sobe sem erro de Liquibase/`ddl-auto: validate`.
- **Commit:** `perf(db): inclui changeset 0019 no changelog master`

### T2.4 — Replicar os 3 índices no script H2

- **What:** Adicionar os 3 `CREATE INDEX IF NOT EXISTS` ao script de teste, com as mesmas colunas e ordem do 0019.
- **Where:** `src/test/resources/schema-nota-fiscal-test.sql`
- **Depends on:** T2.1, T2.2
- **Reuses:** padrão já usado para `idx_apuracao_status_periodo` no mesmo script.
- **Done when:** AC3 — `SchemaTestIndicesTest` verde (IDX-01, IDX-02, IDX-03).
- **Tests:** `SchemaTestIndicesTest`.
- **Gate:** `./mvnw test -Dtest=SchemaTestIndicesTest`
- **Commit:** `test(schema): replica indices compostos do changeset 0019 no schema H2`

### T2.5 [P] — Atualizar "próximo número livre" na skill codebase-map

- **What:** Atualizar o próximo número livre de changeSet para **0020**.
- **Where:** `.claude/skills/codebase-map/SKILL.md`
- **Depends on:** T2.2
- **Reuses:** regra de manutenção do CLAUDE.md ("ao adicionar pacote/classe significativa, atualize `codebase-map`").
- **Done when:** a skill aponta 0020 como próximo número livre.
- **Tests:** —
- **Gate:** revisão do diff.
- **Commit:** `docs(codebase-map): proximo changeset livre passa a 0020`

### T2.6 — Verificação manual documentada no PR: EXPLAIN e rollback em dev

- **What:** Em dev (Postgres com dados do `DataLoader`), rodar `EXPLAIN` da query de `somarDebitosPorPeriodo` e confirmar Index Scan (em vez de Seq Scan) usando o índice novo; executar o rollback do 0019 e confirmar que os 3 índices somem (AC2); registrar as saídas na descrição do PR. Se o `EXPLAIN` mostrar Seq Scan relevante em `findByStatus(PENDENTE_SEFAZ)`, deixar apenas como **sugestão** no PR (não incluído nesta onda).
- **Where:** descrição do PR (sem alteração de código).
- **Depends on:** T2.3, T2.4
- **Reuses:** `DataLoader` (seed de dev).
- **Done when:** IDX-01 (parte manual) e AC2 documentados no PR com a saída do `EXPLAIN` e do rollback.
- **Tests:** manual.
- **Gate:** saídas coladas no PR.
- **Commit:** —

### T2.7 — Suíte completa e PR #102

- **What:** Rodar suíte completa, abrir PR com título do spec, passar por Copilot + OWASP + CI, mergear.
- **Where:** GitHub (PR).
- **Depends on:** T2.4, T2.5, T2.6
- **Reuses:** fluxo dos PRs #78–#82.
- **Done when:** AC4 — suíte verde; `git diff main -- src/main/resources/db/changelog/changes/` só adiciona o 0019; issue #102 fechada.
- **Tests:** suíte completa.
- **Gate:** `./mvnw test`
- **Commit:** — (merge)

---

## Épico E3 — #98: HikariCP com tuning, timeouts e leak-detection (P1)

**História de usuário:** Como operador, quero que uma conexão presa ou vazada apareça no log em 30s e que uma query/transação zumbi seja cortada, em vez de esgotar o pool silenciosamente.

**Estimativa:** 5 SP · **M**
**Branch:** `chore/98-hikari-timeouts`
**PR:** `chore(datasource): tuning HikariCP, leak-detection e timeouts de transacao/statement` · Closes #98
**Decisões aplicadas:** D3 (statement_timeout só em dev/prod), D5 (`default-timeout` 30s, env `DB_TX_TIMEOUT`).

### T3.1 — Criar `DataSourceConfigTest` (vermelho)

- **What:** Criar `@SpringBootTest` (profile `test`, H2) que injeta `DataSource`, faz `unwrap(HikariDataSource.class)` e afirma `maximumPoolSize=10`, `minimumIdle=2`, `connectionTimeout=5000`, `maxLifetime=1800000`, `leakDetectionThreshold=30000`, `poolName="agrocontabil-pool"`; injeta `PlatformTransactionManager` (`JpaTransactionManager`) e afirma `getDefaultTimeout()==30`.
- **Where:** `src/test/java/br/com/agrocontabil/config/DataSourceConfigTest.java`
- **Depends on:** —
- **Reuses:** padrão de `@SpringBootTest` com profile `test` já usado (ex.: `AgroContabilApplicationTests`, `CorsIntegrationTest`).
- **Done when:** o teste **falha** na branch atual (defaults do Hikari 30000/1800000/0 e timeout de transação padrão).
- **Tests:** `DataSourceConfigTest`.
- **Gate:** `./mvnw test -Dtest=DataSourceConfigTest` → **deve falhar**.
- **Commit:** `test(datasource): adiciona teste de configuracao do pool HikariCP e timeout de transacao`

### T3.2 — Configurar Hikari e `default-timeout` no `application.yml` base

- **What:** Adicionar o bloco `spring.datasource.hikari` (pool-name, maximum-pool-size, minimum-idle, connection-timeout, idle-timeout, max-lifetime, leak-detection-threshold, validation-timeout) e `spring.transaction.default-timeout`, todos com `${ENV:default}` exatamente como no spec. **Nenhuma** propriedade Postgres aqui (D3).
- **Where:** `src/main/resources/application.yml`
- **Depends on:** T3.1
- **Reuses:** padrão `${ENV:default}` já usado no yml (ex.: `${DB_PASSWORD}`, `${APP_CORS_ALLOWED_ORIGINS}`).
- **Done when:** POOL-01 e POOL-02 — `DataSourceConfigTest` verde; suíte H2 continua subindo (AC5).
- **Tests:** `DataSourceConfigTest`.
- **Gate:** `./mvnw test -Dtest=DataSourceConfigTest`
- **Commit:** `chore(datasource): parametriza pool HikariCP, leak-detection e timeout padrao de transacao`

### T3.3 [P] — `statement_timeout` em dev/prod e remoção de duplicata no prod

- **What:** Adicionar `spring.datasource.hikari.data-source-properties.options: -c statement_timeout=${DB_STATEMENT_TIMEOUT_MS:30000}` em `application-dev.yml` e `application-prod.yml`; remover o `hikari.maximum-pool-size` duplicado do prod (passa a vir do base).
- **Where:** `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml`
- **Depends on:** T3.2
- **Reuses:** D3 (o driver H2 rejeita propriedades desconhecidas — por isso fora do base).
- **Done when:** POOL-03 (parte de configuração) — os dois profiles têm o `options`; prod sem `maximum-pool-size` próprio; `application.yml` base sem nenhuma propriedade Postgres.
- **Tests:** suíte H2 (prova que nada vazou para o base); manual em T3.6.
- **Gate:** `./mvnw test` (contexto `test` sobe sem `UNSUPPORTED_SETTING`).
- **Commit:** `chore(datasource): aplica statement_timeout do Postgres nos profiles dev e prod`

### T3.4 [P] — Documentar as 9 envs de pool no `.env.example`

- **What:** Adicionar bloco "Pool de conexoes" com `DB_POOL_SIZE`, `DB_POOL_MIN_IDLE`, `DB_CONNECTION_TIMEOUT_MS`, `DB_IDLE_TIMEOUT_MS`, `DB_MAX_LIFETIME_MS`, `DB_LEAK_DETECTION_MS`, `DB_VALIDATION_TIMEOUT_MS`, `DB_TX_TIMEOUT`, `DB_STATEMENT_TIMEOUT_MS`, cada uma com default, unidade e comentário; documentar os edge cases: `DB_LEAK_DETECTION_MS` < 2000 é ignorado pelo Hikari, e `leak < max-lifetime` (senão o leak-detection é desligado).
- **Where:** `.env.example`
- **Depends on:** T3.2
- **Reuses:** formato dos blocos existentes do `.env.example` (ex.: `NFE_CERT_*`).
- **Done when:** as 9 envs estão documentadas com default e unidade; `compose.yaml` verificado — só alterar se houver env de pool lá (spec: "provavelmente nada").
- **Tests:** —
- **Gate:** revisão do diff.
- **Commit:** `docs(env): documenta variaveis do pool HikariCP e timeouts`

### T3.5 — POOL-04: garantir `TransactionTimedOutException` no envelope `errors[]`

- **What:** Verificar se `TransactionTimedOutException` cai no handler de fallback do `GlobalExceptionHandler` (5xx genérico com `errors[]`). Se cair em 500 **sem** envelope, ajustar o handler no mesmo PR; caso contrário, apenas adicionar o teste que prova o comportamento.
- **Where:** `src/test/java/br/com/agrocontabil/exception/GlobalExceptionHandlerTest.java`; `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java` (**só se necessário**)
- **Depends on:** T3.2
- **Reuses:** handler de fallback existente e mapeamento de `DataIntegrityViolationException` (PR #82) como modelo de teste.
- **Done when:** POOL-04 — `GlobalExceptionHandlerTest` tem caso para `TransactionTimedOutException` retornando 5xx com `errors[]` e mensagem genérica (detalhe só no log).
- **Tests:** `GlobalExceptionHandlerTest` (novo método para `TransactionTimedOutException`).
- **Gate:** `./mvnw test -Dtest=GlobalExceptionHandlerTest`
- **Commit:** `test(erro): cobre TransactionTimedOutException no envelope errors[]` — ou, se precisar ajustar o handler, `fix(erro): mapeia TransactionTimedOutException para 5xx com envelope errors[]`

### T3.6 — Verificação manual documentada no PR: `statement_timeout` e leak-detection em dev

- **What:** Em dev (Postgres), executar `SELECT pg_sleep(60)` via `psql` com a mesma config de conexão e confirmar `canceling statement due to statement timeout`; opcionalmente observar o log `Connection leak detection triggered` (WARN) sem interromper a request (AC2). Registrar as saídas no PR.
- **Where:** descrição do PR.
- **Depends on:** T3.3
- **Reuses:** —
- **Done when:** POOL-03 (parte manual) documentado no PR.
- **Tests:** manual.
- **Gate:** saída colada no PR.
- **Commit:** —

### T3.7 — Suíte completa e PR #98

- **What:** Rodar suíte completa, abrir PR com título do spec, Copilot + OWASP + CI, mergear.
- **Where:** GitHub (PR).
- **Depends on:** T3.4, T3.5, T3.6
- **Reuses:** fluxo dos PRs #78–#82.
- **Done when:** AC1–AC5 do #98; suíte verde; issue #98 fechada.
- **Tests:** suíte completa.
- **Gate:** `./mvnw test`
- **Commit:** — (merge)

---

## Épico E4 — #101: Scheduler com pool dedicado e graceful shutdown (P1)

**História de usuário:** Como operador, quero que `SefazStatusSyncJob`, `ApuracaoFechamentoJob` e `GuiaFiscalStatusSyncJob` não disputem uma única thread, e que um deploy espere os jobs/requests em andamento terminarem (até 30s) antes de derrubar o processo.

**Estimativa:** 3 SP · **S**
**Branch:** `chore/101-scheduler-graceful-shutdown` (criar a partir de `main` **após** merge de E3 — mesmo `application.yml`)
**PR:** `chore(scheduler): pool dedicado de 3 threads e graceful shutdown de 30s` · Closes #101
**Decisão aplicada:** D4 (propriedades `spring.task.scheduling.*`, sem bean manual).

### T4.1 — Criar `SchedulingConfigTest` (vermelho)

- **What:** Criar `@SpringBootTest` (profile `test`) que injeta `ThreadPoolTaskScheduler` e afirma `getPoolSize()==3` e `getThreadNamePrefix()=="agro-sched-"`; injeta `ServerProperties` e afirma `getShutdown()==Shutdown.GRACEFUL`; lê via `Environment` `spring.lifecycle.timeout-per-shutdown-phase` (30s) e `spring.task.scheduling.shutdown.await-termination` (true).
- **Where:** `src/test/java/br/com/agrocontabil/config/SchedulingConfigTest.java`
- **Depends on:** — (E3 mergeado)
- **Reuses:** `DataSourceConfigTest` (E3) como padrão de teste de configuração.
- **Done when:** o teste **falha** na branch atual (pool 1, prefixo `scheduling-`, shutdown IMMEDIATE).
- **Tests:** `SchedulingConfigTest`.
- **Gate:** `./mvnw test -Dtest=SchedulingConfigTest` → **deve falhar**.
- **Commit:** `test(scheduler): adiciona teste do pool do scheduler e do graceful shutdown`

### T4.2 — Configurar scheduler, lifecycle e `server.shutdown` no `application.yml`

- **What:** Adicionar `spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:3}`, `thread-name-prefix: agro-sched-`, `shutdown.await-termination: true`, `shutdown.await-termination-period=${SCHEDULER_SHUTDOWN_TIMEOUT:30s}`, `spring.lifecycle.timeout-per-shutdown-phase=${SHUTDOWN_TIMEOUT:30s}` e `server.shutdown: graceful`, exatamente como no spec.
- **Where:** `src/main/resources/application.yml`
- **Depends on:** T4.1
- **Reuses:** bloco `${ENV:default}` do E3 no mesmo arquivo.
- **Done when:** SCHED-01, SCHED-02, SCHED-03 (parte automatizada) — `SchedulingConfigTest` verde; jobs continuam desabilitados em `application-test.yml` (AC4).
- **Tests:** `SchedulingConfigTest`.
- **Gate:** `./mvnw test -Dtest=SchedulingConfigTest`
- **Commit:** `chore(scheduler): pool dedicado de 3 threads e graceful shutdown de 30s`

### T4.3 [P] — Atualizar Javadoc de `SchedulingConfig`

- **What:** Sem código novo (D4): atualizar o Javadoc apontando que o pool é o `ThreadPoolTaskScheduler` autoconfigurado pelo Boot via `spring.task.scheduling.*` e listar os 3 jobs (`SefazStatusSyncJob`, `ApuracaoFechamentoJob`, `GuiaFiscalStatusSyncJob`).
- **Where:** `src/main/java/br/com/agrocontabil/config/SchedulingConfig.java`
- **Depends on:** T4.2
- **Reuses:** Javadoc existente da classe.
- **Done when:** Javadoc atualizado; `git diff` da classe contém apenas comentário.
- **Tests:** —
- **Gate:** revisão do diff.
- **Commit:** `docs(scheduler): documenta pool autoconfigurado e jobs no SchedulingConfig`

### T4.4 [P] — Documentar as 3 envs do scheduler no `.env.example`

- **What:** Adicionar `SCHEDULER_POOL_SIZE` (default 3; `=1` volta ao comportamento serializado atual), `SCHEDULER_SHUTDOWN_TIMEOUT` (30s) e `SHUTDOWN_TIMEOUT` (30s) com comentário e unidade.
- **Where:** `.env.example`
- **Depends on:** T4.2
- **Reuses:** bloco "Pool de conexoes" do E3 como formato.
- **Done when:** as 3 envs documentadas (totalizando as 12 da onda).
- **Tests:** —
- **Gate:** revisão do diff.
- **Commit:** `docs(env): documenta variaveis do scheduler e do graceful shutdown`

### T4.5 [P] — `stop_grace_period` no compose

- **What:** Adicionar `stop_grace_period: 35s` ao serviço `backend` (profile `full`) — o default do Docker (10s) mataria o processo antes dos 30s de graceful. `Dockerfile` não muda (já usa `ENTRYPOINT` exec-form; JVM é PID 1).
- **Where:** `compose.yaml`
- **Depends on:** T4.2
- **Reuses:** serviço `backend` existente no compose.
- **Done when:** serviço `backend` tem `stop_grace_period: 35s`; `Dockerfile` intacto.
- **Tests:** —
- **Gate:** `docker compose config` valida o arquivo (ou revisão do diff).
- **Commit:** `chore(compose): stop_grace_period de 35s para respeitar o graceful shutdown`

### T4.6 — Verificação manual documentada no PR: SIGTERM com request em voo

- **What:** Subir em dev, disparar `GET /api/notas/{id}/pdf` e `kill -TERM`; confirmar no log `Commencing graceful shutdown. Waiting for active requests to complete`, a resposta chegando, e threads `agro-sched-N` (AC2: dois jobs em threads distintas). Registrar no PR.
- **Where:** descrição do PR.
- **Depends on:** T4.2, T4.5
- **Reuses:** —
- **Done when:** SCHED-03 (parte manual) e AC2/AC3 documentados no PR.
- **Tests:** manual.
- **Gate:** trecho do log colado no PR.
- **Commit:** —

### T4.7 — Suíte completa e PR #101

- **What:** Rodar suíte completa, abrir PR com título do spec, Copilot + OWASP + CI, mergear.
- **Where:** GitHub (PR).
- **Depends on:** T4.3, T4.4, T4.5, T4.6
- **Reuses:** fluxo dos PRs #78–#82.
- **Done when:** AC1–AC4 do #101; suíte verde; issue #101 fechada.
- **Tests:** suíte completa.
- **Gate:** `./mvnw test`
- **Commit:** — (merge)

---

## Épico E5 — #89 (parte rápida): `GET /{id}/xml` responde 409 quando XML não gerado (P2)

**História de usuário:** Como dev do Angular, quero que "nota existe mas recurso ainda não gerado" seja sempre **409** (XML e PDF), e 404 signifique só "nota não existe / não é sua", para tratar os dois casos de forma uniforme.

**Estimativa:** 2 SP · **S**
**Branch:** `fix/89-xml-409-nao-gerado`
**PR:** `fix(nota-fiscal): GET /{id}/xml responde 409 quando XML ainda nao foi gerado` · Refs #89 (issue continua aberta para o preview de DANFE)
**Decisão aplicada:** D2 (encaixe como 5º PR; avisar frontend da mudança 404→409).

### T5.1 — Ajustar os testes de service e controller (vermelho)

- **What:** Em `NotaFiscalServiceTest.obterXmlShouldThrowWhenXmlNotYetGenerated` (linha ~777) passar a esperar `XmlIndisponivelException`; em `NotaFiscalControllerTest` (linha ~453) passar a esperar `409` + `errors[0].code == "XML_INDISPONIVEL"` + `suggestion` apontando `gerar-xml`; **manter** o teste de 404 para nota de outro produtor (ERR-02).
- **Where:** `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`, `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`
- **Depends on:** —
- **Reuses:** testes existentes de `DANFE_INDISPONIVEL` (409) para `/pdf` como modelo.
- **Done when:** os dois testes ajustados **falham** na branch atual (não compilam/esperam 404); teste anti-IDOR intacto.
- **Tests:** `NotaFiscalServiceTest#obterXmlShouldThrowWhenXmlNotYetGenerated`, `NotaFiscalControllerTest` (caso `/xml` não gerado).
- **Gate:** `./mvnw test -Dtest=NotaFiscalServiceTest,NotaFiscalControllerTest` → **deve falhar**.
- **Commit:** `test(nota-fiscal): espera 409 XML_INDISPONIVEL ao baixar XML nao gerado`

### T5.2 [P] — Novo `ErrorCode.XML_INDISPONIVEL`

- **What:** Adicionar `XML_INDISPONIVEL` ao catálogo, ao lado de `DANFE_INDISPONIVEL`, com doc "XML da NF-e ainda não gerado. HTTP 409.".
- **Where:** `src/main/java/br/com/agrocontabil/common/ErrorCode.java`
- **Depends on:** —
- **Reuses:** `DANFE_INDISPONIVEL`.
- **Done when:** enum compila com o novo código documentado.
- **Tests:** — (coberto por T5.4).
- **Gate:** `./mvnw compile`
- **Commit:** `feat(erro): adiciona ErrorCode XML_INDISPONIVEL`

### T5.3 — Criar `XmlIndisponivelException`

- **What:** Criar exceção de domínio espelho de `DanfeIndisponivelException`: `DomainException` com `HttpStatus.CONFLICT` (409) e `ErrorDetail.of(XML_INDISPONIVEL, "Gere o XML da nota (POST /api/notas/{id}/gerar-xml) antes de baixá-lo.")`, construtor `(id, status)`.
- **Where:** `src/main/java/br/com/agrocontabil/exception/XmlIndisponivelException.java`
- **Depends on:** T5.2
- **Reuses:** `DanfeIndisponivelException`, `DomainException`, `ErrorDetail.of`.
- **Done when:** classe existe e segue o contrato de erro do CLAUDE.md (nunca tratada no controller; sai pelo `GlobalExceptionHandler`).
- **Tests:** — (coberto por T5.4).
- **Gate:** `./mvnw compile`
- **Commit:** `feat(erro): cria XmlIndisponivelException (409) espelhando DanfeIndisponivelException`

### T5.4 — Trocar a exceção em `NotaFiscalService.obterXml`

- **What:** Em `obterXml` (linha ~316), substituir `ResourceNotFoundException` por `XmlIndisponivelException(id, nota.getStatus())` quando a nota existe mas não tem `xml_gerado`; manter o 404 via `findByIdAndProdutorId` para nota inexistente/alheia; atualizar Javadoc `@throws`.
- **Where:** `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`
- **Depends on:** T5.1, T5.3
- **Reuses:** fluxo de `obterPdf`/`DanfeIndisponivelException` como referência de simetria.
- **Done when:** ERR-01 e ERR-02 — os dois testes ajustados verdes; testes anti-IDOR existentes verdes; AC3 (200 `application/xml` com XML) inalterado.
- **Tests:** `NotaFiscalServiceTest`, `NotaFiscalControllerTest`.
- **Gate:** `./mvnw test -Dtest=NotaFiscalServiceTest,NotaFiscalControllerTest`
- **Commit:** `fix(nota-fiscal): GET /{id}/xml responde 409 quando XML ainda nao foi gerado`

### T5.5 [P] — Documentação OpenAPI do endpoint `/xml`

- **What:** Se houver anotação OpenAPI (`@ApiResponse`/Javadoc) no endpoint `/xml`, trocar 404 por 409 para "não gerado" e documentar a simetria com `/pdf` (AC4); sem lógica no controller.
- **Where:** `src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java`
- **Depends on:** T5.4
- **Reuses:** anotações do endpoint `/pdf`.
- **Done when:** documentação do endpoint reflete 409 `XML_INDISPONIVEL` e 404 só para nota inexistente/alheia.
- **Tests:** —
- **Gate:** revisão do diff (só Javadoc/anotações).
- **Commit:** `docs(nota-fiscal): documenta 409 XML_INDISPONIVEL no endpoint /xml`

### T5.6 [P] — Registrar a exceção no `CLAUDE.md` e `codebase-map`

- **What:** Adicionar `XmlIndisponivelException` à tabela de exceções do `codebase-map` e à descrição do endpoint `GET /api/notas/{id}/xml` no `CLAUDE.md`.
- **Where:** `CLAUDE.md`, `.claude/skills/codebase-map/SKILL.md`
- **Depends on:** T5.3
- **Reuses:** entrada existente de `DanfeIndisponivelException`.
- **Done when:** ambos os arquivos mencionam a nova exceção.
- **Tests:** —
- **Gate:** revisão do diff.
- **Commit:** `docs: registra XmlIndisponivelException no CLAUDE.md e codebase-map`

### T5.7 — Suíte completa, PR e aviso ao frontend

- **What:** Rodar suíte completa; abrir PR com título do spec; na descrição do PR **e** como comentário na issue #89, registrar o aviso: se `NotasApi` do Angular trata 404 em `/xml` como "ainda não gerado", precisa passar a tratar 409. Copilot + OWASP + CI; mergear; comentar na #89 "parte rápida entregue no PR #N".
- **Where:** GitHub (PR + issue #89).
- **Depends on:** T5.4, T5.5, T5.6
- **Reuses:** fluxo dos PRs #78–#82.
- **Done when:** AC1–AC4 do #89; suíte verde; aviso registrado; issue #89 permanece aberta (preview de DANFE fora do escopo).
- **Tests:** suíte completa.
- **Gate:** `./mvnw test`
- **Commit:** — (merge)

---

## Rastreabilidade — ID do spec → task(s)

| ID | Descrição | Task(s) | Tipo |
|----|-----------|---------|------|
| FK-01 | RESTRICT produtor←nota | T1.1, T1.2 | automatizado |
| FK-02 | CASCADE nota→item | T1.1, T1.2 | automatizado |
| FK-03 | órfão item/apuração/guia rejeitado | T1.1, T1.2 | automatizado |
| FK-04 | idempotência do script (`IF NOT EXISTS`) | T1.2, T1.3 (testes existentes com 2+ `@Sql`) | automatizado |
| IDX-01 | índice `nota_fiscal(produtor_id, status, data_emissao)` | T2.1, T2.2, T2.3, T2.4 + **T2.6 (EXPLAIN manual no PR)** | automatizado + manual |
| IDX-02 | índice `guias_fiscais(status, data_vencimento)` | T2.1, T2.2, T2.4 | automatizado |
| IDX-03 | índice `guias_fiscais(produtor_id, data_vencimento)` | T2.1, T2.2, T2.4 | automatizado |
| POOL-01 | Hikari defaults + env | T3.1, T3.2, T3.4 | automatizado |
| POOL-02 | tx `default-timeout` 30s | T3.1, T3.2 | automatizado |
| POOL-03 | `statement_timeout` Postgres | T3.3 + **T3.6 (`pg_sleep` manual no PR)** | manual |
| POOL-04 | `TransactionTimedOutException` → `errors[]` | T3.5 | automatizado |
| SCHED-01 | pool 3 + prefixo `agro-sched-` | T4.1, T4.2 | automatizado |
| SCHED-02 | await-termination 30s | T4.1, T4.2 | automatizado |
| SCHED-03 | `server.shutdown=graceful` + lifecycle 30s | T4.1, T4.2, T4.5 + **T4.6 (SIGTERM manual no PR)** | automatizado + manual |
| ERR-01 | `/xml` 409 `XML_INDISPONIVEL` | T5.1, T5.2, T5.3, T5.4 | automatizado |
| ERR-02 | `/xml` 404 só p/ nota inexistente/alheia | T5.1, T5.4 (testes anti-IDOR existentes) | automatizado |

16/16 IDs cobertos; 13 automatizados, 3 com verificação manual documentada no PR (T2.6, T3.6, T4.6).

---

## Dependências entre épicos e ordem de merge

```
E1 (#90) ──► E2 (#102)        [compartilham schema-nota-fiscal-test.sql — criar branch de E2 após merge de E1]
E3 (#98) ──► E4 (#101)        [compartilham application.yml e .env.example — criar branch de E4 após merge de E3]
E5 (#89)                      [independente de todos]
```

- **Ordem de merge recomendada:** E1 → E2 → E3 → E4 → E5 (a do spec).
- **Paralelismo possível:** E1/E2 (trilha SQL), E3/E4 (trilha config) e E5 podem andar em worktrees paralelos; dentro de cada trilha, o segundo PR só começa depois do merge do primeiro para evitar rebase conflituoso.
- **Sugestão de calendário (2 semanas):** semana 1 — E1, E2, E5 (baixo risco, fecham 3 issues cedo); semana 2 — E3, E4 (verificações manuais em dev + eventual ajuste de handler) e parágrafo "Onda 1 — Fundação (concluída)" no `CLAUDE.md`.

---

## Riscos da sprint

| Risco | Impacto | Mitigação (1 linha) |
|-------|---------|---------------------|
| Cleanup de fixtures vs RESTRICT (E1): `@SpringBootTest` sem `@Transactional` deixa `apuracao_mensal`/`guias_fiscais` órfãos e quebra o `deleteAll()` de outro teste. | Suíte vermelha intermitente. | T1.3: corrigir ordem/abrangência do cleanup do teste ofensor — **nunca** afrouxar a FK. |
| H2 vs propriedade Postgres (E3): `statement_timeout` no `application.yml` base derruba a suíte com `UNSUPPORTED_SETTING`. | Suíte inteira falha ao subir contexto. | D3: propriedade só em `application-dev.yml`/`application-prod.yml`; T3.3 gated por `./mvnw test`. |
| Contrato 404→409 em `/xml` (E5) quebra o Angular se `NotasApi` trata 404 como "não gerado". | Tela de download quebra no frontend. | T5.7: aviso na descrição do PR e comentário na issue #89 antes do merge. |
| POOL-04 (E3): `TransactionTimedOutException` pode cair em 500 sem `errors[]`. | Contrato de erro violado sob timeout. | T3.5: testar primeiro; ajustar handler no mesmo PR só se necessário (SP extra já embutido no 5). |
| Leak-detection desligado silenciosamente se `DB_LEAK_DETECTION_MS` < 2000 ou ≥ `DB_MAX_LIFETIME_MS`. | Instrumentação da Onda 3 inútil sem aviso. | T3.4: documentar as duas regras no `.env.example`. |
| `stop_grace_period` esquecido (E4): Docker mata o processo em 10s, antes do graceful de 30s. | Fechamento de apuração interrompido em deploy. | T4.5 + T4.6 validam em dev com SIGTERM. |
| Transação legítima > 30s (importação de lote grande, DANFE, fechamento) falha por `DB_TX_TIMEOUT`. | 5xx genérico em operação válida. | Comportamento aceito pelo spec (D5): ajustar env, não o código. |

---

## Observações

1. **Nomes de teste de E1:** o spec fixa `deveRejeitarExclusaoDeProdutorComNotas`, `deveRemoverItensAoExcluirNotaPorSql` e `deveRejeitarFilhoOrfao*` (parametrizado). O AC5 (exclusão de produtor com `apuracao_mensal`/`guias_fiscais`) não tem nome fixado — leitura conservadora: cobrir dentro da mesma classe, de preferência estendendo o parametrizado, sem inventar novo ID de rastreabilidade.
2. **Idempotência (FK-04):** o spec diz que "qualquer teste com 2+ métodos `@Sql` (já existe)" prova; não foi criado teste novo para isso — `NotaFiscalRepositoryTest` é o gate.
3. **`compose.yaml` no E3:** o spec diz "só se houver env de pool lá (verificar); provavelmente nada" — T3.4 inclui a verificação, sem task própria.
4. **POOL-04:** dois commits alternativos previstos (`test:` ou `fix:`), conforme o resultado da verificação; a estimativa de 5 SP já inclui o cenário pessimista.
5. **T5.5 (OpenAPI):** condicionada a "se houver anotação OpenAPI" no endpoint, como no spec; se não houver, atualizar apenas o Javadoc.
6. **Contagem de testes novos:** E1 (~4–6), E2 (1–3), E3 (1–2 + POOL-04), E4 (1–2), E5 (0 novos, 2 ajustados) — dentro da estimativa +10 a +14 do spec.
7. **Débito registrado (não é task):** FK em `nota_fiscal_validacao.nota_fiscal_id` não existe no Liquibase (0006) — não replicar; anotar em "Débitos técnicos" do `CLAUDE.md` no fechamento da onda.
8. **Não remover `idx_nota_fiscal_produtor_id`** mesmo ficando redundante após o 0019 — changeSet 0002 não se edita; drop seria outro changeSet e outra decisão (Edge Case do spec).

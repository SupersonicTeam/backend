# Onda 2 — Correção fiscal · Plano de tasks

**Base:** `spec.md` + `design.md` (aprovados em 2026-09-21) · **Gerado em:** 2026-09-21 · **Scrum Master:** agente scrum-master
**Ordem obrigatória de PRs:** #86 → #85 → #88 · **Sprint:** 2 semanas (projeto acadêmico)

> Cada PR = 1 épico. Cada história = no máximo 1 PR (aqui, as 3 histórias do spec mapeiam 1:1 nos 3 PRs). Cada task = 1 commit atômico Conventional Commits.
> Caminhos abaixo são relativos à raiz do worktree. Prefixo `MAIN` = `src/main/java/br/com/agrocontabil`, `TEST` = `src/test/java/br/com/agrocontabil`, `RES` = `src/main/resources`, `TRES` = `src/test/resources`.

---

## 0. Visão geral e cabimento na sprint

| PR | Branch | Issue | Tasks | SP | T-shirt total |
|----|--------|-------|-------|----|---------------|
| 1 | `fix/86-aliquota-resolver-credito` | #86 | 7 | 8 | M |
| 2 | `feat/85-aliquota-vigencia` | #85 | 10 | 13 | L |
| 3 | `fix/88-emitente-real-nnf` | #88 | 16 | 21 | XL (dividível em 3a/3b) |
| **Total** | | | **33** | **42** | |

**Cabe em uma sprint de 2 semanas?** **Sim, com margem apertada.** Justificativa:

- Velocidade de referência: Sprint 5 (3 sub-issues, ~20–25 SP equivalentes) e a Onda 1 (#79–#82, ~4 PRs pequenos) fecharam em ~2 semanas cada, com 1 dev principal + revisão Copilot.
- 42 SP em 10 dias úteis exige ~4 SP/dia. É plausível porque (a) os PRs 1 e 2 são refatoração dirigida por testes sobre código já conhecido (`ReducaoNcmResolver` como molde), (b) o PR 3 não cria endpoint/DTO/controller de `Produtor` (fora de escopo).
- **Sequencialidade é o gargalo, não o volume:** o PR 2 depende do merge do PR 1 (resolvedor único) e o PR 3 depende do número de changeSet livre após o PR 2. Não há paralelismo entre PRs; a única paralelização possível é entre 3a e 3b dentro do PR 3.
- **Plano de contingência (recomendado explicitar no Sprint Planning):** se ao fim do dia 7 o PR 2 não estiver mergeado, o PR 3 vai para a próxima sprint dividido em 3a (T-88-01..T-88-10: modelo + mapper + validador + seed) e 3b (T-88-11..T-88-14: nNF/série/IBGE dest + zeroPad), ambos deployáveis isolados (design §6). Não é falha da sprint: os PRs 1 e 2 já corrigem os valores da apuração, que é o dano de maior valor de negócio.

**Alocação sugerida:** dias 1–3 → PR 1; dias 4–7 → PR 2 (inclui migração em Postgres local); dias 8–10 → PR 3.

**Priorização por valor de negócio (backlog ordenado):** #86 (apuração errada silenciosa, sem teste) > #85 (apuração errada a partir de 2027 / recálculo histórico) > #88 (bloqueia homologação da Sprint 7, mas não altera valores hoje). Coincide com a ordem técnica obrigatória.

---

## 1. Épico PR 1 — `fix/86-aliquota-resolver-credito` (#86)

**História:** Como contador, quero que o crédito de IBS de uma nota de entrada seja calculado com a mesma alíquota por UF usada no débito, para que a apuração mensal seja simétrica e auditável.
**Story Points:** 8 · **Requisitos:** FISC-01, FISC-02, FISC-03 · **ChangeSet:** nenhum.
**Nota de design:** o resolvedor nasce **sem** vigência (usa `findByUf` existente). A dimensão temporal entra no PR 2 sem tocar os serviços consumidores.

### T-86-01 — Teste vermelho: crédito usa alíquota por UF divergente do yml `[S]`

- **Commit:** `test(tributacao): prova divergencia entre credito (yml) e debito (tabela UF) — #86`
- **Arquivos:** alterar `TEST/service/CreditoTributarioServiceTest.java` (novo método `creditoUsaAliquotaPorUfDivergenteDoYml`).
- **Depends on:** —
- **Reuses:** fixtures atuais de `CreditoTributarioServiceTest`; `TributosProperties`; `NotaFiscal`/`NotaFiscalItem`.
- **Como escrever antes da correção:** o teste injeta um mock de `AliquotaIbsCbsResolver` (classe ainda não existe) — o teste **não compila** no estado atual. Para provar a falha em runtime (Success Criteria do spec exige "comprovadamente vermelho"), escrever nesta task uma versão que use `AliquotaIbsUfRepository` mockado devolvendo `0,0030+0,0020` com yml `0,0010` e asserte `valorCreditoIbs = base × 0,0050`. O `CreditoTributarioService` atual ignora o repositório → asserção falha. Em T-86-04 o teste é adaptado para o mock do resolvedor (asserção idêntica).
- **Done when:** `./mvnw test -Dtest=CreditoTributarioServiceTest` mostra exatamente 1 falha (`creditoUsaAliquotaPorUfDivergenteDoYml`), com valor esperado `× 0,0050` e obtido `× 0,0010`. **Registrar hash do commit + saída da falha na descrição do PR.**
- **Testes:** `CreditoTributarioServiceTest.creditoUsaAliquotaPorUfDivergenteDoYml` (§5).
- **Gate:** suíte **não** precisa estar verde neste commit (é o commit vermelho intencional); os 367 testes existentes seguem verdes.
- **Cobre:** FISC-01.

### T-86-02 — Criar `AliquotaIbsCbsResolver` + `AliquotasVigentes` (sem vigência) `[M]`

- **Commit:** `feat(tributacao): adiciona AliquotaIbsCbsResolver unificado para debito e credito — #86`
- **Arquivos:** criar `MAIN/service/AliquotaIbsCbsResolver.java` (`@Component`); criar `MAIN/service/AliquotasVigentes.java` (record com enum interno `OrigemAliquota { TABELA_UF, TABELA_VIGENCIA, FALLBACK_YML }` e `ibsTotal()`).
- **Depends on:** T-86-01
- **Reuses:** `ReducaoNcmResolver` (padrão `@Component` + `Optional`), `AliquotaIbsUfRepository.findByUf` (ainda existente), `TributosProperties` (fallback ÷ 2 HALF_UP 4 casas), lógica `resolveUfAplicavel`/`normalizarUf`/`IbsSplit` **movida** (não copiada) de `TributacaoService`.
- **Regras embutidas:** UF nula ou não cadastrada → fallback yml 50/50 + `log.warn`; nota `IMPORTADA` → `destinatarioEndereco.uf` se presente, senão `produtor.estado`; divergência entre os dois → `WARN`. Javadoc registra a premissa do design §2.3 (princípio do destino, EC 132 art. 156-A §1.º VII). CBS nesta fase vem de `tributosProperties.getCbs().getAliquota()` com `origemCbs = FALLBACK_YML` (tabela só no PR 2).
- **Done when:** classe compila; `AliquotaIbsCbsResolverTest` (T-86-03) verde; nenhum `System.out`; logs sem CPF/CNPJ.
- **Testes:** cobertos por T-86-03.
- **Gate:** `./mvnw test` verde exceto o teste vermelho de T-86-01 (ainda não adaptado).
- **Cobre:** FISC-01, FISC-02, FISC-03.

### T-86-03 — Testes unitários do resolvedor `[M]`

- **Commit:** `test(tributacao): cobre AliquotaIbsCbsResolver (simetria, fallback, nota importada) — #86`
- **Arquivos:** criar `TEST/service/AliquotaIbsCbsResolverTest.java`.
- **Depends on:** T-86-02
- **Reuses:** estilo de `ReducaoNcmResolverTest`; `@ExtendWith(MockitoExtension.class)` strict stubs.
- **Done when:** métodos `debitoECreditoObtemMesmaAliquota`, `fallbackYmlQuandoUfAusente`, `fallbackYmlQuandoUfNaoCadastrada`, `notaImportadaUsaUfDoDestinatario` verdes; cada um asserta `origemIbs` (TABELA_UF vs FALLBACK_YML). Sem `UnnecessaryStubbingException`.
- **Testes:** os 4 nomes acima (§5).
- **Gate:** `./mvnw test -Dtest=AliquotaIbsCbsResolverTest` verde.
- **Cobre:** FISC-01, FISC-02, FISC-03.

### T-86-04 — Migrar `CreditoTributarioService` para o resolvedor (teste passa a verde) `[S]`

- **Commit:** `fix(tributacao): credito de IBS/CBS passa a usar AliquotaIbsCbsResolver — #86`
- **Arquivos:** alterar `MAIN/service/CreditoTributarioService.java`; alterar `TEST/service/CreditoTributarioServiceTest.java` (trocar mock de repositório/yml pelo mock do resolvedor; asserção de T-86-01 inalterada).
- **Depends on:** T-86-02, T-86-03
- **Reuses:** `AliquotaIbsCbsResolver`; mantém `TributosProperties` **apenas** para `credito.percentualAproveitamento`.
- **Done when:** `CreditoTributarioService` não referencia `AliquotaIbsUfRepository` nem `tributosProperties.getIbs()`/`getCbs()` (verificar por `grep`); `creditoUsaAliquotaPorUfDivergenteDoYml` **verde**; testes existentes do crédito verdes com fixtures ajustadas (nenhum removido).
- **Testes:** `CreditoTributarioServiceTest.*`.
- **Gate:** `./mvnw test` verde (367 + novos).
- **Cobre:** FISC-01 (AC1, AC2 para o crédito).

### T-86-05 — Migrar `TributacaoService` (débito) para o resolvedor `[M]`

- **Commit:** `refactor(tributacao): TributacaoService delega resolucao de aliquota ao AliquotaIbsCbsResolver — #86`
- **Arquivos:** alterar `MAIN/service/TributacaoService.java` (remover `IbsSplit`, `resolveAliquotaIbs`, `resolveUfAplicavel`, `normalizarUf`; remover injeção de `AliquotaIbsUfRepository`; `item.setAliquotaCbs(vigentes.cbs())`); alterar `TEST/service/TributacaoServiceTest.java` (mock do resolvedor).
- **Depends on:** T-86-04
- **Reuses:** `AliquotaIbsCbsResolver`; `ReducaoNcmResolver` (inalterado).
- **Done when:** `TributacaoService` não injeta `AliquotaIbsUfRepository` nem lê `tributosProperties.getIbs()`; `TributacaoServiceTest` verde; `CalculoIbsCbsService` sem diff; `ApuracaoService`/`DashboardService`/`RelatorioConformidade*` sem diff (`git diff --stat` na descrição do PR).
- **Testes:** `TributacaoServiceTest.*`, `AliquotaIbsCbsResolverTest.debitoECreditoObtemMesmaAliquota`.
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-01 (AC2 para o débito).

### T-86-06 — Limpeza: `TributosProperties` como fallback declarado `[S]`

- **Commit:** `refactor(config): documenta TributosProperties como fallback conservador do resolvedor — #86`
- **Arquivos:** alterar `MAIN/config/TributosProperties.java` (Javadoc apenas; sem mudança de binding); alterar `RES/application.yml` **somente** se houver comentário desatualizado sobre "alíquota única".
- **Depends on:** T-86-05
- **Reuses:** —
- **Done when:** Javadoc diz "fallback conservador quando não há alíquota na tabela por UF (e, a partir da #85, sem vigência)"; nenhuma propriedade removida (o `credito.percentualAproveitamento` e o fallback continuam em uso).
- **Testes:** `AgroContabilApplicationTests` (contexto sobe).
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-02.

### T-86-07 — Docs do PR 1 `[S]`

- **Commit:** `docs: registra AliquotaIbsCbsResolver no CLAUDE.md, codebase-map e project-conventions — #86`
- **Arquivos:** alterar `CLAUDE.md` (pacote `service/` + seção de estado "Onda 2 — PR #86"); alterar `.claude/skills/codebase-map/SKILL.md` (`AliquotaIbsCbsResolver`, `AliquotasVigentes`); alterar `.claude/skills/project-conventions/SKILL.md` (nova regra: "alíquota nunca lida direto de `TributosProperties`/repositório — sempre via `AliquotaIbsCbsResolver`").
- **Depends on:** T-86-05
- **Done when:** os três arquivos mencionam o resolvedor; a regra de convenção está na seção de arquitetura.
- **Gate:** —
- **Cobre:** FISC-01 (documental, design §8).

### Definition of Done — PR 1

- [ ] `./mvnw test` verde: 367 existentes + ≥ 5 novos (`AliquotaIbsCbsResolverTest` ×4, `creditoUsaAliquotaPorUfDivergenteDoYml`). Nenhum teste removido.
- [ ] Descrição do PR contém hash do commit T-86-01 e saída da falha (Success Criteria "comprovadamente vermelho").
- [ ] `grep -r "AliquotaIbsUfRepository" MAIN/service/` retorna só `AliquotaIbsCbsResolver`.
- [ ] `git diff --stat main -- MAIN/service/ApuracaoService.java MAIN/service/DashboardService.java` vazio.
- [ ] Code review (Copilot) sem apontamentos abertos; auditoria OWASP leve: logs do resolvedor sem CPF/CNPJ/segredos; nenhuma exceção vaza detalhe técnico.
- [ ] Docs (T-86-07) atualizados.
- [ ] PR com descrição, critérios de aceite AC1–AC4 da #86 marcados e checklist acima.
- [ ] Branch a partir de `main`, em worktree isolado; commits Conventional em português.

---

## 2. Épico PR 2 — `feat/85-aliquota-vigencia` (#85)

**História:** Como contador, quero que a alíquota aplicada a uma nota seja a vigente na data de emissão, para que reemissão/recálculo de períodos passados e futuros (transição 2026–2033) use o valor correto.
**Story Points:** 13 · **Requisitos:** FISC-04, FISC-05, FISC-06, FISC-07, FISC-08 · **ChangeSet:** `00NN-aliquota-vigencia.xml` (hoje `0019`; **conferir `db.changelog-master.yaml` no momento do merge** — a Onda 1 #102 pode consumir o número).
**Depende do merge do PR 1.**

### T-85-01 — ChangeSet `00NN-aliquota-vigencia.xml` `[M]`

- **Commit:** `feat(db): adiciona vigencia a aliquota_ibs_uf e cria aliquota_cbs (changeSet 00NN) — #85`
- **Arquivos:** criar `RES/db/changelog/changes/00NN-aliquota-vigencia.xml` (2 changeSets: `00NN-aliquota-ibs-uf-add-vigencia`, `00NN-create-aliquota-cbs`, com `rollback` explícito em ambos); alterar `RES/db/changelog/db.changelog-master.yaml` (append).
- **Depends on:** PR 1 mergeado
- **Reuses:** `0013-aliquota-ibs-uf.xml` como referência de nomes de coluna (**não editar**); padrão de `0018-guias-fiscais.xml`.
- **Conteúdo (design §3.1):** `addColumn data_inicio DATE NOT NULL defaultValueDate="2026-01-01"`, `addColumn data_fim DATE NULL`, `dropPrimaryKey` + `addPrimaryKey (uf, data_inicio) constraintName="pk_aliquota_ibs_uf"`, `sql` CHECK `ck_aliquota_ibs_uf_vigencia`; `createTable aliquota_cbs (data_inicio DATE PK, data_fim DATE NULL, aliquota NUMERIC(7,4) NOT NULL)` + CHECK + `insert (2026-01-01, NULL, 0.0090)`.
- **Risco §7 (dropPrimaryKey sem nome no Postgres):** mitigação = T-85-09 (migração real em Postgres local antes de abrir o PR). Se o Liquibase não resolver o nome, usar `<sql>ALTER TABLE aliquota_ibs_uf DROP CONSTRAINT aliquota_ibs_uf_pkey</sql>` com `preConditions onFail="MARK_RAN"`.
- **Done when:** arquivo XML válido (schema Liquibase); master aponta para ele; `0013` sem diff.
- **Testes:** validado em T-85-09 (Postgres) e indiretamente por `AgroContabilApplicationTests` (H2 espelho em T-85-02).
- **Gate:** `./mvnw test` verde (Liquibase off no profile test — o gate real é T-85-09).
- **Cobre:** FISC-07 (AC6), FISC-05 (tabela `aliquota_cbs`).

### T-85-02 — Espelho H2 `schema-nota-fiscal-test.sql` `[S]`

- **Commit:** `test(db): espelha vigencia de aliquota_ibs_uf e aliquota_cbs no schema H2 de teste — #85`
- **Arquivos:** alterar `TRES/schema-nota-fiscal-test.sql` (reescrever bloco `aliquota_ibs_uf` com colunas novas e PK composta; `MERGE ... KEY (uf, data_inicio)`; criar `aliquota_cbs` + seed `2026-01-01`; comentário "manter sincronizado com 00NN").
- **Depends on:** T-85-01
- **Reuses:** blocos existentes do mesmo arquivo.
- **Done when:** contexto de teste sobe (`ddl-auto: validate` passa após T-85-03); `SELECT COUNT(*) FROM aliquota_ibs_uf` = 27 no H2.
- **Testes:** `AgroContabilApplicationTests`.
- **Gate:** `./mvnw test` verde (após T-85-03 — commits T-85-02 e T-85-03 podem ficar temporariamente vermelhos entre si; abrir o PR só após T-85-03).
- **Cobre:** FISC-07 (AC7).

### T-85-03 — Entidades `AliquotaIbsUf` (PK composta), `AliquotaIbsUfId`, `AliquotaCbs` `[M]`

- **Commit:** `feat(model): AliquotaIbsUf com vigencia (IdClass) e nova entidade AliquotaCbs — #85`
- **Arquivos:** alterar `MAIN/model/AliquotaIbsUf.java` (`@IdClass(AliquotaIbsUfId.class)`, `@Id uf`, `@Id dataInicio`, `dataFim` nullable); criar `MAIN/model/AliquotaIbsUfId.java` (`Serializable`, `equals/hashCode`); criar `MAIN/model/AliquotaCbs.java` (`@Id dataInicio`, `dataFim`, `aliquota`).
- **Depends on:** T-85-02
- **Reuses:** convenções de `AliquotaIbsUf` atual (nomes de coluna); `LocalDate`.
- **Done when:** Hibernate `validate` passa contra o H2 espelho; entidades sem Lombok inconsistente com o resto do pacote `model/`.
- **Testes:** `AgroContabilApplicationTests`.
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-04, FISC-05, FISC-07.

### T-85-04 — Repositórios: `findVigente` e remoção de `findByUf` `[S]`

- **Commit:** `feat(repository): findVigente(uf, data) e AliquotaCbsRepository.findVigente(data) — #85`
- **Arquivos:** alterar `MAIN/repository/AliquotaIbsUfRepository.java` (`extends JpaRepository<AliquotaIbsUf, AliquotaIbsUfId>`; `@Query` JPQL `dataInicio <= :data AND (dataFim IS NULL OR dataFim >= :data) ORDER BY dataInicio DESC`; **remover** `findByUf`); criar `MAIN/repository/AliquotaCbsRepository.java`.
- **Depends on:** T-85-03
- **Reuses:** —
- **Risco §7 (Optional + ORDER BY com >1 linha):** assinatura **`List<AliquotaIbsUf> findVigente(...)`** (ou `Limit.of(1)` do Spring Data 3.2+) e `stream().findFirst()` no resolvedor — decisão a registrar no Javadoc do repositório. Isso também garante o edge case "sobreposição → escolhe `data_inicio` mais recente".
- **Done when:** compila; resolvedor (T-85-06) é o único consumidor; `grep findByUf` retorna vazio em `MAIN/`.
- **Testes:** `AliquotaVigenciaIntegrationTest` (T-85-08) exercita a query real no H2.
- **Gate:** `./mvnw test` verde após T-85-06 (o resolvedor ainda chama `findByUf` até lá — fazer T-85-04 e T-85-06 em sequência imediata ou incluir a troca da chamada aqui e ajustar o teste em T-85-06; **preferir** manter compilável: mover a chamada `findByUf → findVigente(uf, LocalDate.now(BRT))` nesta task como passo intermediário).
- **Cobre:** FISC-04, FISC-05.

### T-85-05 — Teste vermelho: resolvedor seleciona vigência por `dataEmissao` `[S]`

- **Commit:** `test(tributacao): resolvedor deve selecionar vigencia pela data de emissao em BRT — #85`
- **Arquivos:** alterar `TEST/service/AliquotaIbsCbsResolverTest.java` (novos: `selecionaVigenciaPelaDataDeEmissao`, `converteDataEmissaoParaBrt`, `cbsVigenteEFallback`, `dataForaDeVigenciaCaiNoFallback`, `semDataEmissaoUsaHojeBrt`).
- **Depends on:** T-85-04
- **Reuses:** fixtures de T-86-03.
- **Done when:** os 5 métodos compilam e **falham** (o resolvedor ainda não usa `nota.dataEmissao` — chama com `LocalDate.now`): `selecionaVigenciaPelaDataDeEmissao` verifica `findVigente("MT", 2027-03-15)` e `findVigente("MT", 2026-06-10)`; `converteDataEmissaoParaBrt` verifica `findVigente(_, 2026-12-31)` para `2027-01-01T01:00Z`; `cbsVigenteEFallback` asserta `origemCbs`. Registrar saída no PR (não obrigatório pelo spec, mas segue o padrão TDD do PR 1).
- **Testes:** os 5 nomes acima (§5; `dataForaDeVigenciaCaiNoFallback` e `semDataEmissaoUsaHojeBrt` cobrem FISC-06 e o edge case do spec).
- **Gate:** suíte verde exceto os 5 novos (vermelho intencional).
- **Cobre:** FISC-04, FISC-05, FISC-06.

### T-85-06 — Resolvedor por data de emissão (BRT) + CBS por tabela `[M]`

- **Commit:** `feat(tributacao): AliquotaIbsCbsResolver seleciona vigencia por dataEmissao (America/Sao_Paulo) — #85`
- **Arquivos:** alterar `MAIN/service/AliquotaIbsCbsResolver.java` (algoritmo §2.2: `dataEmissao.atZoneSameInstant(America/Sao_Paulo).toLocalDate()`; nula → `LocalDate.now(BRT)` + WARN; `findVigente` IBS e CBS; fallback yml + WARN em ambos; `origemCbs = TABELA_VIGENCIA` quando da tabela); injetar `AliquotaCbsRepository`.
- **Depends on:** T-85-05
- **Reuses:** `AliquotasVigentes.OrigemAliquota`, `TributosProperties` (fallback), constante `ZoneId.of("America/Sao_Paulo")` — verificar se já existe uma em `ApuracaoService`/jobs para reaproveitar, senão declarar `private static final` no resolvedor.
- **Done when:** os 5 testes de T-85-05 verdes; testes de T-86-03 continuam verdes (fallback e UF); `TributacaoService`/`CreditoTributarioService` **sem diff** (a vigência entrou só no resolvedor — prova a decisão A do design §1).
- **Testes:** `AliquotaIbsCbsResolverTest.*`.
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-04, FISC-05, FISC-06.

### T-85-07 — Ajuste de fixtures que instanciam `AliquotaIbsUf` `[S]`

- **Commit:** `test(tributacao): fixtures de AliquotaIbsUf recebem dataInicio/dataFim — #85`
- **Arquivos:** alterar testes que constroem `AliquotaIbsUf` diretamente (localizar com `grep -rl "AliquotaIbsUf" TEST/`; esperado: `TributacaoServiceTest`, `CreditoTributarioServiceTest`, possivelmente `ApuracaoEventoDominioIntegrationTest`, `NcmCfopValidacaoIntegrationTest`).
- **Depends on:** T-85-03
- **Reuses:** —
- **Done when:** nenhum teste existente removido; todos compilam e passam; `ApuracaoEventoDominioIntegrationTest` verde **sem diff em `ApuracaoService`**.
- **Testes:** suíte existente; `ApuracaoEventoDominioIntegrationTest` (FISC-08).
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-07, FISC-08.

### T-85-08 — `AliquotaVigenciaIntegrationTest` (H2, fluxo `POST /api/notas`) `[M]`

- **Commit:** `test(tributacao): integracao — nota de 2027 usa segunda vigencia de MT; nota de 2026 inalterada — #85`
- **Arquivos:** criar `TEST/service/AliquotaVigenciaIntegrationTest.java` (`@SpringBootTest` + MockMvc, profile `test`; insere via repositório a vigência `MT [2027-01-01, ∅] = 0,0010+0,0010`; `POST /api/notas` com `dataEmissao = 2027-03-15T10:00-03:00` → `valorIbs` por `0,0020`; nota `2026-06-10` → `0,0010`).
- **Depends on:** T-85-06, T-85-07
- **Reuses:** padrão de `ImportacaoNotaFiscalIntegrationTest`/`NcmCfopValidacaoIntegrationTest`; `MockProdutorFilter` (produtor mock do seed).
- **Done when:** ambos os cenários verdes; teste limpa a vigência inserida (`@Transactional` ou `@AfterEach`) para não contaminar outros testes; a asserção de FISC-08 (projeção/recálculo somam valores persistidos) é coberta por `ApuracaoEventoDominioIntegrationTest` existente.
- **Testes:** `AliquotaVigenciaIntegrationTest.*` (§5).
- **Gate:** `./mvnw test` verde.
- **Cobre:** FISC-04, FISC-07, FISC-08.

### T-85-09 — Gate Postgres: migração real sobre banco com a 0013 aplicada `[S]`

- **Commit:** nenhum código; se ajuste for necessário no changeSet, commit `fix(db): ajusta dropPrimaryKey de aliquota_ibs_uf para Postgres — #85`.
- **Arquivos:** possivelmente `RES/db/changelog/changes/00NN-aliquota-vigencia.xml`.
- **Depends on:** T-85-01..T-85-06
- **Procedimento:** `docker compose up -d` (Postgres 16) com banco já em `0018`; `./mvnw spring-boot:run`; verificar log do Liquibase sem erro; `SELECT COUNT(*) FROM aliquota_ibs_uf WHERE data_inicio='2026-01-01' AND data_fim IS NULL` = **27**; `SELECT * FROM aliquota_cbs` = 1 linha `0.0090`; `\d aliquota_ibs_uf` mostra `pk_aliquota_ibs_uf (uf, data_inicio)` e `ck_aliquota_ibs_uf_vigencia`. Testar também o `rollback` (`./mvnw liquibase:rollback -Dliquibase.rollbackCount=2` ou equivalente) e re-aplicar.
- **Risco §7:** este é o gate que mitiga "dropPrimaryKey sem nome no Postgres".
- **Done when:** saída dos SELECTs colada na descrição do PR (Success Criteria do spec).
- **Gate:** migração Postgres OK + `./mvnw test` verde.
- **Cobre:** FISC-07 (AC6).

### T-85-10 — Docs do PR 2 `[S]`

- **Commit:** `docs: vigencia de aliquotas (changeSet 00NN), AliquotaCbs e debito 2027+ — #85`
- **Arquivos:** alterar `CLAUDE.md` (estado Onda 2 PR #85; **novo débito** "alíquotas 2027–2033 pendentes de publicação — inserir vigências por novo changeSet quando RFB/CONFAZ/CG-IBS publicarem"); alterar `.claude/skills/codebase-map/SKILL.md` (`AliquotaCbs`, `AliquotaCbsRepository`, `AliquotaIbsUfId`, changeSet `00NN`); alterar `.claude/skills/project-conventions/SKILL.md` (nota: "nova vigência = novo changeSet com `insert`; nunca `update` em linha existente de `aliquota_*`").
- **Depends on:** T-85-09
- **Done when:** os três arquivos refletem o novo modelo; Javadoc do `AliquotaIbsCbsResolver` cita o mesmo débito (risco §7 "valores de 2027+ desconhecidos").
- **Gate:** —
- **Cobre:** FISC-04, FISC-07 (documental).

### Definition of Done — PR 2

- [ ] `./mvnw test` verde: base + ≥ 7 novos (`AliquotaIbsCbsResolverTest` +5, `AliquotaVigenciaIntegrationTest` ×2). Nenhum teste removido.
- [ ] Migração executada num Postgres 16 local com a 0013 aplicada; saída de `COUNT(*) = 27`, `aliquota_cbs` = 1 linha, rollback testado — colada no PR.
- [ ] `0013-aliquota-ibs-uf.xml` sem diff; `db.changelog-master.yaml` só com append.
- [ ] `TRES/schema-nota-fiscal-test.sql` sincronizado (comentário referenciando `00NN`).
- [ ] `git diff --stat main -- MAIN/service/ApuracaoService.java MAIN/service/DashboardService.java MAIN/controller/RelatorioConformidadeController.java MAIN/service/TributacaoService.java MAIN/service/CreditoTributarioService.java` vazio (FISC-08 + prova da decisão de design A).
- [ ] Code review (Copilot) sem apontamentos abertos; auditoria OWASP leve: JPQL parametrizado (sem concatenação), WARN sem dados pessoais.
- [ ] Docs (T-85-10) atualizados, incluindo o novo débito técnico.
- [ ] PR com descrição, AC1–AC8 da #85 marcados, checklist acima e nota "número do changeSet conferido contra `main` no momento do merge".

---

## 3. Épico PR 3 — `fix/88-emitente-real-nnf` (#88)

**História:** Como produtor, quero que o XML da NF-e carregue meu endereço e IE reais e que número/série inválidos sejam rejeitados com erro claro, para que a nota seja aceita pela SEFAZ de homologação na Sprint 7.
**Story Points:** 21 · **Requisitos:** NFE-01, NFE-02, NFE-03, NFE-04 · **ChangeSet:** `00NN+1-produtor-endereco-ie.xml`.
**Depende do merge do PR 2** (numeração do changeSet). Se o review pedir, dividir em **3a** (T-88-01..T-88-10) e **3b** (T-88-11..T-88-14) — ambos deployáveis isolados; T-88-15/16 acompanham o último a mergear.

#### Sub-bloco 3a — emitente real (modelo, mapper, validador, seed)

### T-88-01 — `UfIbge` em `common/` (extraído de `NotaFiscalNfeMapper.UF_CODE`) `[S]`

- **Commit:** `refactor(common): extrai mapa UF->cUF para UfIbge reutilizavel — #88`
- **Arquivos:** criar `MAIN/common/UfIbge.java` (utilitário técnico → inglês OK; `static String codigo(String uf)`, `static boolean prefixoCoerente(String codigoIbge, String uf)`); alterar `MAIN/nfe/NotaFiscalNfeMapper.java` (remover `UF_CODE`, usar `UfIbge`); criar `TEST/common/UfIbgeTest.java`.
- **Depends on:** PR 2 mergeado
- **Reuses:** conteúdo atual de `UF_CODE` (27 UFs).
- **Done when:** `NotaFiscalNfeMapperTest` verde sem alteração de comportamento; `UfIbgeTest` cobre 27 UFs + UF desconhecida (retorna `Optional.empty()`/null documentado — **não** mais o default silencioso `"35"`).
- **Testes:** `UfIbgeTest`, `NotaFiscalNfeMapperTest` (existentes).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-02, NFE-03 (habilitador).

### T-88-02 — ChangeSet `00NN+1-produtor-endereco-ie.xml` + espelho H2 `[S]`

- **Commit:** `feat(db): adiciona endereco e inscricao_estadual em produtores (changeSet 00NN+1) — #88`
- **Arquivos:** criar `RES/db/changelog/changes/00NN+1-produtor-endereco-ie.xml` (`addColumn` ×8 nullable em `produtores`: `endereco_logradouro`, `endereco_numero`, `endereco_complemento`, `endereco_bairro` VARCHAR(60); `endereco_codigo_municipio_ibge` VARCHAR(7); `endereco_municipio` VARCHAR(60); `endereco_cep` VARCHAR(8); `inscricao_estadual` VARCHAR(14); `rollback dropColumn` ×8); alterar `RES/db/changelog/db.changelog-master.yaml`; alterar `TRES/schema-nota-fiscal-test.sql` (bloco `produtores`).
- **Depends on:** T-88-01
- **Reuses:** `0017-fix-produtores-estado-varchar.xml` como referência do nome da tabela/colunas.
- **Done when:** XML válido; H2 espelho sobe; todas as colunas nullable (rascunho e produtor existente não quebram).
- **Testes:** `AgroContabilApplicationTests`.
- **Gate:** `./mvnw test` verde; migração conferida em Postgres local (mesmo procedimento de T-85-09, mais simples — só `addColumn`).
- **Cobre:** NFE-01.

### T-88-03 — `EnderecoProdutor` (`@Embeddable`) + `inscricaoEstadual` em `Produtor` `[S]`

- **Commit:** `feat(model): Produtor ganha EnderecoProdutor embutido e inscricaoEstadual — #88`
- **Arquivos:** criar `MAIN/model/EnderecoProdutor.java` (7 campos, `@Column(name="endereco_*")`); alterar `MAIN/model/Produtor.java` (`@Embedded EnderecoProdutor endereco`; `String inscricaoEstadual`; `estado` **permanece** fora do embeddable).
- **Depends on:** T-88-02
- **Reuses:** `EnderecoDestinatario` como referência de estilo (**não** reutilizar a classe — o emitente exige `bairro` e tamanhos próprios).
- **Done when:** `ddl-auto: validate` passa; os 31 usos de `Produtor.builder().estado(...)` compilam sem alteração.
- **Testes:** `AgroContabilApplicationTests`.
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-01.

### T-88-04 — Fixture compartilhada `ProdutorTestFixtures.produtorCompleto()` `[S]`

- **Commit:** `test(nfe): fixture ProdutorTestFixtures.produtorCompleto() com IE e endereco Cuiaba/MT — #88`
- **Arquivos:** criar `TEST/ProdutorTestFixtures.java` (produtor MT, IE `1234567890`, IBGE `5103403`, CEP `78005000`, logradouro/número/bairro/município reais; variantes `produtorSemIe()`, `produtorEnderecoIncompleto()`, `produtorIbgeIncoerente()` para NFE-02).
- **Depends on:** T-88-03
- **Reuses:** `NfeImportTestFixtures` como modelo de fixture estática.
- **Risco §7 (testes de XML quebram por 422):** esta fixture é a mitigação; deve ser aplicada em T-88-08 **no mesmo PR** que introduz o validador.
- **Done when:** classe compila; usada por ao menos um teste (T-88-05).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-01, NFE-02 (habilitador).

### T-88-05 — Teste vermelho: XML com emitente real valida XSD sem placeholders `[S]`

- **Commit:** `test(nfe): XML deve carregar IE/endereco reais do emitente e cMunFG do emitente — #88`
- **Arquivos:** alterar `TEST/nfe/NotaFiscalNfeMapperTest.java` (novo `xmlComEmitenteRealValidaXsdSemPlaceholders`: produtor `produtorCompleto()`; asserta ausência dos literais `SEM ENDERECO`, `S/N`, `99999`, `MUNICIPIO NAO INFORMADO`, `00000000`, `0000000`; `ide.cMunFG == emit.enderEmit.cMun == 5103403`; `emit.IE == 1234567890`; XSD 4.00 válido).
- **Depends on:** T-88-04
- **Reuses:** `XsdValidator`, `XmlGenerator` já usados no teste existente.
- **Done when:** teste compila e **falha** (mapper ainda emite placeholders e `cMunFG` do destinatário). Registrar saída no PR.
- **Testes:** `NotaFiscalNfeMapperTest.xmlComEmitenteRealValidaXsdSemPlaceholders` (§5).
- **Gate:** suíte verde exceto este teste.
- **Cobre:** NFE-01 (AC1, AC6).

### T-88-06 — Mapper: `buildEmit`/`buildEnderEmit`/`resolveCMunFG`/`buildEnderDest` sem placeholders `[M]`

- **Commit:** `fix(nfe): mapper usa IE e endereco reais do emitente, cMunFG do emitente e enderDest sem fallback — #88`
- **Arquivos:** alterar `MAIN/nfe/NotaFiscalNfeMapper.java` (`buildEnderEmit` mapeia `EnderecoProdutor` com `truncate(…,60)`; `buildEmit`: `IE = inscricaoEstadual` ou `ISENTO` **só** se `tipoDocumento == NFA`; `resolveCMunFG` → `produtor.endereco.codigoMunicipioIbge`, remove `cUF+"9999"`; `buildEnderDest` remove `"0000000"`, `"SEM LOGRADOURO"`, `"MUNICIPIO"`, `"SP"`; atualizar Javadoc da classe que hoje documenta os placeholders).
- **Depends on:** T-88-05
- **Reuses:** `UfIbge` (T-88-01), `truncate`.
- **Done when:** T-88-05 verde; `grep -n "SEM ENDERECO\|99999\|0000000\|SEM LOGRADOURO" MAIN/nfe/NotaFiscalNfeMapper.java` vazio; testes existentes do mapper ajustados para `produtorCompleto()` (nenhum removido); `NfeToNotaFiscalMapper` (reverso) sem diff.
- **Testes:** `NotaFiscalNfeMapperTest.*`.
- **Gate:** `./mvnw test` pode ficar vermelho em `GeracaoNotaFiscalServiceTest`/`GeracaoNfaFlowTest` até T-88-08 (fixtures) — **não abrir o PR** antes de T-88-08.
- **Cobre:** NFE-01 (AC1, AC3, AC6), NFE-03 (AC10).

### T-88-07 — `FiscalRulesValidator.validarEmitente`/`validarDestinatario` (IE, endereço, prefixo IBGE) `[M]`

- **Commit:** `feat(nfe): valida IE por tipo de documento, endereco completo e coerencia IBGE/UF do emitente e destinatario — #88`
- **Arquivos:** alterar `MAIN/nfe/FiscalRulesValidator.java` (`validarEmitente`: IE obrigatória se `NFE` (`produtor.inscricaoEstadual`); 6 campos de endereço obrigatórios, um `FieldViolation` por campo `produtor.endereco.<campo>`; prefixo IBGE = `UfIbge.codigo(produtor.estado)`; `validarDestinatario`: prefixo IBGE = cUF de `destinatarioEndereco.uf`); criar `TEST/nfe/FiscalRulesValidatorTest.java` (`rejeitaEmitenteSemIeQuandoNfe`, `aceitaIsentoQuandoNfa`, `rejeitaEnderecoIncompleto` (asserta 6 violations com os 6 `field`s), `rejeitaIbgeIncoerenteComUf`, `rejeitaIbgeDestinatarioIncoerenteComUf`).
- **Depends on:** T-88-06
- **Reuses:** `NotaFiscalInvalidaParaEmissaoException.FieldViolation` (agrupamento em um único 422 — inalterado); `NotaFiscalValidacaoService` (persiste o lote — sem mudança); `FiscalRulesValidatorCfopAmbitoTest` como modelo de teste; `ProdutorTestFixtures` variantes.
- **Done when:** 5 testes verdes; regra "produtor.estado nulo → 422" (edge case) já existente permanece; `ErrorCode` reutilizado (verificar se há código adequado; criar só se necessário e documentar).
- **Testes:** `FiscalRulesValidatorTest.*` (§5).
- **Gate:** idem T-88-06 (vermelho tolerado até T-88-08).
- **Cobre:** NFE-02 (AC2–AC5), NFE-03 (AC8 lado emissão/422).

### T-88-08 — Aplicar `produtorCompleto()` nos fluxos de emissão existentes `[M]`

- **Commit:** `test(nfe): fluxos de emissao usam ProdutorTestFixtures.produtorCompleto() — #88`
- **Arquivos:** alterar `TEST/service/GeracaoNotaFiscalServiceTest.java`, `TEST/service/GeracaoNfaFlowTest.java`, `TEST/service/NotaFiscalValidacaoFlowTest.java`, `TEST/nfe/NotaFiscalNfeMapperTest.java`, integrações que chamam `gerar-xml` (localizar com `grep -rl "gerarXml\|gerar-xml" TEST/`).
- **Depends on:** T-88-07
- **Reuses:** `ProdutorTestFixtures`.
- **Risco §7:** mitigação direta do risco "testes existentes de XML quebram por 422".
- **Done when:** `./mvnw test` **inteiramente verde**; os usos de `Produtor.builder()` que não emitem XML ficam intactos (diff restrito aos arquivos acima); nenhum teste removido.
- **Testes:** suíte completa.
- **Gate:** `./mvnw test` verde. **Primeiro ponto em que o PR 3 pode ser aberto.**
- **Cobre:** NFE-01, NFE-02.

### T-88-09 — `DataLoader`: seed do produtor mock com IE e endereço válidos `[S]`

- **Commit:** `feat(config): DataLoader semeia produtor mock com IE e endereco completo (Cuiaba/MT) — #88`
- **Arquivos:** alterar `MAIN/config/DataLoader.java`; alterar `TEST/config/DataLoaderTest.java` (novo teste: produtor seed passa em `FiscalRulesValidator.validarEmitente` — ou asserção equivalente via nota mínima).
- **Depends on:** T-88-07
- **Reuses:** valores idênticos aos da fixture (IE `1234567890`, IBGE `5103403`, CEP `78005000`) — manter uma única fonte de verdade: a fixture de teste pode referenciar constantes públicas do `DataLoader`, se existirem.
- **Done when:** `DataLoaderTest` verde; `POST /api/notas/{id}/gerar-xml` manual com o produtor do seed gera XML sem os 6 literais (verificado em T-88-15).
- **Testes:** `DataLoaderTest` (§5).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-01 (AC7).

### T-88-10 — Docs do sub-bloco 3a `[S]`

- **Commit:** `docs: EnderecoProdutor, UfIbge e remocao do debito "Produtor sem endereco/IE" — #88`
- **Arquivos:** alterar `CLAUDE.md` (remover débito "Produtor sem endereço completo nem IE (placeholders no `NotaFiscalNfeMapper`)"; **novo débito** "IE de produtor sem DV (não há algoritmo nacional)"; estado Onda 2 PR #88; changeSet `00NN+1`); alterar `.claude/skills/codebase-map/SKILL.md` (`EnderecoProdutor`, `UfIbge`, `FiscalRulesValidatorTest`, `ProdutorTestFixtures`).
- **Depends on:** T-88-09
- **Done when:** débito removido e novo débito registrado; codebase-map lista as classes novas.
- **Gate:** —
- **Cobre:** NFE-01 (documental).

#### Sub-bloco 3b — limites nNF/série + IBGE do destinatário no DTO

### T-88-11 — Teste vermelho: `zeroPad` lança em overflow `[S]`

- **Commit:** `test(nfe): zeroPad deve lancar IllegalArgumentException em vez de truncar — #88`
- **Arquivos:** alterar `TEST/nfe/ChaveAcessoGeneratorTest.java` (`zeroPadLancaEmOverflow`: `zeroPad("1234567890", 9)` → `IllegalArgumentException` com mensagem contendo o valor e o limite; `zeroPadCpfComMaisDe11DigitosNaoCnpjLanca` para o edge case do spec).
- **Depends on:** T-88-01 (independente de 3a; pode ser feito em paralelo após PR 2)
- **Reuses:** testes existentes de `ChaveAcessoGeneratorTest`.
- **Done when:** os testes compilam e **falham** (hoje trunca silenciosamente). Registrar saída no PR.
- **Testes:** `ChaveAcessoGeneratorTest.zeroPadLancaEmOverflow` (§5).
- **Gate:** suíte verde exceto estes.
- **Cobre:** NFE-04 (AC13).

### T-88-12 — `zeroPad` lança + teste de chave no limite `[S]`

- **Commit:** `fix(nfe): ChaveAcessoGenerator.zeroPad lanca em overflow; chave consistente com nNF/serie no limite — #88`
- **Arquivos:** alterar `MAIN/service/ChaveAcessoGenerator.java` (`if (value.length() > length) throw new IllegalArgumentException(String.format("valor '%s' excede %d dígitos", value, length))`); alterar `TEST/nfe/ChaveAcessoGeneratorTest.java` (`chaveComNnfESerieNoLimite`: `numero = 999_999_999`, `serie = 999` → chave gerada, posições de `nNF`/`serie` da chave coincidem com o XML — asserção cruzada com `NotaFiscalNfeMapperTest` ou via parsing das posições 22–25 e 26–34 da chave).
- **Depends on:** T-88-11
- **Reuses:** —
- **Done when:** T-88-11 verde; `chaveComNnfESerieNoLimite` verde; nenhum chamador legítimo de `zeroPad` passa a lançar em fluxo normal (rodar `NotaFiscalNfeMapperTest` e `GeracaoNotaFiscalServiceTest`). Nota: a mensagem da exceção contém o valor — como `zeroPad` também recebe CPF/CNPJ, **não** logar a mensagem em nível INFO/WARN sem mascarar (LGPD — débito da Sprint 8; registrar no PR).
- **Testes:** `ChaveAcessoGeneratorTest.*` (§5).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-04 (AC13, AC14).

### T-88-13 — Bean Validation: `@Max` em `numero`/`serie` + `@CodigoIbgeCoerenteComUf` `[M]`

- **Commit:** `feat(validation): @Max em numero/serie e @CodigoIbgeCoerenteComUf no endereco do destinatario — #88`
- **Arquivos:** alterar `MAIN/dto/NotaFiscalRequest.java` (`@Max(999_999_999)` em `numero`, `@Max(999)` em `serie`; anotação de classe no record aninhado `EnderecoDestinatarioRequest`); criar `MAIN/validation/CodigoIbgeCoerenteComUf.java` (anotação `@Target(TYPE)`) e `MAIN/validation/CodigoIbgeCoerenteComUfValidator.java` (`ConstraintValidator<CodigoIbgeCoerenteComUf, EnderecoDestinatarioRequest>`; se ambos presentes, `UfIbge.prefixoCoerente`; se algum ausente → válido); criar `TEST/validation/CodigoIbgeCoerenteComUfValidatorTest.java`.
- **Depends on:** T-88-01
- **Reuses:** `ValidCfop`/`CfopValidator` como modelo de anotação customizada; `UfIbge`; `GlobalExceptionHandler` (já mapeia `MethodArgumentNotValidException` → 400 `errors[]` — verificar que violação de classe sem `field` produz `field` legível, ex.: `destinatarioEndereco`).
- **Done when:** `CodigoIbgeCoerenteComUfValidatorTest` verde (`MT`+`3550308` inválido; `MT`+`5103403` válido; IBGE omitido válido; UF omitida válida).
- **Testes:** `CodigoIbgeCoerenteComUfValidatorTest` (§5).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-03 (AC8, AC9), NFE-04 (AC11, AC12 lado 400).

### T-88-14 — `FiscalRulesValidator`: limites nNF/série na emissão + `NotaFiscalControllerValidationTest` `[M]`

- **Commit:** `feat(nfe): validador rejeita nNF/serie fora do layout na emissao; testes MockMvc de 400 — #88`
- **Arquivos:** alterar `MAIN/nfe/FiscalRulesValidator.java` (`validarCamposDaNota`: `numero ≤ 999_999_999`, `serie ≤ 999` → `FieldViolation`); alterar `TEST/nfe/FiscalRulesValidatorTest.java` (`rejeitaNumeroAcimaDoLimite`, `rejeitaSerieAcimaDoLimite`); criar `TEST/controller/NotaFiscalControllerValidationTest.java` (MockMvc: `numeroAcimaDoLimite` → 400, `serieAcimaDoLimite` → 400, `ibgeDestinatarioIncoerenteComUf` → 400 com `errors[]`, `ibgeDestinatarioOmitidoAceito` → 201; asserta envelope `errors[].code/field`).
- **Depends on:** T-88-13, T-88-07
- **Reuses:** `NotaFiscalControllerTest` como modelo MockMvc; `ApiError`/`ErrorDetail`.
- **Risco §7 (frontend envia numero/serie fora do limite):** comportamento novo é 400 com `errors[]` já no contrato — **comunicar no PR** (nota para o frontend Angular; sem mudança de payload).
- **Done when:** todos os testes verdes; defesa em profundidade comprovada (400 no DTO **e** 422 na emissão para nota importada/legada).
- **Testes:** `NotaFiscalControllerValidationTest.*`, `FiscalRulesValidatorTest.rejeitaNumero/SerieAcimaDoLimite` (§5).
- **Gate:** `./mvnw test` verde.
- **Cobre:** NFE-03 (AC8, AC9), NFE-04 (AC11, AC12).

#### Fechamento do PR 3

### T-88-15 — Verificação manual: `gerar-xml` com produtor do seed (Success Criteria) `[S]`

- **Commit:** nenhum (evidência no PR). Se surgir bug, commit `fix(nfe): ...`.
- **Procedimento:** Postgres local com `00NN+1` aplicado; `./mvnw spring-boot:run`; `POST /api/notas` (rascunho válido) → `POST /api/notas/{id}/gerar-xml` → `GET /api/notas/{id}/xml`; `grep -c "SEM ENDERECO\|S/N\|99999\|MUNICIPIO NAO INFORMADO\|00000000\|0000000" nota.xml` = 0; XML valida no XSD (já ocorre no pipeline); `ide.cMunFG` = `5103403`. Também `POST /api/notas` com `numero = 1000000000` → 400 `errors[]`.
- **Depends on:** T-88-09, T-88-14
- **Done when:** saída colada na descrição do PR.
- **Gate:** migração Postgres OK + `./mvnw test` verde.
- **Cobre:** NFE-01 (AC6), NFE-04 (AC11).

### T-88-16 — Docs do sub-bloco 3b + fechamento da Onda 2 `[S]`

- **Commit:** `docs: @CodigoIbgeCoerenteComUf, limites nNF/serie e encerramento da Onda 2 — #88`
- **Arquivos:** alterar `CLAUDE.md` (pacote `validation/` + `@CodigoIbgeCoerenteComUf`; estado "Onda 2 concluída"; contagem de testes atualizada); alterar `.claude/skills/codebase-map/SKILL.md` (`CodigoIbgeCoerenteComUf(Validator)`, `NotaFiscalControllerValidationTest`); alterar `.claude/skills/project-conventions/SKILL.md` (nota: "limites do layout NF-e — `@Max` no DTO **e** regra no `FiscalRulesValidator`: defesa em profundidade para notas importadas/legadas").
- **Depends on:** T-88-15
- **Done when:** docs consistentes com o código mergeado; débitos: "Produtor sem endereço/IE" removido (T-88-10), "IE sem DV" e "alíquotas 2027+" presentes; "tabela IBGE completa (Sprint 7)" e "IBS por município" registrados conforme Out of Scope do spec.
- **Gate:** —
- **Cobre:** NFE-03, NFE-04 (documental).

### Definition of Done — PR 3

- [ ] `./mvnw test` verde: base + ≥ 15 novos (`UfIbgeTest`, `NotaFiscalNfeMapperTest` +1, `FiscalRulesValidatorTest` ×7, `DataLoaderTest` +1, `ChaveAcessoGeneratorTest` +3, `CodigoIbgeCoerenteComUfValidatorTest`, `NotaFiscalControllerValidationTest` ×4). Nenhum teste removido; fixtures migradas para `produtorCompleto()` só onde há emissão de XML.
- [ ] Descrição do PR contém hash/saída dos commits vermelhos T-88-05 e T-88-11.
- [ ] Migração `00NN+1` executada em Postgres local; evidência manual de T-88-15 (XML sem placeholders, `cMunFG = 5103403`, 400 para `numero` inválido) colada no PR.
- [ ] `grep` de placeholders em `MAIN/nfe/NotaFiscalNfeMapper.java` vazio; `UF_CODE` removido do mapper.
- [ ] Sem DTO/controller/endpoint de `Produtor` (Out of Scope).
- [ ] Code review (Copilot) sem apontamentos abertos; auditoria OWASP leve: mensagem de `IllegalArgumentException` do `zeroPad` não vaza ao cliente (cai em 5xx genérico do `GlobalExceptionHandler`) nem é logada com CPF/CNPJ em claro; validação de entrada só por Bean Validation + `FiscalRulesValidator`; nenhum literal de endereço hardcoded fora do `DataLoader`.
- [ ] Nota para o frontend no PR: `numero > 999.999.999`, `serie > 999` e IBGE incoerente com UF passam a responder 400 `errors[]`.
- [ ] Docs (T-88-10, T-88-16) atualizados: débito "Produtor sem endereço/IE" removido; novos débitos registrados.
- [ ] PR com descrição, AC1–AC14 da #88 marcados e checklist acima. Se dividido em 3a/3b, cada metade cumpre este DoD isoladamente (3a: AC1–AC7, AC10; 3b: AC8–AC9, AC11–AC14).

---

## 4. Riscos do design §7 → onde estão tratados

| Risco | Tratamento |
|-------|------------|
| `dropPrimaryKey` sem nome no Postgres | T-85-01 (nota + plano B com `<sql>` + precondition) e **T-85-09** (gate Postgres obrigatório antes de abrir o PR 2) |
| Spring Data `Optional` + `ORDER BY` com >1 linha | T-85-04 (assinatura `List` + `findFirst()` ou `Limit.of(1)`, decidida e documentada no Javadoc) |
| Testes de XML quebram por 422 (emitente incompleto) | T-88-04 (fixture) + **T-88-08** (aplicação nos fluxos de emissão no mesmo PR; PR só abre após este commit) |
| Frontend envia `numero`/`serie` fora do limite | T-88-14 (nota de comunicação no PR) + DoD do PR 3 |
| Valores de 2027+ desconhecidos | T-85-06 (Javadoc do resolvedor) + T-85-10 (débito no CLAUDE.md; convenção "nova vigência = novo changeSet") |

---

## 5. Rastreabilidade requisito → tasks

| ID | Descrição curta | Tasks | Testes que provam | Status |
|----|-----------------|-------|-------------------|--------|
| FISC-01 | Resolvedor único IBS por UF (débito = crédito) | T-86-01, T-86-02, T-86-03, T-86-04, T-86-05, T-86-07 | `CreditoTributarioServiceTest.creditoUsaAliquotaPorUfDivergenteDoYml`, `AliquotaIbsCbsResolverTest.debitoECreditoObtemMesmaAliquota` | mapped |
| FISC-02 | Fallback yml 50/50 + WARN | T-86-02, T-86-03, T-86-06 | `AliquotaIbsCbsResolverTest.fallbackYmlQuandoUfAusente`, `…QuandoUfNaoCadastrada` | mapped |
| FISC-03 | Nota importada → UF do destinatário | T-86-02, T-86-03 | `AliquotaIbsCbsResolverTest.notaImportadaUsaUfDoDestinatario` | mapped |
| FISC-04 | Vigência IBS por `dataEmissao` em BRT | T-85-03, T-85-04, T-85-05, T-85-06, T-85-08, T-85-10 | `AliquotaIbsCbsResolverTest.selecionaVigenciaPelaDataDeEmissao`, `…converteDataEmissaoParaBrt`, `AliquotaVigenciaIntegrationTest` | mapped |
| FISC-05 | Tabela `aliquota_cbs` com vigência + fallback | T-85-01, T-85-03, T-85-04, T-85-05, T-85-06 | `AliquotaIbsCbsResolverTest.cbsVigenteEFallback` | mapped |
| FISC-06 | Data fora de vigência → fallback, não lança | T-85-05, T-85-06 | `AliquotaIbsCbsResolverTest.dataForaDeVigenciaCaiNoFallback`, `…cbsVigenteEFallback` | mapped |
| FISC-07 | ChangeSet de migração + espelho H2 | T-85-01, T-85-02, T-85-03, T-85-07, T-85-08, T-85-09, T-85-10 | `AliquotaVigenciaIntegrationTest`, `AgroContabilApplicationTests`, gate Postgres (T-85-09) | mapped |
| FISC-08 | Apuração/projeção inalteradas | T-85-07, T-85-08 (+ DoD PR 1 e PR 2: `git diff --stat` vazio) | `ApuracaoEventoDominioIntegrationTest` (existente, sem diff) | mapped |
| NFE-01 | Endereço + IE reais no XML, `cMunFG` do emitente, seed | T-88-02, T-88-03, T-88-04, T-88-05, T-88-06, T-88-08, T-88-09, T-88-10, T-88-15 | `NotaFiscalNfeMapperTest.xmlComEmitenteRealValidaXsdSemPlaceholders`, `DataLoaderTest` | mapped |
| NFE-02 | Validação do emitente (IE por tipo, endereço, prefixo IBGE) | T-88-01, T-88-04, T-88-07, T-88-08 | `FiscalRulesValidatorTest.rejeitaEmitenteSemIeQuandoNfe`, `…aceitaIsentoQuandoNfa`, `…rejeitaEnderecoIncompleto`, `…rejeitaIbgeIncoerenteComUf` | mapped |
| NFE-03 | IBGE do destinatário coerente com UF; sem fallback `0000000` | T-88-01, T-88-06, T-88-07, T-88-13, T-88-14, T-88-16 | `CodigoIbgeCoerenteComUfValidatorTest`, `NotaFiscalControllerValidationTest.ibgeDestinatarioIncoerenteComUf`, `…ibgeDestinatarioOmitidoAceito`, `FiscalRulesValidatorTest.rejeitaIbgeDestinatarioIncoerenteComUf` | mapped |
| NFE-04 | Limites nNF/série (400 + 422) + `zeroPad` lança | T-88-11, T-88-12, T-88-13, T-88-14, T-88-15, T-88-16 | `ChaveAcessoGeneratorTest.zeroPadLancaEmOverflow`, `…chaveComNnfESerieNoLimite`, `NotaFiscalControllerValidationTest.numeroAcimaDoLimite`, `…serieAcimaDoLimite`, `FiscalRulesValidatorTest.rejeitaNumero/SerieAcimaDoLimite` | mapped |

**Coverage:** 12/12 requisitos mapeados · 0 unmapped.

---

## 6. Resumo de estimativas

| PR | S | M | L | Tasks | SP | Justificativa dos SP |
|----|---|---|---|-------|----|----------------------|
| 1 (#86) | 4 | 3 | 0 | 7 | **8** | Refatoração dirigida por teste sobre padrão já existente (`ReducaoNcmResolver`); risco baixo; toca 2 serviços + 3 testes |
| 2 (#85) | 6 | 4 | 0 | 10 | **13** | ChangeSet com `dropPrimaryKey` (risco Postgres), PK composta em JPA, espelho H2, integração; exige gate manual em Postgres |
| 3 (#88) | 11 | 5 | 0 | 16 | **21** | Maior superfície: modelo + migration + mapper + validador + Bean Validation + fixture em ≥ 5 arquivos de teste + verificação manual; dividível em 3a/3b |
| **Total** | 21 | 12 | 0 | **33** | **42** | Cabe em 1 sprint de 2 semanas com contingência de mover 3b (ou todo o PR 3) para a seguinte |

**Regras de tamanho usadas:** S = ≤ 1h, 1–2 arquivos, sem decisão de design; M = 1–4h, 2–5 arquivos ou decisão local (ex.: assinatura do repositório); L não foi necessário porque nenhuma task exige > 4h após a quebra.

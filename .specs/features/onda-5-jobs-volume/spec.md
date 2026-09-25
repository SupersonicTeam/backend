# Spec — Onda 5: Jobs e volume

- **Data:** 2026-09-25
- **Issues:** #100 (fechamento por produtor) → #87 (ShedLock) → #103 (paginação de guias e janela do calendário)
- **Pressupõe:** Onda 1 mergeada — #102 (índices compostos, changeSet `0019`) e #101 (pool do scheduler + graceful shutdown).
- **Baseline:** 520 testes (2 skipped), todos verdes em `23f9e33`.
- **Autonomia:** Murillo autorizou em 2026-09-25 a execução autônoma da onda (spec → PR → review → merge por issue).

## 1. Problema

Os três jobs `@Scheduled` e as duas listagens de guias foram escritos para um produtor e poucos
dados. Com a base crescendo e com mais de uma instância no ar, três coisas quebram:

1. **#100 — atomicidade errada no fechamento mensal.** `ApuracaoService.fecharPeriodo` faz
   `produtorRepository.findAll()` e consolida todos os produtores numa **única** transação. Um
   produtor com dado inconsistente derruba o fechamento de todos. 500 produtores são 500 entidades em
   memória e cerca de 2.000 queries segurando uma conexão por minutos. `refecharPeriodosReabertos`
   tem o mesmo defeito.
2. **#87 — jobs rodam em dobro com 2+ instâncias.** Nenhum job tem lock distribuído: cada réplica
   sincroniza a SEFAZ, fecha a apuração e marca guias ao mesmo tempo.
3. **#103 — listagens sem limite.** `GuiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc`
   devolve `List` com todas as guias do produtor, consumida por `GET /api/contador/guias` e por
   `GET /api/calendario-fiscal/{produtorId}/vencimentos`. Cresce para sempre.

## 2. Requisitos

### #100 — Fechamento por produtor

| ID | Requisito |
|----|-----------|
| R100-1 | `fecharPeriodo` **não** abre transação de lote (`Propagation.NOT_SUPPORTED`); cada produtor é fechado numa transação própria (`REQUIRES_NEW` via `TransactionTemplate`). |
| R100-2 | Falha de um produtor (qualquer `RuntimeException`, inclusive na hora do commit) é logada em `ERROR` com `periodo` e `produtorId`, faz rollback só daquele produtor, e **não interrompe** os demais. |
| R100-3 | Os produtores são lidos em **lotes** por keyset (`id > último`, `ORDER BY id`), só os IDs. Tamanho do lote em `app.apuracao.fechamento.lote-tamanho` (padrão 100). Nunca `findAll()`. |
| R100-4 | `fecharPeriodo` devolve um resumo `ResumoLoteApuracao` (concluídas, lista de IDs com falha); o job loga o resumo e sobe para `ERROR` quando houve falha. |
| R100-5 | `refecharPeriodosReabertos` segue o mesmo desenho: lê só os IDs das apurações ABERTAS da janela RN005 e refecha cada uma em transação própria, com o mesmo resumo. |
| R100-6 | Regras preservadas: RN004 (fechamento automático), RN005 (janela de 3 meses), guarda da #54 (consolidar não rebaixa FECHADO), recálculo por evento só em ABERTO (#64). |
| R100-7 | Cada transação por produtor usa o timeout padrão (`spring.transaction.default-timeout`, 30s). A propriedade `app.apuracao.fechamento.transaction-timeout` deixa de existir: não há mais transação de lote para ela limitar. |

**Fora de escopo (registrado):** consolidar débito/crédito de todos os produtores num único SQL
agregado (item 3 da #100). Com as agregações por produtor já cobertas pelo índice
`idx_nota_fiscal_produtor_status_emissao` (#102), o custo por produtor é de ~4 queries indexadas
numa transação curta. O SQL agregado mudaria a fonte única `agregarTotais` compartilhada com a
projeção ao vivo (#53) e com os listeners (#64), um risco de divergência débito×crédito que não
compensa agora.

### #87 — Lock distribuído

| ID | Requisito |
|----|-----------|
| R87-1 | Dependências `net.javacrumbs.shedlock:shedlock-spring` e `shedlock-provider-jdbc-template` na versão **7.10.1** (fixada no `pom.xml`). |
| R87-2 | `@EnableSchedulerLock` em `SchedulingConfig`, com o advice do lock **por fora** do `@Transactional` do job: o lock só é liberado depois do commit. |
| R87-3 | `LockProvider` = `JdbcTemplateLockProvider` com `usingDbTime()` (relógio do banco, imune a skew entre instâncias). |
| R87-4 | Tabela `shedlock` via changeSet Liquibase `0023-shedlock.xml` (com rollback) e DDL equivalente no H2 de teste. |
| R87-5 | `@SchedulerLock` em cada job, com nome único e durações configuráveis por propriedade (tabela abaixo). |

| Job | Nome do lock | Cadência | `lockAtMostFor` | `lockAtLeastFor` | Justificativa |
|-----|--------------|----------|-----------------|------------------|---------------|
| `SefazStatusSyncJob` | `sefaz-status-sync` | `fixedDelay` 15 min | `PT14M` | `PT5M` | Teto abaixo do intervalo; o piso impede que a outra réplica rode logo em seguida, já que cada instância agenda o seu próprio `fixedDelay`. |
| `ApuracaoFechamentoJob` | `apuracao-fechamento` | cron `0 0 23 L * *` | `PT50M` | `PT5M` | Roda uma vez por mês; o teto cobre a varredura em lotes com folga e libera o lock se o nó morrer; o piso cobre skew entre réplicas que disparam o mesmo cron. |
| `GuiaFiscalStatusSyncJob` | `guia-fiscal-status-sync` | cron diário 1h | `PT30M` | `PT5M` | Um `UPDATE` em lote curto; mesmo raciocínio de skew. |

### #103 — Guias paginadas e calendário por janela

O Angular (`ContadorApi.getGuias`, `CalendarioApi.getVencimentos`) consome os dois endpoints como
**array** e o calendário navega os meses no cliente, depois de uma única carga. Trocar o corpo para
`Page` quebraria as duas telas. Decisão: **o corpo continua sendo um array**; o volume é limitado por
padrão.

| ID | Requisito |
|----|-----------|
| R103-1 | `GET /api/contador/guias?produtorId=&page=&size=`: `page` (padrão 0, ≥ 0) e `size` (padrão **100**, 1..**200**). Ordenação fixa `dataVencimento DESC, id DESC`, que usa o índice `idx_guia_fiscal_produtor_vencimento` (#102). |
| R103-2 | O corpo continua `GuiaFiscalResponse[]`. O total vai no header **`X-Total-Count`**, exposto no CORS. |
| R103-3 | `GET /api/calendario-fiscal/{produtorId}/vencimentos?de=YYYY-MM-DD&ate=YYYY-MM-DD`, ambos opcionais. `de` ausente → 1º dia do mês corrente − 12 meses; `ate` ausente → último dia do mês corrente + 12 meses (fuso `America/Sao_Paulo`). |
| R103-4 | `de > ate` → **400** `JANELA_CALENDARIO_INVALIDA` (campo `de`). Janela maior que **36 meses** → **400** `JANELA_CALENDARIO_INVALIDA` (campo `ate`, `expectedValue` com o limite). Data malformada → 400 do handler de tipo já existente. |
| R103-5 | `size` ou `page` fora da faixa → 400 no envelope `errors[]`. |
| R103-6 | O método sem limite `findByProdutorIdOrderByDataVencimentoDesc` é **removido**; ownership anti-IDOR (404) preservado nos dois endpoints. |
| R103-7 | Nenhum changeSet: o índice `(produtor_id, data_vencimento)` já existe (`0019`, #102). |

**Compatibilidade com o Angular:** sem mudança de código no frontend, as duas telas continuam
funcionando. O que muda: a lista de guias do Contador mostra no máximo 100 guias (cerca de 8 anos a
~12/ano) e o calendário mostra os vencimentos de 12 meses antes a 12 meses depois do mês corrente.
Ver a nota para o frontend na seção 6.

## 3. Desenho

### 3.1 Fechamento por produtor (#100)

```
ApuracaoFechamentoJob.fecharApuracaoDoMes()                      [sem transação]
  └─ ApuracaoService.fecharPeriodo(periodo)                      [NOT_SUPPORTED]
       loop por lote (keyset):
         ids = produtorRepository.findIdsOrdenados(PageRequest.ofSize(lote))
             | produtorRepository.findIdsOrdenadosApos(ultimo, PageRequest.ofSize(lote))
         para cada id:
           try  txPorItem.executeWithoutResult(consolidarParaProdutor(id, mes, FECHADO, agora))  [REQUIRES_NEW]
           catch RuntimeException → log.error(periodo, produtorId, e); falhas += id
       return ResumoLoteApuracao(concluidas, falhas)
  └─ ApuracaoService.refecharPeriodosReabertos()                 [NOT_SUPPORTED]
       ids = apuracaoRepository.findIdsByStatusAndPeriodoEntre(ABERTO, mes-3, mes)
       para cada id: txPorItem → findById → aplicarConsolidacao(FECHADO)
```

- `txPorItem` é um `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` construído a partir do
  `PlatformTransactionManager` injetado no `ApuracaoService`. Fica no próprio service porque a
  consolidação (`aplicarConsolidacao`/`agregarTotais`) é privada; um bean separado exigiria expor
  essa lógica.
- Keyset em vez de offset: um produtor cadastrado durante a varredura não desloca as páginas.
- Uma apuração que sai de ABERTO entre a leitura dos IDs e a transação dela (por exemplo, fechada
  por outra via) é refechada do mesmo jeito: a consolidação com alvo FECHADO é idempotente.

### 3.2 ShedLock (#87)

- `SchedulingConfig`: `@EnableSchedulerLock(defaultLockAtMostFor = "PT30M", order = HIGHEST_PRECEDENCE)`
  e o bean `LockProvider`. O `order` põe o advice do lock por fora do advice transacional (que tem
  precedência mínima), então no `GuiaFiscalStatusSyncJob` o lock envolve o commit.
- Durações em propriedades `app.<job>.lock.at-most-for` / `at-least-for`, com os padrões da tabela.
- No profile `test` os jobs continuam desligados; o teste de lock liga só o job de guias com cron
  desativado (`-`) e chama o método pelo proxy.

### 3.3 Guias e calendário (#103)

- `GuiaFiscalRepository`: `Page<GuiaFiscal> findByProdutorId(UUID, Pageable)` e
  `List<GuiaFiscal> findByProdutorIdAndDataVencimentoBetweenOrderByDataVencimentoDescIdDesc(UUID, LocalDate, LocalDate)`.
- `GuiaFiscalService.listar(produtorId, page, size)` → `Page<GuiaFiscalResponse>`; o controller
  devolve `page.getContent()` e o header `X-Total-Count`.
- Validação de `page`/`size`: Bean Validation direto nos `@RequestParam` (`@Min`/`@Max`), usando a
  validação de método do Spring 6.1+.
- `CalendarioFiscalService.listarVencimentos(produtorId, de, ate)` resolve os padrões com o `Clock`
  do projeto e valida a janela.

## 4. Testes

| Issue | Unitário (Mockito) | Integração (H2) |
|-------|--------------------|-----------------|
| #100 | Falha de 1 produtor não aborta os demais (resumo com o ID que falhou); lotes por keyset (2ª página parte do último ID; para em página curta); rollback só da transação que falhou; refechamento por ID em transação própria. | Classe **não transacional** `ApuracaoFechamentoIntegrationTest`: fecha 3 produtores com um falhando de propósito (spy no repositório de notas) → os outros 2 ficam FECHADOS e **commitados**; lote menor que o nº de produtores cobre a paginação; refechamento da janela RN005; guarda da #54. Os testes de fechamento saem do `ApuracaoIntegrationTest` (`@Transactional`, invisível para `REQUIRES_NEW`). |
| #87 | Cada job tem `@SchedulerLock` com nome único e `lockAtMostFor` definido; `SchedulingConfig` tem `@EnableSchedulerLock`. | Com o lock `guia-fiscal-status-sync` segurado por "outra instância", chamar o job não marca a guia vencida; liberado o lock, marca. |
| #103 | Service de guias monta `PageRequest` com ordenação fixa; calendário resolve os padrões da janela, rejeita `de > ate` e janela > 36 meses; anti-IDOR preservado. | MockMvc: header `X-Total-Count`, corpo array, `size` > 200 → 400, recorte `de`/`ate`, janela padrão exclui guia de 2 anos atrás, 400 em janela inválida. |

## 5. Critérios de aceite

- [ ] Um produtor com falha no fechamento deixa os demais FECHADOS e o resumo aponta o ID dele (#100).
- [ ] Nenhum `findAll()` de produtores no fechamento; leitura por lotes configuráveis (#100).
- [ ] RN004/RN005/#54/#64 inalterados: toda a suíte anterior continua verde (#100).
- [ ] Os três jobs têm `@SchedulerLock`; com o lock segurado o job não executa (#87).
- [ ] Tabela `shedlock` criada por changeSet novo, com rollback (#87).
- [ ] `/api/contador/guias` devolve no máximo `size` itens, com `X-Total-Count` (#103).
- [ ] `/vencimentos` respeita a janela padrão e `de`/`ate`, com 400 no envelope `errors[]` (#103).
- [ ] O Angular atual continua funcionando sem mudança de código (#103).
- [ ] `./mvnw verify` verde em cada PR; CLAUDE.md, `codebase-map` e `.env.example` atualizados.

## 6. Nota para o frontend (Angular)

1. **Nada quebra.** Os dois endpoints continuam devolvendo array.
2. **Guias do Contador (`ContadorApi.getGuias`):** passam a vir no máximo 100 por chamada, das mais
   recentes para as mais antigas. Para paginar, envie `page`/`size` (máx. 200) e leia o total em
   `X-Total-Count` (header já exposto no CORS).
3. **Calendário (`CalendarioApi.getVencimentos`):** sem parâmetros, a janela é de 12 meses antes a
   12 meses depois do mês corrente. Ao navegar para fora dela, envie `de`/`ate` (`YYYY-MM-DD`,
   janela máxima de 36 meses) ou recarregue por trimestre.
4. **Erros novos:** `400 JANELA_CALENDARIO_INVALIDA` (em `de` ou `ate`) e 400 de validação para
   `page`/`size` fora da faixa, no mesmo envelope `errors[]`.

## 7. Sequência de PRs

1. **PR #100** `fix(apuracao): fecha a apuracao por produtor em transacao propria e em lotes` — inclui este spec.
2. **PR #87** `feat(jobs): lock distribuido com ShedLock nos jobs agendados`.
3. **PR #103** `fix(guias): pagina a listagem de guias e recorta o calendario por janela`.

Cada PR passa por code review avançado, correção dos achados, CI verde e merge (squash) antes do próximo.

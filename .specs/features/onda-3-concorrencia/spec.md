# Spec — Onda 3: Concorrência e escopo transacional

- **Data:** 2026-09-23
- **Issues:** #95, #96, #99, #97 (nesta ordem de execução)
- **Épico:** planejamento das 21 issues abertas em 7 ondas (2026-09-21)
- **Status:** aprovado em 2026-09-23. PR 1 (#95) mergeado em #115; PR 2 (#96) em revisão.
- **Escopo TLC:** Large (multi-componente, 3 PRs, mudança de schema, novos estados de domínio)

## 1. Objetivo

Remover os bloqueadores reais de uso multi-usuário do backend AgroContábil. Hoje o sistema funciona
porque só um usuário opera por vez: nenhuma entidade tem controle de versão, nenhuma transição de
estado tem lock, e as duas operações mais lentas (assinatura RSA+XSD e renderização da DANFE) rodam
com uma conexão do pool presa — uma delas abrindo uma segunda conexão enquanto a primeira está presa.

Três classes de defeito, com consequência fiscal real:

1. **Lost update** — duas threads geram duas chaves de acesso para a mesma nota; o último `save`
   sobrescreve. A nota fica com uma chave e um XML que não correspondem entre si.
2. **Duplo efeito externo** — dois requests simultâneos de `enviar-sefaz` transmitem a mesma NF-e
   duas vezes à SEFAZ e publicam dois `NotaEmitidaEvent`, apurando o débito de IBS/CBS em dobro.
3. **Exaustão/deadlock de pool** — N emissões ou downloads concorrentes ocupam o pool inteiro
   enquanto assinam/renderizam; o fluxo de nota inválida precisa de 2 conexões simultâneas por
   request e trava o pool com 10 requests concorrentes.

## 2. Pré-requisitos — confirmados

Ondas 1 e 2 estão mergeadas na `origin/main` (até `cc258e5`, PR #114). Verificado em 2026-09-23.
O que delas sustenta esta onda:

| Entrega | PR | Por que importa aqui |
|---|---|---|
| HikariCP com `leak-detection-threshold: 30s`, `connection-timeout: 5s`, pool 10 | #109 | Torna #97/#99 **observáveis**: conexão presa vira WARN com stack trace, e a espera por conexão falha rápido em vez de enfileirar. |
| `spring.transaction.default-timeout: 30s` | #109 | Teto global para transação sem timeout explícito. Hoje uma chamada SEFAZ lenta + retry pode estourá-lo — a #96 remove esse risco ao tirar a I/O da transação. |
| FKs replicadas no schema H2 com `ON DELETE` | #104 | Os testes de concorrência não passam por acidente. |
| Pool dedicado de 3 threads para os jobs + graceful shutdown 30s | #110 | O job de reconciliação (RF-96.9) não disputa thread com os demais. |
| Timeout de transação próprio para lotes de job (`@Transactional(timeoutString=...)`) | #113 | Padrão a seguir na reconciliação de transmissões presas. |
| `Produtor` com `endereco` (`@Embedded EnderecoProdutor`) e `inscricaoEstadual` | #88/#114 | **Entram no snapshot de RF-99.1** — o pipeline de NF-e agora lê endereço e IE reais do emitente. |
| `obterXml` lança `XmlIndisponivelException` (409) | #111 | Sem impacto no desenho; apenas não reintroduzir o 404 antigo ao mexer no service. |

- Próximo número livre de changeSet Liquibase: **0022** (0019, 0020 e 0021 foram consumidos pelas
  Ondas 1 e 2).
- Baseline de testes: **478** (`./mvnw test`), medida nesta `origin/main`.

## 3. Requisitos

### RF-95 — Controle de versão otimista em NotaFiscal (issue #95)

| ID | Requisito |
|----|-----------|
| RF-95.1 | `NotaFiscal` passa a ter `@Version private Long version` (coluna `version`, `BIGINT NOT NULL DEFAULT 0`). |
| RF-95.2 | ChangeSet Liquibase `0022-nota-fiscal-version.xml` cria a coluna com default `0` e a torna `NOT NULL`, preenchendo as linhas existentes. |
| RF-95.3 | `src/test/resources/schema-nota-fiscal-test.sql` recebe a coluna `version` (usado via `@Sql` no `NotaFiscalRepositoryTest`; o profile `test` roda `ddl-auto: create-drop` com Liquibase desabilitado, então as demais tabelas de teste se atualizam sozinhas). |
| RF-95.4 | `OptimisticLockingFailureException` (e a `jakarta.persistence.OptimisticLockException` subjacente) é mapeada para **HTTP 409** no `GlobalExceptionHandler`, com o envelope `ApiError.errors[]`. |
| RF-95.5 | Novo `ErrorCode.CONFLITO_CONCORRENCIA`, com `message` = "A nota foi modificada em outra operação; recarregue e tente de novo." e `suggestion` orientando o recarregamento. Nenhum detalhe técnico (nome de exceção, stack, nome de tabela) vaza ao cliente. |
| RF-95.6 | Decisão registrada: `ApuracaoMensal` e `GuiaFiscal` **não** recebem `@Version` nesta onda — ver §6 (Decisões). |

### RF-96 — Lock e escopo transacional em enviar/cancelar (issue #96)

| ID | Requisito |
|----|-----------|
| RF-96.1 | Dois novos status: `ENVIANDO` e `CANCELANDO` (estados transitórios de transmissão). Ambos cabem em `VARCHAR(20)`. |
| RF-96.2 | `NotaFiscalRepository.findByIdAndProdutorIdForUpdate` com `@Lock(LockModeType.PESSIMISTIC_WRITE)`. |
| RF-96.3 | `enviar` passa a ter três etapas: **tx curta 1** (lock + checa pré-condição + `XML_GERADO → ENVIANDO`, commit libera lock e conexão) → **I/O SEFAZ fora de transação** → **tx curta 2** (lock + aplica retorno + status final + publica evento). |
| RF-96.4 | `cancelar` segue o mesmo desenho, com `AUTORIZADA → CANCELANDO → CANCELADA`. A validação de prazo (RN006, 30 dias) e o ownership permanecem na tx 1. |
| RF-96.5 | Um segundo request concorrente bloqueia no lock, e ao obtê-lo vê `ENVIANDO`/`CANCELANDO` e responde **409** (`NotaFiscalNaoEnviavelException` / `NotaFiscalNaoCancelavelException`). Nenhuma segunda transmissão à SEFAZ. |
| RF-96.6 | `NotaEmitidaEvent` / `NotaCanceladaEvent` são publicados **exatamente uma vez** por transmissão bem-sucedida, na tx 2 (mantendo `@TransactionalEventListener(AFTER_COMMIT)`). |
| RF-96.7b | **Pré-condição de RF-96.7, descoberta no code review:** compensar para `PENDENTE_SEFAZ` só é seguro se a consulta de status distinguir chave conhecida de desconhecida. O `MockSefazTransport` respondia AUTORIZADA para qualquer chave, então uma nota que falhou **antes** de chegar ao fisco era "autorizada" pelo job, com protocolo fictício e `NotaEmitidaEvent` publicado — débito de IBS/CBS sobre NF-e inexistente. O mock passa a registrar as chaves transmitidas e a responder **cStat 217** para chave desconhecida, como a SEFAZ real. |
| RF-96.7 | **Falha de comunicação no envio** (`SefazComunicacaoException`): tx curta de compensação marca `ENVIANDO → PENDENTE_SEFAZ` e o 502 é propagado. Reúsa o `SefazStatusSyncJob` já existente, que reconcilia `PENDENTE_SEFAZ` pela chave — estado semanticamente correto para "transmiti, não sei o desfecho". Nunca volta a `XML_GERADO` (permitiria reenvio duplicado). |
| RF-96.8 | **Falha de comunicação no cancelamento**: tx curta de compensação devolve `CANCELANDO → AUTORIZADA` e propaga o 502. Não usa `PENDENTE_SEFAZ`, porque `consultarEAtualizar`/`mapStatus` não reconhecem cancelamento e o job ressuscitaria a nota como `AUTORIZADA` — exatamente a regressão fechada pela #79. Reenviar o cancelamento é idempotente do lado do fisco (cStat 135/155 de evento já vinculado). |
| RF-96.9 | **Reconciliação de nota presa** (processo derrubado entre tx 1 e tx 2): o `SefazStatusSyncJob` passa a varrer, antes da sincronização normal, notas em `ENVIANDO`/`CANCELANDO` com `updatedAt` mais antigo que `app.nfe.sefaz.transmissao.timeout-ms` (default 10 min) e aplica a mesma compensação de RF-96.7/RF-96.8. Usa o `updatedAt` já existente (atualizado pelo `@PreUpdate`) — **sem coluna nova**. |
| RF-96.10 | `ENVIANDO`/`CANCELANDO` são estados **não-editáveis e não-operáveis**: `update`, `delete`, `gerar-xml`, `enviar`, `cancelar` e `status-sefaz` respondem 409. Não entram em `STATUS_EDITAVEIS`. |
| RF-96.11 | **Nenhuma mudança de schema para os novos status.** Verificado em `0002-nota-fiscal.xml`: a coluna é `VARCHAR(20) NOT NULL DEFAULT 'RASCUNHO'`, **sem CHECK constraint**, e o enum é `EnumType.STRING`. `ENVIANDO` (8) e `CANCELANDO` (10) caberiam mesmo se houvesse limite mais estreito. O PR de #96 não tem changeSet. |

| RF-96.12 | **Reconciliação isolada por nota:** o lote roda uma transação curta por nota, com `try/catch` individual (mesmo padrão de `sincronizarPendentes`). Uma colisão de versão — corrida com a tx2 de uma transmissão que finalmente respondeu — não pode desfazer as demais nem abortar o job. A nota é recarregada dentro da transação: se já saiu do estado transitório, o desfecho real prevalece e ela não entra na contagem. |
| RF-96.13 | **A I/O sai da transação também na sincronização:** `sincronizarStatus` e `sincronizarPendentes` adotam o mesmo desenho de três fases (lê e valida → consulta fora de transação → aplica em tx curta). Sem isso, N consultas com retry correriam numa transação só, prendendo conexão do pool — o mesmo defeito que a issue remove do envio. |
| RF-96.14 | **Recusa do fisco tem exceção própria:** `CancelamentoRejeitadoException` (409, `ErrorCode.CANCELAMENTO_REJEITADO`) repassa cStat e `xMotivo`. Reaproveitar `NotaFiscalNaoCancelavelException` produzia mensagem contraditória — "status AUTORIZADA não permite cancelar", sendo AUTORIZADA o status cancelável. |

### RF-99 — Transação curta em gerarXml e gerarPdf (issue #99)

| ID | Requisito |
|----|-----------|
| RF-99.1 | Novo DTO interno de snapshot (`NotaFiscalSnapshot`, `record`, pacote `service` ou `dto`) materializando tudo que os pipelines de XML e DANFE consomem: dados escalares da nota, itens (`List`), `EnderecoDestinatario` e o emitente completo — `Produtor` com **`endereco` (`EnderecoProdutor`) e `inscricaoEstadual`**, entregues pela Onda 2 (#88/#114) e agora lidos pelo `NotaFiscalNfeMapper` para `<enderEmit>` e `cMunFG`. Nada de proxy Hibernate cruza a fronteira. |
| RF-99.2 | `gerarXml` passa a: **tx curta readOnly** (ownership 404 + checa `STATUS_EDITAVEIS` + materializa snapshot) → **geração/assinatura/XSD fora de transação** → **tx curta de escrita** (lock, recheca status, persiste `xmlGerado`/`versaoLayoutNfe`/`dataGeracaoXml`/`chaveAcesso`/`status = XML_GERADO`). |
| RF-99.3 | `gerarPdf` passa a: **tx curta readOnly** (ownership 404 + checa XML gerado + materializa snapshot) → **renderização JasperReports fora de transação**. |
| RF-99.4 | `NotaFiscalService` deixa de ser `@Transactional` **na classe**; a anotação passa a ser por método, para que os trechos de I/O pesado fiquem comprovadamente fora de transação. Os métodos CRUD mantêm o comportamento atual. |
| RF-99.5 | `DanfePdfService.gerarPdf` e o pipeline de geração (`GeracaoNotaFiscalService` / `GeracaoNfaService` / `FiscalRulesValidator` / `NotaFiscalNfeMapper`) passam a receber o snapshot em vez da entidade — ou um adaptador equivalente. Nenhum acesso LAZY fora da sessão (`getItens()`, `getProdutor()`). |
| RF-99.6 | O comportamento externo dos endpoints não muda: mesmos status HTTP, mesmo corpo, mesma ordem de validação. `open-in-view` continua `false`. |
| RF-99.7 | A recheca de status na tx de escrita (RF-99.2) combinada com o `@Version` da #95 garante que duas gerações concorrentes não produzam duas chaves persistidas: a segunda vê `XML_GERADO` (409) ou falha no `@Version` (409). |

### RF-97 — Registro de inconsistências sem conexão aninhada (issue #97)

| ID | Requisito |
|----|-----------|
| RF-97.1 | `NotaFiscalValidacaoService.registrarInconsistencias` deixa de ser `Propagation.REQUIRES_NEW` e passa a `@Transactional` simples (`REQUIRED`), porque com RF-99.2 ele já é chamado **fora** de qualquer transação — abre uma transação própria, única, e nunca uma segunda conexão simultânea. |
| RF-97.2 | A garantia da #22 é preservada: o histórico de inconsistências e a marcação `COM_INCONSISTENCIAS` **sobrevivem** ao 422 devolvido ao cliente, porque a exceção de validação é lançada fora da transação de escrita — não há mais rollback a sobreviver. |
| RF-97.3 | O `catch` de `NfaInvalidaParaEmissaoException` / `NotaFiscalInvalidaParaEmissaoException` / `XmlGenerationException` que registra o histórico e relança continua existindo, agora envolvendo o trecho sem transação. |
| RF-97.4 | O Javadoc de `registrarInconsistencias` é reescrito: a justificativa antiga (`REQUIRES_NEW` para sobreviver ao rollback) deixa de ser verdadeira e passa a documentar a nova invariante — **este método só pode ser chamado fora de transação**. |

## 4. Critérios de aceite

### Comportamento observável

| ID | Critério |
|----|----------|
| CA-1 | Duas chamadas simultâneas a `POST /api/notas/{id}/gerar-xml` sobre a mesma nota RASCUNHO: uma responde 200 com a chave de acesso, a outra responde **409**. Ao final existe **uma única** chave persistida e o XML corresponde a ela. |
| CA-2 | Duas chamadas simultâneas a `POST /api/notas/{id}/enviar-sefaz` sobre a mesma nota XML_GERADO: uma responde 200, a outra **409**. O `SefazTransport` recebe **exatamente uma** chamada de envio. |
| CA-3 | No cenário de CA-2, exatamente **um** `NotaEmitidaEvent` é publicado — o débito da apuração não dobra. |
| CA-4 | Duas chamadas simultâneas a `POST /api/notas/{id}/cancelar` sobre a mesma nota AUTORIZADA: uma 200, a outra 409; **uma** chamada de cancelamento no transporte e **um** `NotaCanceladaEvent`. |
| CA-5 | Com pool de tamanho **1** (H2), 10 chamadas sequenciais e concorrentes de `gerar-xml` sobre nota **inválida** completam todas com 422 e o histórico de inconsistências registrado — **sem timeout de aquisição de conexão e sem deadlock**. |
| CA-6 | O corpo do 409 de conflito otimista segue o envelope `ApiError.errors[]` com `code = CONFLITO_CONCORRENCIA` e não contém nome de exceção, stack trace nem nome de tabela. |
| CA-7 | Falha de comunicação no envio deixa a nota em `PENDENTE_SEFAZ` e devolve 502; o `SefazStatusSyncJob` subsequente reconcilia o status pela chave. |
| CA-8 | Falha de comunicação no cancelamento devolve a nota a `AUTORIZADA` e responde 502; a nota continua cancelável. |
| CA-9 | Nota presa em `ENVIANDO` com `updatedAt` além do timeout é reconciliada pelo job para `PENDENTE_SEFAZ`; presa em `CANCELANDO`, volta para `AUTORIZADA`. |
| CA-10 | `update`, `delete`, `gerar-xml`, `enviar`, `cancelar` e `status-sefaz` sobre nota em `ENVIANDO`/`CANCELANDO` respondem 409. |
| CA-11 | Anti-IDOR intacto: toda operação continua respondendo **404** (não 403, não 409) para nota de outro produtor — inclusive nos novos caminhos com lock. |

| CA-16 | Nota em `PENDENTE_SEFAZ` cuja chave nunca foi transmitida **não** é autorizada pela sincronização e **não** publica evento de débito; nota efetivamente transmitida continua sendo autorizada e publicando (contraponto, guarda da #79). |
| CA-17 | Uma falha isolada no lote de reconciliação não desfaz as demais; nota que saiu do estado transitório entre a leitura e a gravação não é sobrescrita nem contada. |
| CA-18 | Recusa de cancelamento pelo fisco responde 409 com `CANCELAMENTO_REJEITADO` e o motivo da SEFAZ; a nota volta a `AUTORIZADA` e segue cancelável. |

### Qualidade

| ID | Critério |
|----|----------|
| CA-12 | `./mvnw test` verde. Baseline de **478 testes** na abertura da onda (500 ao fim do PR 2); a onda adiciona testes, nenhum removido. |
| CA-13 | Nenhum aviso de connection leak do Hikari (`leak-detection-threshold`, #98) durante a suíte. |
| CA-14 | Nenhuma `LazyInitializationException` em nenhum teste — prova de que o snapshot cobre todo o grafo consumido. |
| CA-15 | Contrato de erro, camadas, ownership e migrations seguem `project-conventions`. Nenhum changeSet existente editado. |

## 5. Testes

### Novos testes de concorrência (integração, H2)

Padrão: `CountDownLatch` para alinhar o start + `ExecutorService` de 2+ threads + `assertThat` sobre
o resultado agregado. Nome sugerido: `*ConcorrenciaIntegrationTest`.

| Teste | Prova |
|-------|-------|
| `gerarXmlConcorrente_segundoRequestRecebe409` | CA-1 |
| `gerarXmlConcorrente_persisteUmaUnicaChaveDeAcesso` | CA-1 |
| `enviarSefazConcorrente_transmiteUmaUnicaVez` | CA-2 (spy/mock no `SefazTransport` contando invocações) |
| `enviarSefazConcorrente_publicaEventoUmaUnicaVez` | CA-3 (listener de teste contando eventos) |
| `cancelarConcorrente_cancelaUmaUnicaVez` | CA-4 |
| `notaInvalidaComPoolUnitario_naoTrava` | CA-5 (`@TestPropertySource` com `spring.datasource.hikari.maximum-pool-size=1` + timeout de aquisição curto, para que um deadlock **falhe o teste** em vez de pendurá-lo) |
| `geracaoXmlNaoMantemConexaoDuranteAssinatura` | CA-13/RF-99.2 |

### Testes unitários (Mockito)

- `NotaFiscalServiceTest`: novas rotas de `gerarXml` (snapshot → geração → persistência), recheca de
  status na tx de escrita, 409 em `ENVIANDO`/`CANCELANDO`.
- `SefazServiceTest`: transição para `ENVIANDO`/`CANCELANDO`; compensação em
  `SefazComunicacaoException` (RF-96.7/96.8); publicação única de evento.
- `NotaFiscalValidacaoServiceTest`: propagação `REQUIRED`, histórico persistido.
- `GlobalExceptionHandlerTest`: `OptimisticLockingFailureException` → 409 + `CONFLITO_CONCORRENCIA`,
  sem vazamento técnico.
- `SefazStatusSyncJobTest`: reconciliação de `ENVIANDO`/`CANCELANDO` por timeout.
- Mockito **strict stubs**: nada de stub para objeto usado só depois de um early return.

### Regressão obrigatória

`NotaFiscalValidacaoFlowTest`, `GeracaoNfaFlowTest`, `NotaFiscalUpdateItemFlowTest`,
`ApuracaoEventoDominioIntegrationTest`, `SefazServiceTest`, `DashboardIntegrationTest` — todos devem
continuar passando sem alteração de asserção de comportamento externo.

## 6. Decisões

| # | Decisão | Por quê |
|---|---------|---------|
| D-1 | **#96 usa estado intermediário `ENVIANDO`/`CANCELANDO`** sob lock pessimista curto, com a I/O fora da transação. | É o desenho final que a **#83** (Onda 6) vai exigir de qualquer forma. Um lock pessimista simples resolveria hoje e seria refeito depois — a própria issue pede que se evite isso. Custo aceito: 2 estados novos + reconciliação por timeout (reúsa o job existente). |
| D-2 | **#99 cobre `gerarXml` e `gerarPdf` no mesmo PR**, com um snapshot compartilhado. | Os dois fluxos precisam exatamente da mesma materialização; separar duplicaria a lógica ou deixaria o segundo PR sendo só fiação. Fecha a issue inteira de uma vez. |
| D-3 | **#97 sai no mesmo PR de #99.** | Com a validação rodando fora da tx externa, a correção de #97 é uma consequência direta: trocar `REQUIRES_NEW` por `REQUIRED`. Um PR separado só trocaria uma anotação. A issue já admite essa fusão. |
| D-4 | **Só `NotaFiscal` recebe `@Version`.** | É a única entidade com transições de estado concorrentes disparadas por request HTTP. `ApuracaoMensal` é protegida pelo fluxo auditado de fechamento/reabertura (#54) e por job serializado; `GuiaFiscal` só muda por `PATCH /pagar`, já guardado por `GuiaJaPagaException`. Avaliado e considerado desnecessário — registrado aqui para não se repetir a pergunta. |
| D-5 | **Falha de comunicação no envio → `PENDENTE_SEFAZ`; no cancelamento → `AUTORIZADA`.** | `PENDENTE_SEFAZ` é o estado de "transmiti, desfecho desconhecido" e o job já o reconcilia pela chave. Para cancelamento isso não serve: `mapStatus` não conhece `CANCELADA`, então o job ressuscitaria a nota como `AUTORIZADA` — a regressão fechada pela #79. Reenviar cancelamento é idempotente no fisco. |
| D-6 | **Reconciliação usa `updatedAt`, sem coluna nova.** | O `@PreUpdate` da entidade já atualiza `updatedAt` na transição para `ENVIANDO`/`CANCELANDO`; um campo dedicado seria redundante. |
| D-7 | **`NotaFiscalService` deixa de ser `@Transactional` na classe.** | `@Transactional` na classe é justamente o que arrasta assinatura RSA e Jasper para dentro da transação. Com escopo explícito por fase, a revisão consegue auditar onde a transação abre e fecha. |
| D-8 | **As fases transacionais usam `TransactionTemplate`**, não `@Transactional` em colaborador nem autoinjeção. | É o precedente do projeto: `ImportacaoNotaFiscalService` (#19/#64) já usa exatamente este desenho pelo mesmo motivo, e documenta que `publishEvent` precisa rodar **dentro** da transação para o listener `AFTER_COMMIT` não descartá-lo. Imune à armadilha da autoinvocação (não há proxy a contornar), mantém o limite da transação legível no ponto de uso e aceita timeout por bloco. Análise completa em `design.md` §10. |

## 7. Riscos

| Risco | Mitigação |
|-------|-----------|
| O snapshot esquece um campo LAZY e estoura `LazyInitializationException` só em runtime. | CA-14: nenhum teste pode lançar `LazyInitializationException`; cobrir `gerarPdf` e `gerarXml` com nota completa (produtor + múltiplos itens + endereço) em teste de integração. |
| Nota fica presa em `ENVIANDO` se o processo cair entre tx 1 e tx 2. | RF-96.9 (reconciliação por timeout no job já existente) + CA-9. |
| `PESSIMISTIC_WRITE` no H2 se comporta diferente do PostgreSQL. | Os testes rodam em H2 `MODE=PostgreSQL`; o teste de 409 concorrente valida o **efeito** (um 200, um 409), não o mecanismo. Documentar que a validação definitiva do lock é em PostgreSQL. |
| Trocar a entidade pelo snapshot na assinatura de `DanfePdfService`/pipeline NF-e toca muitos arquivos. | É o PR maior da onda (D-2); manter o PR focado em mecânica transacional, **sem** mudar regra fiscal nem formatação de XML/DANFE, para que a regressão seja fácil de ler. |
| `ENVIANDO`/`CANCELANDO` vazam para o frontend, que não os conhece. | RF-96.10 os torna estados curtos e não-operáveis. Registrar nota de integração para o frontend (o Angular deve tratar status desconhecido como "em processamento"). |
| Onda 1 não mergeada torna CA-5/CA-13 não-observáveis. | §2: verificar o merge de #90 e #98 antes de iniciar a Fase 4. |

## 8. Fora de escopo

- Transporte SOAP real à SEFAZ e certificado ICP-Brasil (Sprint 7 / Onda 6, #83).
- Reconciliação de **evento de cancelamento** via consulta de eventos na SEFAZ (depende de #83).
- `@Version` em `ApuracaoMensal`/`GuiaFiscal` (D-4).
- Auth/RBAC real, rate limiting, mascaramento de CPF/CNPJ em log (Sprint 8).
- Qualquer mudança de regra fiscal, cálculo de IBS/CBS, layout de XML ou de DANFE.

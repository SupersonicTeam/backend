# Backlog até a banca e depois: ordem por sessões

Auditoria de 2026-11-06 contra a `main` em `6dcc8a6`: 45 issues abertas, cada critério conferido no código, com um cético tentando derrubar as que pareciam prontas. Dois planos independentes foram julgados e sintetizados aqui. **Aprovado pelo Murillo em 2026-11-06** (decisões no fim). S1 e S2 concluídas no mesmo dia (andamento logo abaixo).

## Andamento

- **S1 e S2 concluídas em 2026-11-06:** 8 issues, 10 PRs (#394 a #406), `main` em `40914ae`, 2.274 testes sem falha, deploy na VPS conferido. Na VPS, nenhuma nota em `XML_GERADO` (a #389 não recusa nada ali) e as 157 notas com a busca nova da #271. Nenhuma ferramenta das duas sessões tocou o conjunto cego.
- **Issues novas da S1 e da S2:** #396 entrou na S3 (resposta errada sobre produto, precisa estar no SHA oficial); #399 na S14; #402 na S13. O contrato final da #341 está na frontend#164.
- **Portão do benchmark:** o `sessaoParalela.tambemExige` ainda não pede #369, #357, #364 e a S3. Fica para quando o Murillo fixar o SHA.

## Regras do fluxo

- 1 issue por PR, em série: branch da `origin/main` atualizada, PR, code review avançado, correção, merge sem pedir OK, próxima issue.
- Segurança fica na última sessão. Achado de segurança no caminho vira issue nova; a única exceção é IDOR em rota nova.
- Esta sessão orquestra e fica na `main`. As sessões de desenvolvimento têm worktree próprio, e duas em paralelo nunca dividem arquivo.
- Nenhuma sessão de código lê `docs/avaliacao/benchmark/perguntas-benchmark.json` nem o repositório privado `SupersonicTeam/ceres-benchmark-cego`, e nenhuma recebe texto de inédita. Mensagens citam só o id. Desde o PR #392 as inéditas não estão mais no backend; a regra completa está no `CLAUDE.md`.
- Todo merge na `main` reimplanta o backend na VPS, sem filtro de caminho. Por isso nenhum merge durante a medição da S5 e só correção urgente entre o congelamento e a banca.

## Datas

Confirmadas pelo Murillo em 2026-11-06: resposta do contador até 13/11, congelamento da demonstração em 26/11 e banca em 03/12.

| Data | Marco |
|---|---|
| 19/11 | corte da S3: o que não mergeou vai para depois da banca |
| 25/11 | fim da S5 (medição) |
| 26/11 a 03/12 | SHA fixado, rodada oficial do benchmark, só correção urgente |
| 03/12 | banca |

## VPS em 2026-11-06

8 OCPU ARM (Neoverse-N1), 47 GiB de RAM, disco de 96 GB com 29% em uso, Docker 29.9.2, contêineres saudáveis, site e API respondendo 200, backend em `sha-6dcc8a6`, profile `homolog`. Ollama em CPU com `qwen3:4b-instruct` e `bge-m3`, `OLLAMA_NUM_PARALLEL=1`. Os textos ainda dizem 4 OCPU e 24 GB em `deploy/README.md`, `docs/avaliacao/benchmark/README.md`, `relatorio.py` e `graficos.py`; a correção está na S5.

## Visão geral

| Sessão | Tema | Issues, em ordem | Quando | Modelo e esforço | Roda junto com |
|---|---|---|---|---|---|
| S1 | Notas: regra fiscal versionada, busca e carteira do contador | #389 → #271 → #341 → #353 | concluída em 2026-11-06 | sonnet / high (medium na #353) | S2 |
| S2 | Ceres: precisão e texto que o produtor lê | #369 → #342 → #357 → #364 | concluída em 2026-11-06 | sonnet / high na #369, low na #342, medium na #357 e na #364 | S1 |
| S3 | Ceres 2.0: exclusão no tratamento, glossário e lei em linguagem simples | #396 → #267 → #269 | antes da banca | sonnet / high | S4 |
| S4 | Qualidade: Swagger, fluxos em PostgreSQL e piso de cobertura | #154 → #152 → #151 | antes da banca | haiku low na #154; sonnet medium na #152 e na #151 / low a medium | S3 |
| S5 | Medição na VPS: Ceres, classificador e p95 | #270 → #265 → #153 | antes da banca | sonnet / medium | - |
| S6 | Pós-banca imediato: remover o seed da demonstração | #317 | depois da banca | sonnet / medium | - |
| S7 | NF-e pós-banca: unidade tributável, destinatário e ICMS por UF | #362 → #300 → #334 | depois da banca | sonnet high na #362 e #300; opus high na #334 / high | - |
| S8 | Ceres pós-banca: sinais de nota e resposta de débito | #363 → #329 | depois da banca | sonnet / medium | - |
| S9 | Crédito e situação do produtor no IBS/CBS | #356 → #299 → #281 | depois da banca | opus / high (xhigh na #281) | - |
| S10 | Ceres pós-banca: explicação do modelo em duas etapas | #268 | depois da banca | opus na issue filha; sonnet na #268 / high | - |
| S11 | Ceres pós-banca: base de lei, NCM e busca | #283 → #200 | depois da banca | sonnet high na #283; opus high na #200 / high | - |
| S12 | Ceres: relatório de uso e feedback com curadoria | #311 → #312 | depois da banca | sonnet / medium na #311; high na #312 | - |
| S13 | API: rotas de guias separadas por papel e quem encerrou o vínculo | #273 → #402 | depois da banca | sonnet / medium | - |
| S14 | Segurança consolidada (última sessão) | #289 → #252 → #345 → #367 → #313 → #399 → #263 | depois da banca | sonnet; haiku low na #289 e na #367 / high | - |

Fora do fluxo autônomo: #284, #275, #198, #202, #261, #321, #322. A #387 fechou com o PR #392. Nenhuma issue pode ser fechada agora: a #198 teve o fechamento refutado e as demais ainda dependem de medição ou de ajuste de escopo.

Pela ordem, S1 e S2 rodam em paralelo; S3 e S4 também. A partir da S5, uma por vez. Depois da S5 vem o portão do Murillo: fixar o SHA e rodar o benchmark oficial.

## Sessões em detalhe

### S1. Notas: regra fiscal versionada, busca e carteira do contador

**Objetivo:** Fechar a brecha da nota antiga em XML_GERADO e entregar o que o contador usa na demonstração: busca por produto e documento, notas de toda a carteira e histórico de vínculos.

**Por que nessa ordem:** Começa já: não depende do Murillo, do contador, do Ollama real nem do frontend para mergear. A #389 vem primeiro porque é pré-requisito de toda regra fiscal seguinte e porque a frase nova precisa estar em FrasesSinaisNota antes de a sessão 2 acentuar e mexer nesse arquivo. A #271 antes da #341 porque a rota agregada reaproveita a busca. As quatro ficam na mesma sessão, em série, porque dividem NotaFiscal.java, NotaFiscalService, o changelog e CLAUDE.md. Nada aqui muda o que o benchmark mede, salvo a frase de um sinal que o seed não produz.

**Revisão:** opus na #389 (estado fiscal); sonnet high nas demais. **Tamanho:** G.

**Condições:** Nenhum para começar. Enquanto a sessão 2 rodar, só esta sessão edita CLAUDE.md, ErrorCode e o changelog.

- **#389** Regra fiscal nova não alcança nota já em XML_GERADO nem o rascunho antes da validação
  Nada existe. ChangeSet novo (próximo número depois do 0054) com a coluna da versão da regra fiscal em nota_fiscal, gravada em gerar-xml a partir de uma constante única. Em enviar-sefaz, XML de versão menor ou nula é recusado com 409 de ação (ErrorCode novo), sem transmitir, e a nota volta a estado editável com registro no histórico, no padrão APROVACAO_CADUCADA. Nota AUTORIZADA não é tocada. No create e no update do rascunho, roda só a checagem de cClassTrib admitido e avisa pelo mecanismo de sinais já usado pelo ICMS_A_DEFINIR, sem 422, para não impedir salvar; a frase nova entra em FrasesSinaisNota. Testes: XML antigo recusado sem transmitir, nota nova intacta, rascunho avisa. Asserção no LiquibaseChangelogIntegrationTest. Registrar nas conventions e no codebase-map a regra: mudou mapper ou validador, sobe a constante.
- **#271** Busca de notas não acha por produto nem por CPF/CNPJ do destinatário (#174)
  Nada existe. busca_normalizada passa a incluir descrição e NCM dos itens e o CPF/CNPJ do destinatário (só dígitos), recalculando também quando só um item muda, porque o @PreUpdate da nota não dispara nesse caso. Alargar a coluna de 600 em changeSet novo, sem truncar em silêncio, com asserção Liquibase. Termo de 11 ou 14 dígitos casa o documento; 1 a 2 dígitos segue prefixo do número. Versionar a regra no BuscaNotasLegadoRunner com backfill em lotes: ele roda antes do Tomcat aceitar conexões, então não pode alongar o boot da VPS. EXPLAIN (ANALYZE, BUFFERS) com 200 mil notas em Postgres local, meta abaixo de 50 ms. Testes: produto, CNPJ, isolamento entre dois produtores. Abrir issue de segurança para a sessão 14 sobre a cópia do documento em claro nessa coluna, sem tratar aqui.
- **#341** feat: listagem de notas de toda a carteira do contador (GET /api/contador/notas)
  Decisão do Murillo em 2026-11-06: entra antes da banca, e o Luiz adota no frontend (frontend#164). O 403 da origem da issue já saiu (frontend#136, PR frontend#142, 04/11): `/documentos` usa a `NotasContador`, com seletor de cliente e `GET /api/contador/produtores/{produtorId}/notas`. A rota agregada evita escolher o cliente. Nada existe no backend; o corpo da issue vale como spec. GET /api/contador/notas reaproveitando CarteiraNotasService e os filtros de /api/notas, com produtorId opcional (fora da carteira 404) e responsavel eu/nenhum/id. Consulta paginada com IN da carteira ativa, sem N+1; item com produtorId e produtorNome, sem documento; 403 para produtor e SEM_ESCRITORIO. GET /api/contador/notas/resumo com os grupos em constante documentada, seguindo as sete caixas de `CAIXAS_TRIAGEM` em `notas-contador.ts` (revisão, travadas, rascunhos, devolvidas, autorizadas, canceladas e todas) e aceitando os mesmos filtros da lista, inclusive `busca`. O item traz `produtorId`, porque aprovar, devolver e prévia seguem nas rotas por cliente. Testes de escopo (outro escritório, vínculo suspenso e encerrado) e de contagem de queries. CLAUDE.md na tabela e na matriz. Ao mergear, comentar o contrato final na frontend#164.
- **#353** feat(vinculos): o produtor ver os vínculos que já encerrou (histórico em GET /api/vinculos)
  Só o parâmetro ?situacao= repetível em GET /api/vinculos, com padrão ATIVO e SUSPENSO (o Angular atual não muda), consulta por situação no VinculoCarteiraRepository, ENCERRADO com fim preenchido, situação inválida 400 no envelope errors[], teste com dois produtores. Sem encerradoPor e motivo: exigem changeSet e os vínculos antigos ficariam nulos; se a equipe quiser, é issue nova. CLAUDE.md.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`, `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`, `src/main/java/br/com/agrocontabil/service/SefazService.java`, `src/main/java/br/com/agrocontabil/nfe/FiscalRulesValidator.java`, `src/main/java/br/com/agrocontabil/service/SinaisNotaConferirService.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/FrasesSinaisNota.java`, `src/main/java/br/com/agrocontabil/common/ErrorCode.java`, `src/main/resources/db/changelog/db.changelog-master.yaml`, `src/test/java/br/com/agrocontabil/db/LiquibaseChangelogIntegrationTest.java`, `CLAUDE.md`, `.claude/skills/project-conventions/SKILL.md`, `.claude/skills/codebase-map/SKILL.md`

### S2. Ceres: precisão e texto que o produtor lê

**Objetivo:** Corrigir o tratamento errado por produto, informar a origem do dado, acentuar os textos fixos e reduzir falso positivo dos sinais, tudo antes de fixar o SHA da coluna oficial.

**Por que nessa ordem:** A #369 entrega tratamento e citação errados e precisa estar no SHA oficial. A #342 é pequena. A #357 fixa o texto antes do glossário da sessão 3 e da nota de clareza da #275. A #364 entra depois da #357 pela frase em FrasesSinaisNota. Fica antes da #329, que usa os mesmos arquivos e é pós-banca pelo próprio título.

**Revisão:** sonnet high. **Tamanho:** G.

**Condições:** #369 e #342 começam já. O PR de templates da #357 e a #364 só depois do merge da #389 (FrasesSinaisNota e SinaisNotaConferirService). Não edita CLAUDE.md, ErrorCode nem o changelog enquanto a sessão 1 rodar; as notas vão no corpo do PR. Nenhum agente lê perguntas-benchmark.json nem o repositório cego, e nenhum roda o benchmark. Excluir esse caminho das buscas.

- **#369** Ceres: busca de produto por nome ignora negacao (exceto, nao) nas descricoes dos anexos
  tokens() e melhores() do TratamentoProdutoService passam a descartar ou penalizar os tokens do trecho negado (exceto, excluídos, ressalvados, salvo, não) até vírgula, ponto e vírgula ou fim. Antes, levantar as ocorrências nas descrições da referência e dos itens do changeSet 0048. Regressão: semente de arroz, arroz em casca, ração para cão, item com ressalvados do Anexo I. Coerência NCM a NCM com ReducaoNcmResolver verde. Casos só do seed e dos anexos.
- **#342** Ceres: informar em fontesDados se a consulta é dado do produtor ou tabela pública
  Campo origem (PRODUTOR ou TABELA_OFICIAL) em FonteDado, em AssistenteChatResponse e no mapeamento do AssistenteService, definido onde a consulta é registrada no ContextoAssistente; consultarTratamentoProduto é TABELA_OFICIAL. Sem rótulo em português: o frontend traduz. Aditivo. docs/contrato-frontend-ceres.md e teste de contrato.
- **#357** Textos da Ceres sem acento: "e" e "é" se confundem e mudam o sentido
  Acentuar só o texto entregue ao produtor, em 3 PRs como a issue pede (Refs nos dois primeiros, Closes no último): atalhos (TextoReformaTributaria, AtalhoReformaTributaria, FundamentoRetencao); templates de dados (RedatorRespostaDados, FrasesSinaisNota, TemplateTratamentoProduto, TextosDoBackend e as mensagens entregues por FerramentasDadosProdutor); fallback (ModoFallback). Prompts do modelo e descrições de @Tool ficam como estão, porque mudá-los altera o que o modelo vê. Normalização sem acento e caixa só na comparação. Mapear antes os testes de string exata e quem lê o texto do próprio backend (VerificadorNegativaDados, AvaliadorResposta). Teste que varre os textos fixos atrás de palavras comuns sem acento, com lista de exceções. Citações da #347 verdes; UTF-8 no JSON e no SSE.
- **#364** Sinais de nota a conferir: termos de servico agricola e cruzamento com NCM e unidade
  Termos novos em termos-servico.txt (gradagem, subsolagem, aração, destoca, calagem, secagem, ensilagem) e exceções em excecoes-servico.txt (bico de pulverização, seco e limpo). Cruzar termo com unidade comercial (hora, diária, ha contra kg, saca, litro, un) e com NCM de mercadoria. Mensagem cita a NFS-e de padrão nacional, com artigo conferido na base de lei. Criar o README de sinais-nota/, que não existe. Tabela de testes com os 4 casos.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/service/TratamentoProdutoService.java`, `src/main/java/br/com/agrocontabil/assistente/FonteDado.java`, `src/main/java/br/com/agrocontabil/assistente/AssistenteService.java`, `src/main/java/br/com/agrocontabil/dto/AssistenteChatResponse.java`, `src/main/java/br/com/agrocontabil/assistente/orquestracao/TextoReformaTributaria.java`, `src/main/java/br/com/agrocontabil/assistente/orquestracao/FundamentoRetencao.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/RedatorRespostaDados.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/FrasesSinaisNota.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/TemplateTratamentoProduto.java`, `src/main/java/br/com/agrocontabil/assistente/fallback/ModoFallback.java`, `src/main/java/br/com/agrocontabil/service/SinaisNotaConferirService.java`, `src/main/resources/sinais-nota/`, `docs/contrato-frontend-ceres.md`

### S3. Ceres 2.0: glossário e lei em linguagem simples

**Objetivo:** Dar ao produtor a explicação das siglas por texto do backend, sem novo texto do modelo sobre lei sem medição.

**Por que nessa ordem:** Depende dos textos acentuados da sessão 2 e tem de terminar antes da medição, que precisa do prompt final. O corte da explicação segue a decisão de 05/11 na #347: 10 de 10 rodadas passaram nos validadores com uma frase de sentido errado.

**Revisão:** opus high. **Tamanho:** M.

**Condições:** Sessão 2 mergeada. Glossário revisado por humano antes do merge: exceção ao merge sem OK. PR do prompt da #269 exige modelo real e OK do Murillo. Linha de corte: o que não estiver mergeado até 19/11 vai para o pós-banca, e a medição segue sem ele.

- **#396** Ceres: tratamento de produto não informa a exclusão que está na descrição do item
  Achado no review da #369. Levar para `TratamentoProduto.condicao` o trecho negado da descrição do item ou NCM escolhido, curto e sem números, com a mesma regra de `semTrechoNegado`. Cuidado com `TemplateTratamentoProduto` e `ValidadorNumeros` (listas de NCM no "exceto" dos Anexos I e VII). Nenhum caso existente (soja, arroz, leite, ovos, adubo) muda sem decisão explícita.
- **#267** [Ceres 2.0 · 5/8] Glossario do produtor e explicacao opcional do modelo, conferida em codigo
  Recorte determinístico: assistente/glossario-produtor.yml (inclui ADCT, LC, EC, PIS, Cofins, ISS, ICMS), carga e teste estático das siglas nos textos fixos (AtalhosFixos, TextoReformaTributaria), revisão da densidade de citações do atalho. ExplicadorResposta, VerificadorFidelidade, app.assistente.explicacao.*, consumo da cota e desfechos na auditoria saem para uma issue filha, criada neste PR e executada na sessão 10 com a #268, salvo decisão contrária do Murillo. Atualizar o corpo da #267 com o recorte.
- **#269** [Ceres 2.0 · 7/8] Rota de legislacao em linguagem simples, sem recusa falsa (#258) nem ruido no MISTO (#260)
  Itens 3 e 4 já entregues: #258 e #260 estão fechadas, e no código CITACAO_INVALIDA com marcas válidas já cai em TRECHOS_SEM_CONFIRMACAO. Atualizar o corpo. PR principal: linha determinística O que isso quer dizer, montada pelo backend depois das validações, e teste de nenhuma sigla do glossário sem explicação (ChatModelSimulado). A diretriz de linguagem em PromptsCeres.sistemaLegislacao fica em PR separado e só mergeia depois de rodar com o modelo real em perguntas de docs/avaliacao/treino/ e de o Murillo ver os exemplos; o texto livre da rota de lei ainda sai do modelo. A meta de clareza depende da #275.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/service/TratamentoProdutoService.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/TemplateTratamentoProduto.java`, `src/main/resources/assistente/glossario-produtor.yml`, `src/main/java/br/com/agrocontabil/assistente/RotaLegislacao.java`, `src/main/java/br/com/agrocontabil/assistente/PromptsCeres.java`, `src/main/java/br/com/agrocontabil/assistente/orquestracao/AtalhosFixos.java`, `src/main/java/br/com/agrocontabil/assistente/orquestracao/TextoReformaTributaria.java`, `docs/ceres-assistente.md`, `CLAUDE.md`

### S4. Qualidade: Swagger, fluxos em PostgreSQL e piso de cobertura

**Objetivo:** Fechar as entregas de documentação e teste que o TCC cobra, sem mudar comportamento.

**Por que nessa ordem:** Entrega do TCC e sem risco funcional. Fica depois da sessão 1 porque a #154 toca quase todos os controllers e DTOs, e antes da medição porque todo merge reinicia a aplicação na VPS. A #152 antes da #151 para os testes novos contarem na cobertura.

**Revisão:** sonnet medium. **Tamanho:** M.

**Condições:** Sessão 1 mergeada, e sessão 2 mergeada na parte da #342 (AssistenteChatResponse). Não edita CLAUDE.md enquanto a sessão 3 rodar.

- **#154** [Sprint 10] Documentação da API com SpringDoc OpenAPI (Swagger)
  Levantar por varredura os mapeamentos sem @Operation (110 de 127) e os DTOs sem @Schema, inclusive os das rotas novas da sessão 1. Preencher, criar GroupedOpenApi (notas, apuração, chat, relatórios, contador, auth) e um teste que varre o RequestMappingHandlerMapping e falha se faltar @Operation. Swagger segue desligado em prod; a banca vê em homolog ou dev.
- **#152** [Sprint 9] Testes de integração nos fluxos críticos
  Os quatro fluxos já têm integração em H2. Acrescentar base abstrata PostgreSQL (Testcontainers) e testes de criar nota, gerar XML, enviar ao mock até AUTORIZADA, cancelar dentro e fora do prazo, e reabrir apuração. Confirmar que a CI roda os Testcontainers. Importação fica em H2, salvo se a base abstrata a cobrir sem custo. Fechar com esse recorte.
- **#151** [Sprint 9] Testes unitários no backend (JUnit 5 + Mockito) — meta 80% de cobertura
  Rodar ./mvnw verify e ler a cobertura real. Fixar o piso do jacoco:check em LINE (hoje 0.0) com exclusões justificadas (config, DTOs, DataLoader, demonstracao, avaliacao), no valor medido menos 1 ponto, e subir até 0,80 cobrindo os módulos abaixo. A CI não pode ficar vermelha. Reescrever o item AiService, que não existe, e dispensar o Enforcer.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/controller/`, `src/main/java/br/com/agrocontabil/dto/`, `src/main/java/br/com/agrocontabil/config/OpenApiConfig.java`, `pom.xml`, `.github/workflows/ci.yml`

### S5. Medição na VPS: Ceres, classificador e p95

**Objetivo:** Gerar os números do relatório e das decisões de configuração na VPS de 8 OCPU e 47 GiB, com o código final, antes de fixar o SHA da coluna oficial.

**Por que nessa ordem:** Precisa do prompt e das funções finais e de main quieta, porque qualquer merge, inclusive só de docs, reinicia o backend na VPS e suja os tempos. Termina antes do congelamento porque o resultado pode mudar tetos e contexto. Depois dela vem o portão do Murillo: fixar o SHA e rodar o benchmark oficial.

**Revisão:** haiku low nos docs; sonnet medium no script k6 e em ajuste de configuração. **Tamanho:** M.

**Condições:** Sessões 2, 3 e 4 mergeadas ou cortadas. Acesso SSH à VPS e janela combinada, sem nenhum merge em paralelo. Ollama com qwen3:4b-instruct e bge-m3 e base de lei ingerida. Nenhum agente lê as perguntas do benchmark.

- **#270** [Ceres 2.0 · 8/8] Inferência local no ARM e metas de tempo
  Código dos itens 2 e 3 já está na main (#351, #354, #359). Medir uma coisa por vez, com o harness da #264 e 3 repetições: Q4_0 contra Q4_K_M, qwen3:8b e 30B-A3B como medição, prompt_eval_count com prefixo em cache e efeito na cota #166 e na auditoria, num_ctx 6144 e AI_TETO_LEGISLACAO=512 pela contagem de RESPOSTA_CORTADA. Tabela antes e depois (p50, p95 por rota, acerto, RAM) em docs/ceres-assistente.md. Corrigir 4 OCPU e 24 GB em deploy/README.md, no README do benchmark, em relatorio.py e graficos.py, e a premissa Always Free da issue. Não troca modelo nem configuração padrão sem OK explícito; ajuste aprovado sai em PR próprio.
- **#265** [Ceres 2.0 · 3/8] Classificacao de intencao por regras + embeddings (LLM so no desempate)
  Sem código (PR #328). Na mesma rodada, registrar em docs/avaliacao/linha-de-base-ceres-2-0.md o acerto de rota, os erros das regras, o p95 do classificador com Ollama ocioso e ocupado e a proporção de desempate. Fechar com os números; critério que falhar vira issue própria. #253 e #199 já estão fechadas.
- **#153** [Sprint 9] Performance: garantir resposta < 2s em p95
  Só o benchmark: script k6 versionado sobre dashboard, listagem de notas, apuração e calendário, contra stack isolada na VPS (projeto compose e banco próprios, com volume sintético no script), para não sujar o banco da demonstração. p95 registrado e teste de contagem de statements para N+1. Cache e /actuator/metrics viram issues novas; a exposição de métricas vai para a sessão 14. Se o p95 passar de 2 s, abrir issue, não otimizar aqui.

**Arquivos que a sessão reserva:** `docs/ceres-assistente.md`, `docs/avaliacao/linha-de-base-ceres-2-0.md`, `deploy/README.md`, `deploy/.env.example`, `docs/avaliacao/benchmark/README.md`, `docs/avaliacao/benchmark/ceres_benchmark/relatorio.py`, `docs/avaliacao/benchmark/ceres_benchmark/graficos.py`

### S6. Pós-banca imediato: remover o seed da demonstração

**Objetivo:** Tirar o código temporário da banca logo depois da apresentação, como decidido em 01/11.

**Por que nessa ordem:** Primeira depois da banca porque edita CLAUDE.md, compose e testes de agendamento, e reduz a superfície da sessão de segurança.

**Revisão:** sonnet low. **Tamanho:** M.

**Condições:** Banca realizada.

- **#317** [Demonstração] Remover o seed da banca depois da apresentação (#315)
  Remover config/demonstracao (main e test), a propriedade DEMONSTRACAO_HABILITADA, a variável em deploy/compose.yaml e as referências em CLAUDE.md, codebase-map, deploy/README.md, premissas-e-escopo e demonstracao-banca.md. Incluir o que a issue não lista: scripts de reset da #391 e ScriptsResetDemonstracaoTest, CotaDemonstracaoCeres no SchedulerLockConfigTest, e conferir DataLoader, DataLoaderTest e AssistentePropertiesTest. Copiar o compose na VPS e subir. Não zerar o banco.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/config/demonstracao/`, `src/test/java/br/com/agrocontabil/config/demonstracao/`, `src/test/java/br/com/agrocontabil/config/SchedulerLockConfigTest.java`, `src/main/resources/application.yml`, `deploy/compose.yaml`, `deploy/README.md`, `CLAUDE.md`

### S7. NF-e pós-banca: unidade tributável, destinatário e ICMS por UF

**Objetivo:** Corrigir o grupo tributável e fechar os débitos executáveis do leiaute, com a marca de versão da #389 cobrindo cada mudança.

**Por que nessa ordem:** A #389 já está na main. A própria #362 pede depois da banca, salvo exigência da homologação; a #300 tem título Pós-banca. Vem antes da sessão 9, que também altera o mapper. Se a fila pré-banca esvaziar antes de 19/11, a #362 é a primeira a ser puxada.

**Revisão:** opus high. **Tamanho:** G.

**Condições:** Banca realizada. Pergunta E do #284 para a #334; sem ela, a #334 fica de fora e a sessão termina na #300.

- **#362** NF-e: unidade tributável igual à comercial (café em saca sai com uTrib SC em vez de kg)
  Conferir no MOC e nas NTs quando uTrib deve ser a unidade estatística do NCM e citar no PR. Tabela de unidade e fator por NCM (changeSet com insert), qTrib = qCom × fator, vUnTrib = vProd / qTrib, XSD válido, item em kg inalterado. Subir a constante da #389. Se o MOC não obrigar em operação interna, registrar e rebaixar o escopo. O sinal de unidade sem fator vira issue nova, executada na sessão 8.
- **#300** Débitos da NF-e e do DANFE após a NT 2026.010: QR Code, Imposto Seletivo, fone e bairro do destinatário, CBS de 2027
  Dividir: bairro e fone do destinatário são executáveis; QR Code, Imposto Seletivo e CBS de 2027 viram issues congeladas até a publicação oficial. Bairro nullable em EnderecoDestinatario, DTO e changeSet, mantendo o literal CENTRO só quando não informado, porque o XSD exige xBairro e o Angular ainda não envia. Fone opcional. Subir a constante e abrir a issue do frontend.
- **#334** Grupo de ICMS por item na NF-e com diferimento (CST 51) conforme a UF
  Grupo básico, CST 51, 422, DANFE e sinais já entregues na #377. Reescrever a issue para o resto: tabela de diferimento por UF, NCM e destinatário em changeSet com insert; pDif e vICMSDif se o layout exigir; testes PR e MT; XSD. CSOSN fica fora, pela premissa do regime regular e da #298, salvo decisão contrária.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapper.java`, `src/main/java/br/com/agrocontabil/nfe/FiscalRulesValidator.java`, `src/main/java/br/com/agrocontabil/nfe/TributacaoIcms.java`, `src/main/java/br/com/agrocontabil/model/EnderecoDestinatario.java`, `src/main/java/br/com/agrocontabil/danfe/DanfePdfService.java`, `src/main/java/br/com/agrocontabil/service/SinaisNotaConferirService.java`, `src/main/resources/db/changelog/db.changelog-master.yaml`

### S8. Ceres pós-banca: sinais de nota e resposta de débito

**Objetivo:** Ampliar sinais e a resposta de débito, mudanças que alteram gabarito e por isso esperam a rodada oficial.

**Por que nessa ordem:** Mudam o que o benchmark mede. A #364 já mergeou na sessão 2, então não há conflito nos arquivos de sinais. Fica depois da sessão 7 porque a #334 também toca os sinais de ICMS.

**Revisão:** sonnet high. **Tamanho:** M.

**Condições:** Banca e rodada oficial concluídas. Revisão do dicionário da #329 pela equipe ou pelo avaliador.

- **#363** Ceres: debito pendente mostra tambem as guias a vencer, em linha secundaria
  guiasEmAtraso com três blocos: em atraso, vence hoje e linha secundária com as a vencer e o total até a data. O DTO GuiasEmAtraso ganha vence-hoje e a-vencer. Frase sem negativa absoluta quando não há atraso; a conta da #257 não muda. Versão nova do gabarito e do ValoresEsperados; testes dos 4 casos.
- **#329** [Pos-banca] Notas a conferir: sinal NCM_NAO_BATE_DESCRICAO (e atalho no frontend)
  Só o item 1 (o frontend está na frontend#132). Dicionário produto para prefixo de NCM em sinais-nota/, código NCM_NAO_BATE_DESCRICAO, frase, tabela de testes. Taxa de falso positivo medida em conjunto de teste próprio, porque o seed saiu na sessão 6. Incluir aqui a issue do sinal de unidade sem fator que sai da #362.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/assistente/ferramentas/RedatorRespostaDados.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/ComposicaoRespostaDados.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/FrasesSinaisNota.java`, `src/main/java/br/com/agrocontabil/service/SinaisNotaConferirService.java`, `src/main/java/br/com/agrocontabil/config/avaliacao/ValoresEsperados.java`, `docs/avaliacao/gabarito-ceres.json`

### S9. Crédito e situação do produtor no IBS/CBS

**Objetivo:** Corrigir o crédito dos insumos do Anexo IX, aplicar a cooperativa optante e modelar a situação do produtor, com decisão escrita do contador.

**Por que nessa ordem:** Mudam valores que a Ceres consome, então só depois da rodada. A #356 estabiliza CreditoTributarioService antes da feature maior; a #299 toca a mesma classificação; a #281 é a maior e vem por último.

**Revisão:** opus xhigh. **Tamanho:** G.

**Condições:** Banca realizada e sessão 7 concluída. Respostas C, 6, D, B1 e 8 do #284, e percentual do art. 168. Spec da #281 aprovado pelo Murillo.

- **#356** Crédito estimado nos insumos do Anexo IX sem considerar o diferimento (art. 138, § 2º)
  Só depois da resposta C. Regra única de crédito para item do Anexo IX com fornecedor do regime regular, via ClassificacaoTributariaService, com motivo e artigo; grupo de diferimento do XML importado; coerência de apuração, dashboard e relatório; teste de simetria da #86; docs. Sem crédito zero por palpite.
- **#299** Aplicar as respostas do contador (#284, perguntas 1 a 6) na tabela de tratamento por NCM: leite cru, arroz em casca e cooperativa
  Leite (#374, #381) e arroz em casca (#366) entregues. A demonstração não tem comprador cooperativa: CenarioDemonstracao só traz cerealistas, supermercados, frigorífico e afins, então a 6b já está atendida e nada muda antes da banca. Atualizar o corpo já na sessão 1 de triagem do orquestrador. Resta: cClassTrib 200020 com marca de cooperativa optante e de associado (pergunta 6) e o 1006.10.91 parboilizado (pergunta D).
- **#281** Modelar a situação do produtor no IBS/CBS: não contribuinte (art. 164), optante (art. 165) e crédito presumido (art. 168)
  Spec e divisão antes do código. Lado vendedor: situação com vigência, apuração e XML do não contribuinte (CST 410, cClassTrib 410014), funções da Ceres. Lado comprador: crédito presumido do art. 168 por evento, em issue própria, até sair o percentual. Asserção Liquibase; remover a premissa da #280 quando o primeiro recorte entrar.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/service/CreditoTributarioService.java`, `src/main/java/br/com/agrocontabil/service/ClassificacaoTributariaService.java`, `src/main/java/br/com/agrocontabil/service/ApuracaoService.java`, `src/main/java/br/com/agrocontabil/model/Produtor.java`, `src/main/java/br/com/agrocontabil/nfe/NotaFiscalNfeMapper.java`, `src/main/java/br/com/agrocontabil/assistente/ferramentas/FerramentasDadosProdutor.java`, `docs/premissas-e-escopo.md`, `CLAUDE.md`

### S10. Ceres pós-banca: explicação do modelo em duas etapas

**Objetivo:** Reabrir com medição a explicação gerada pelo modelo e a entrega em duas etapas no SSE.

**Por que nessa ordem:** Sem a explicação ligada, a #268 seria código morto na banca e soma risco ao bulkhead perto do congelamento. Sobe para antes da sessão 5 só se o Murillo decidir que a explicação entra na Ceres medida.

**Revisão:** opus high. **Tamanho:** G.

**Condições:** Banca realizada, ou decisão do Murillo de antecipar. Ollama real para a medição do descarte.

- **#268** [Ceres 2.0 · 6/8] Entrega em duas etapas no SSE: o numero primeiro, a explicacao depois
  Primeiro a issue filha da #267: ExplicadorResposta, VerificadorFidelidade, app.assistente.explicacao.* com enabled=false, cota #166, desfechos EXPLICACAO_ACEITA e EXPLICACAO_DESCARTADA_<motivo> com changeSet e asserção. Medir com modelo real e mostrar exemplos ao Murillo antes de seguir, pela lição da #347. Depois a #268: evento SSE explicacao entre resposta e fim, campo aditivo no POST /chat, bulkhead liberado após a explicação ou o timeout, erro posterior não invalida a resposta, testes de ordem (aceita, descartada, timeout), contrato e frontend#105.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/assistente/AssistenteService.java`, `src/main/java/br/com/agrocontabil/assistente/RotaDados.java`, `src/main/java/br/com/agrocontabil/controller/AssistenteController.java`, `src/main/java/br/com/agrocontabil/dto/AssistenteChatResponse.java`, `src/main/resources/application.yml`, `src/main/resources/db/changelog/db.changelog-master.yaml`, `CLAUDE.md`, `docs/ceres-assistente.md`

### S11. Ceres pós-banca: base de lei, NCM e busca

**Objetivo:** Ampliar a base de conhecimento e medir a busca de lei antes de qualquer reranker.

**Por que nessa ordem:** Mudam o RAG e dependem da taxa de descarte da #269 e dos dados da #270. A #283 antes porque amplia a base que a #200 mede.

**Revisão:** opus high na #200; sonnet high na #283. **Tamanho:** G.

**Condições:** Banca realizada e sessão 5 concluída. Ollama real e base de lei. Máquina no Brasil para a parte 3 da #283.

- **#283** Base de dados da Ceres: tabela oficial cClassTrib + anexos por NCM, regulamentos e nomenclatura NCM
  Parte 1 entregue (#325). Dividir as partes 2 a 4. Executar a parte 4 (nomenclatura NCM do Siscomex na rota de dados; a #248 já está fechada, então não há bloqueio) e a parte 2 (Decreto 12.955/2026 e Resolução CGIBS 6/2026 pelo profile ingestao). A parte 3 exige máquina no Brasil e fica com o Murillo.
- **#200** Ceres: busca de lei antes do gerador (reranker, busca híbrida RRF, embedding ajustado)
  Dividir. (a) recall@k no relatório da avaliação, só medição. (b) Busca híbrida com RRF, mantendo limiar e vigência, se o recall@k e a taxa de descarte justificarem. (c) Reranker em serviço próprio só com aprovação de container novo. Cada passo em 3 repetições.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/assistente/rag/IngestaoLegislacaoService.java`, `src/main/java/br/com/agrocontabil/assistente/rag/BuscaLegislacaoService.java`, `src/main/java/br/com/agrocontabil/assistente/rag/BuscaTextualLegislacaoService.java`, `src/main/java/br/com/agrocontabil/service/TratamentoProdutoService.java`, `src/main/java/br/com/agrocontabil/config/avaliacao/`, `src/main/resources/db/changelog/db.changelog-master.yaml`, `docs/ceres-assistente.md`

### S12. Ceres: relatório de uso e feedback com curadoria

**Objetivo:** Entregar o relatório agregado sem texto de pergunta e trocar o scaffolding de Feedback por um fluxo com consentimento e LGPD.

**Por que nessa ordem:** Sem urgência para a banca. O dropTable executa direto na VPS. A #311 antes porque as duas editam os mesmos docs e o relatório precisa de uso real.

**Revisão:** sonnet high. **Tamanho:** G.

**Condições:** Banca realizada. Curador definido para o PR2 da #312.

- **#311** Ceres: relatório de uso por rota, função e desfecho, sem o texto da pergunta
  Comando em profile dev/avaliacao, sem endpoint, que gera Markdown com rota e função, desfecho, latência p50 e p95 e tokens, por janela. Mínimo de 5 interações por célula, sem produtor_id, com teste disso. docs/ceres-assistente.md e docs/lgpd.md.
- **#312** Ceres: feedback 'esta resposta está errada' com consentimento e curadoria humana
  Dois PRs. PR1: changeSet com dropTable de feedback (conferir antes que está vazia na VPS), remover Feedback, TipoFeedback e FeedbackRepository, FeedbackAssistente com CHECKs, unicidade, expira_em de 90 dias, job de retenção com ShedLock, validação do texto livre, ExclusaoDadosService, asserção. PR2: endpoints e curadoria com @Auditado e anti-IDOR 404, depois de nomear o curador.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/model/Feedback.java`, `src/main/java/br/com/agrocontabil/model/TipoFeedback.java`, `src/main/java/br/com/agrocontabil/repository/FeedbackRepository.java`, `src/main/java/br/com/agrocontabil/service/ExclusaoDadosService.java`, `src/main/resources/db/changelog/db.changelog-master.yaml`, `docs/lgpd.md`, `docs/ceres-assistente.md`, `CLAUDE.md`

### S13. API: rotas de guias separadas por papel

**Objetivo:** Separar a rota de guias do produtor da rota da carteira, sem quebrar o Angular.

**Por que nessa ordem:** Baixa prioridade pela própria issue. Fica antes da segurança porque a #345 edita o mesmo SecurityConfig.

**Revisão:** sonnet high. **Tamanho:** M.

**Condições:** Banca realizada.

- **#273** Guias do produtor vivem sob /api/contador/guias: separar a rota do produtor da rota da carteira
  Aditivo. GET e POST /api/guias e PATCH /api/guias/{id}/pagar (produtor do token); GET e POST /api/contador/produtores/{produtorId}/guias pelo ProdutorEmContextoResolver; rotas antigas deprecated no OpenAPI e mantidas. SecurityConfig, CLAUDE.md, testes com dois produtores e produtor fora da carteira; IDOR em rota nova se corrige aqui. Abrir a issue de migração no frontend.
- **#402** feat(vinculos): registrar quem encerrou o vínculo e o motivo
  Follow-up da #353. ChangeSet novo em `carteira_vinculo` (`encerrado_por`, motivo opcional), sem preencher os vínculos antigos: a tela trata nulo como "não registrado". Os dois caminhos de encerramento gravam, o motivo não vai para auditoria nem log, asserção no `LiquibaseChangelogIntegrationTest`.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/controller/GuiaFiscalController.java`, `src/main/java/br/com/agrocontabil/config/SecurityConfig.java`, `src/main/java/br/com/agrocontabil/service/GuiaFiscalService.java`, `CLAUDE.md`

### S14. Segurança consolidada (última sessão)

**Objetivo:** Corrigir de uma vez os defeitos conhecidos e os achados que as sessões anteriores abrirem como issue.

**Por que nessa ordem:** Regra do Murillo: segurança no fim, para juntar os achados. Ordem interna do menor ao maior impacto de deploy. A #313 mexe no que vai ao modelo e por isso só depois da rodada. A #263 fecha porque muda a subida.

**Revisão:** opus high. **Tamanho:** G.

**Condições:** Sessões 6 a 13 concluídas ou com o restante bloqueado por fora. Decisão sobre homolog para a #263. Acesso à VPS para o compose.

- **#289** Reabertura recusada devolve o id interno da apuração no message de topo
  Tirar o UUID do getMessage de ApuracaoNaoReabrivelException e ReaberturaForaPrazoException, ou usar ComMensagemCliente. Varredura só das DomainException 4xx com id que o cliente não enviou. Teste no CarteiraApuracaoIntegrationTest: 409 e 422 sem UUID.
- **#252** Política de senha aceita sequência numérica (12345678901)
  Regra de sequência crescente e decrescente de dígitos e letras e de padrão de teclado em PoliticaSenha; lista de comuns ampliada em memória; testes com 01234567890, 98765432109, abcdefghijk, qwertyuiopas; conferir senhas fixas de seeds e testes.
- **#345** Matcher da CSP (/api/**) lê o caminho analisado em dois passos e quebra em commit fora da thread do container
  Trocar o matcher da CSP por um sem estado que compara o caminho decodificado com /api/, no estilo de RotasAutenticacao.caminho, cobrindo /%61pi/. Teste com a coreografia do AssistenteStreamingIntegrationTest sem o comentário de abertura; manter os testes da #150.
- **#367** CSV cego das externas do benchmark não protege valor com tab ou CR (injeção de fórmula)
  Conferir antes se o #392 já alterou `_celula_csv`. Incluir tab e CR nos prefixos protegidos em externas.py, teste em tests/test_externas.py e paridade com o CsvCego Java.
- **#313** CNPJ alfanumérico não é mascarado no log nem no DataSanitizer da Ceres
  CNPJ alfanumérico em MascaraDadosPessoais e DataSanitizer, com DV módulo 11 e a fronteira do PR #310; aceitar minúsculas; docs/lgpd.md; testes de CNPJ válido, hash e base64 de 14 caracteres intactos, CPF e CNPJ numérico inalterados. Aceitar CNPJ alfanumérico como dado do sistema é decisão à parte.
- **#399** CPF/CNPJ do destinatário em claro na busca_normalizada das notas
  Aberta pela #271. Decidir se o documento sai da coluna para igualdade por HMAC em coluna própria, e cobrir `busca_normalizada` na anonimização da LGPD (`ExclusaoDadosService`) se o titular excluído puder ser destinatário. Liga com o débito de dados das notas em claro.
- **#263** SEFAZ mock ligado por padrão em prod
  Última da sessão. Tirar o default true do base e pôr true em application-dev.yml e application-test.yml; prod com mock não sobe (teste no estilo do AntivirusConfigTest). A VPS roda em homolog com o mock herdado do default (deploy/compose.yaml, sem NFE_SEFAZ_MOCK_ENABLED): declarar a variável no compose no mesmo PR, com o tratamento de homolog que o Murillo decidir, e copiar o compose para a VPS antes do deploy.

**Arquivos que a sessão reserva:** `src/main/java/br/com/agrocontabil/config/SecurityConfig.java`, `src/main/java/br/com/agrocontabil/common/MascaraDadosPessoais.java`, `src/main/java/br/com/agrocontabil/assistente/DataSanitizer.java`, `src/main/java/br/com/agrocontabil/service/PoliticaSenha.java`, `src/main/java/br/com/agrocontabil/config/SefazProperties.java`, `src/main/resources/application.yml`, `src/main/resources/application-dev.yml`, `src/main/resources/application-test.yml`, `deploy/compose.yaml`, `docs/avaliacao/benchmark/ceres_benchmark/externas.py`, `docs/lgpd.md`

## Fora do fluxo autônomo

- **#284** Fila de decisão do contador, sem código. Seguem abertas 6, 7/B1, 8, A, B2, C, D, E e 16. A pergunta 15 deixou de ser do contador com o PR #392 (revisão pela equipe e pelo orientador): atualizar o texto.
- **#275** Depende de avaliador de fora da equipe e da linha de base com modelo real. Só vira trabalho no repositório quando chegar o retorno; a nota de clareza deve vir depois da #357.
- **#198** Fechamento refutado na auditoria: nenhum critério cumprido e a #270 exclui GPU. Fica aberta e estacionada. A sessão 5 mede o qwen3:8b na VPS, e o Murillo decide depois se a GPU local ainda interessa.
- **#202** Condicional e estacionada pelo autor. Só reavaliar depois da #270, com erros residuais medidos, e exige GPU externa.
- **#261** Congelada por decisão do Murillo em 02/11. Se descongelar, separar a Parte A (histórico no request) da Parte B (memória, LGPD, migration).
- **#321** O Playwright mora no repositório do frontend e depende de login na tela e ambiente estável. Sem trabalho de backend agora; reavaliar depois da banca.
- **#322** Guarda-chuva de triagem do QA manual, sem código próprio. Cada achado do formulário vira issue; fecha quando os filhos fecharem.

## O que já está implementado, issue a issue

| Issue | Título | Tipo | Situação no código | Tam. | Risco | Toca a Ceres | Banca | Onde |
|---|---|---|---|---|---|---|---|---|
| #396 | Ceres: tratamento de produto não informa a exclusão que está na descrição do item | bug | não iniciada | P | medio | sim | antes | S3 |
| #399 | [Segurança] CPF/CNPJ do destinatário em claro na busca_normalizada das notas | seguranca | não iniciada | M | medio | não | depois | S14 |
| #402 | feat(vinculos): registrar quem encerrou o vínculo e o motivo | feature | não iniciada | P | baixo | não | depois | S13 |
| #151 | [Sprint 9] Testes unitários no backend (JUnit 5 + Mockito) — meta 80% de cobertura | teste | parcial | M | baixo | não | depois | S4 |
| #152 | [Sprint 9] Testes de integração nos fluxos críticos | teste | parcial | M | baixo | não | tanto faz | S4 |
| #153 | [Sprint 9] Performance: garantir resposta < 2s em p95 | performance | parcial | G | medio | não | depois | S5 |
| #154 | [Sprint 10] Documentação da API com SpringDoc OpenAPI (Swagger) | docs | parcial | M | baixo | não | tanto faz | S4 |
| #198 | [Sprint 9] Ceres — Linha de base com o Ollama na GPU e com o qwen3:8b | experimento-ia | não iniciada | M | baixo | não | congelada | fora |
| #200 | Ceres: busca de lei antes do gerador (reranker, busca híbrida RRF, embedding ajustado) | experimento-ia | não iniciada | G | alto | sim | depois | S11 |
| #202 | Ceres: LoRA no qwen3:4b para função e formato (condicional) | experimento-ia | não iniciada | G | alto | sim | congelada | fora |
| #252 | Política de senha aceita sequência numérica (12345678901) | seguranca | não iniciada | P | baixo | não | depois | S14 |
| #261 | Ceres: contexto da conversa (sessão) e memória do usuário | feature | não iniciada | G | alto | sim | congelada | fora |
| #263 | SEFAZ mock ligado por padrão em prod | seguranca | não iniciada | P | medio | não | depois | S14 |
| #265 | [Ceres 2.0 · 3/8] Classificacao de intencao por regras + embeddings (LLM so no desempate) | experimento-ia | parcial | P | baixo | sim | tanto faz | S5 |
| #267 | [Ceres 2.0 · 5/8] Glossario do produtor e explicacao opcional do modelo, conferida em codigo | experimento-ia | não iniciada | G | medio | sim | antes | S3 |
| #268 | [Ceres 2.0 · 6/8] Entrega em duas etapas no SSE: o numero primeiro, a explicacao depois | feature | não iniciada | M | medio | sim | antes | S10 |
| #269 | [Ceres 2.0 · 7/8] Rota de legislacao em linguagem simples, sem recusa falsa (#258) nem ruido no MISTO (#260) | experimento-ia | parcial | M | medio | sim | antes | S3 |
| #270 | [Ceres 2.0 · 8/8] Inferência local no ARM e metas de tempo | experimento-ia | parcial | M | medio | sim | antes | S5 |
| #271 | Busca de notas não acha por produto nem por CPF/CNPJ do destinatário (#174) | feature | fechada pelo PR #400 | M | medio | não | antes | S1 |
| #273 | Guias do produtor vivem sob /api/contador/guias: separar a rota do produtor da rota da carteira | chore | não iniciada | M | baixo | não | depois | S13 |
| #275 | [Ceres 2.0 · externo] Avaliação externa: revisão do gabarito e nota de clareza | decisao-externa | não é código | P | baixo | não | antes | fora |
| #281 | Modelar a situação do produtor no IBS/CBS: não contribuinte (art. 164), optante (art. 165) e crédito presumido (art. 168) | feature | não iniciada | G | alto | sim | depois | S9 |
| #283 | Base de dados da Ceres: tabela oficial cClassTrib + anexos por NCM, regulamentos e nomenclatura NCM | feature | parcial | G | medio | sim | depois | S11 |
| #284 | [Contador] Perguntas que precisam de validação de um contador | decisao-externa | não é código | P | baixo | não | antes | fora |
| #289 | Reabertura recusada devolve o id interno da apuração no message de topo | seguranca | não iniciada | P | baixo | não | depois | S14 |
| #299 | Aplicar as respostas do contador (#284, perguntas 1 a 6) na tabela de tratamento por NCM: leite cru, arroz em casca e cooperativa | feature | parcial | M | medio | sim | antes | S9 |
| #300 | Débitos da NF-e e do DANFE após a NT 2026.010: QR Code, Imposto Seletivo, fone e bairro do destinatário, CBS de 2027 | chore | não iniciada | M | baixo | não | depois | S7 |
| #311 | Ceres: relatório de uso por rota, função e desfecho, sem o texto da pergunta | feature | não iniciada | M | baixo | não | depois | S12 |
| #312 | Ceres: feedback 'esta resposta está errada' com consentimento e curadoria humana | feature | não iniciada | G | medio | não | depois | S12 |
| #313 | CNPJ alfanumérico não é mascarado no log nem no DataSanitizer da Ceres | seguranca | não iniciada | M | medio | sim | depois | S14 |
| #317 | [Demonstração] Remover o seed da banca depois da apresentação (#315) | chore | não iniciada | M | medio | não | congelada | S6 |
| #321 | [Sprint 9] Testes E2E: UC001–UC005 (Playwright) | teste | não iniciada | G | medio | não | depois | fora |
| #322 | [Sprint 9] Correção de bugs encontrados no QA | decisao-externa | não é código | P | baixo | não | depois | fora |
| #329 | [Pos-banca] Notas a conferir: sinal NCM_NAO_BATE_DESCRICAO (e atalho no frontend) | feature | não iniciada | M | baixo | sim | depois | S8 |
| #334 | Grupo de ICMS por item na NF-e com diferimento (CST 51) conforme a UF | feature | parcial | G | medio | não | depois | S7 |
| #341 | feat: listagem de notas de toda a carteira do contador (GET /api/contador/notas) | feature | fechada pelo PR #401 | G | medio | não | antes | S1 |
| #342 | Ceres: informar em fontesDados se a consulta é dado do produtor ou tabela pública | feature | fechada pelo PR #397 | P | baixo | sim | antes | S2 |
| #345 | Matcher da CSP (/api/**) lê o caminho analisado em dois passos e quebra em commit fora da thread do container | seguranca | não iniciada | P | medio | não | depois | S14 |
| #353 | feat(vinculos): o produtor ver os vínculos que já encerrou (histórico em GET /api/vinculos) | feature | fechada pelo PR #403 | M | baixo | não | tanto faz | S1 |
| #356 | Crédito estimado nos insumos do Anexo IX sem considerar o diferimento (art. 138, § 2º) | bug | não iniciada | M | alto | sim | depois | S9 |
| #357 | Textos da Ceres sem acento: "e" e "é" se confundem e mudam o sentido | feature | fechada pelos PRs #398, #404 e #405 | M | medio | sim | antes | S2 |
| #362 | NF-e: unidade tributável igual à comercial (café em saca sai com uTrib SC em vez de kg) | bug | não iniciada | M | medio | sim | depois | S7 |
| #363 | Ceres: debito pendente mostra tambem as guias a vencer, em linha secundaria | feature | parcial | M | medio | sim | depois | S8 |
| #364 | Sinais de nota a conferir: termos de servico agricola e cruzamento com NCM e unidade | feature | fechada pelo PR #406 | M | baixo | sim | tanto faz | S2 |
| #367 | CSV cego das externas do benchmark não protege valor com tab ou CR (injeção de fórmula) | seguranca | não iniciada | P | baixo | não | tanto faz | S14 |
| #369 | Ceres: busca de produto por nome ignora negacao (exceto, nao) nas descricoes dos anexos | bug | fechada pelo PR #394 | M | medio | sim | antes | S2 |
| #387 | benchmark: planilha de revisão pela equipe e pelo orientador (sem contador) e leitura de volta | docs | fechada pelo PR #392 | M | baixo | não | antes | fora |
| #389 | Regra fiscal nova não alcança nota já em XML_GERADO nem o rascunho antes da validação | bug | fechada pelo PR #395 | M | medio | não | tanto faz | S1 |

### O que falta nas parciais

- **#151:** Subir o minimum do JaCoCo de 0.0 para 0.80 em LINE depois de medir a cobertura real (rodar ./mvnw verify e ler target/site/jacoco). Decidir se o 80% vale para o BUNDLE inteiro ou com exclusões (config, DTOs, dataloader, demonstracao, avaliacao); sem isso o gate pode nascer vermelho. Cobrir os módulos que ficarem abaixo do alvo. Reescrever o item AiService: não existe, o equivalente é o módulo assistente com ModeloLinguagem mockado, que já tem testes. Item do Maven Enforcer é desnecessário: o jacoco:check já faz o papel.
- **#152:** O grosso dos @SpringBootTest roda em H2; só 4 classes usam PostgreSQL via Testcontainers, então não vale a frase 'banco real em todos os fluxos'. Não há um teste único ponta a ponta 'criar nota, gerar XML, enviar à SEFAZ mock, AUTORIZADA' em PostgreSQL; os pedaços existem separados. Confirmar que a CI executa os testes Testcontainers (o ubuntu-latest do GitHub tem Docker; não vi nada em ci.yml que os desabilite). Decidir se migra 3 a 4 fluxos críticos (emissão, importação com recálculo, cancelamento, reabertura) para uma base abstrata em PostgreSQL ou fecha a issue como atendida na prática.
- **#153:** Benchmark de carga (k6 ou JMeter) com script versionado e resultado de p95. Expor métricas HTTP (/actuator/metrics, ou Prometheus) com cautela para identificar endpoints lentos. @Cacheable na apuração mensal FECHADA (hoje inexistente); avaliar se vale a complexidade, dado que a leitura já é uma consulta indexada. Prova de ausência de N+1 por teste (contagem de statements Hibernate) em vez de show-sql. Revisar o item 'Pageable em todos os listagens': conferir os endpoints de lista restantes.
- **#154:** Cerca de 6 controllers sem @Operation e alguns mapeamentos sem anotação (110 @Operation para 127 mapeamentos); levantar a lista exata. DTOs sem @Schema: 49 arquivos com @Schema contra 64 itens em dto/ mais records em outros pacotes. Configurar GroupedOpenApi (notas, apuração, chat, relatórios, contador, auth). Teste que falhe quando um endpoint ficar sem @Operation (varredura via RequestMappingHandlerMapping), para travar o 100%. Decidir se Swagger fica fechado em prod (hoje desligado de propósito) e como a banca o acessa (homolog/dev).
- **#265:** Medicao real: acerto de rota >= 95% em 3 repeticoes e zero erros nas regras (a linha de base Ceres 2.0 esta marcada como pendente de rodar com modelo real). p95 < 300 ms com Ollama ocioso e ocupado no ambiente publicado (nao ha medicao registrada). Proporcao de desempate por LLM no relatorio real. Fechar #253 e #199, e confirmar a parte de classificacao da #260. Plano B BERTimbau nao feito (condicional).
- **#269:** Diretriz de linguagem simples no PromptsCeres.sistemaLegislacao (frases curtas, siglas explicadas, exemplo do campo sem numeros novos). Glossario deterministico ao fim da resposta ('O que isso quer dizer: ...'), dependente do glossario da #267. Teste de nenhuma sigla do glossario sem explicacao nas respostas de lei. Confirmar CITACAO_INVALIDA com marcas validas tambem cai em SO_TRECHOS (conferir o caminho). Medir clareza >= 4/5 sem queda de citacao e taxa de descarte antes/depois (medicao real pendente tambem de #307/#309). Fechar #258 e #260.
- **#270:** Item 1: medir Q4_0 contra Q4_K_M (só medição na VPS, o código e a doc já existem). Item 4: medir o Qwen3 30B-A3B e registrar a decisão; ~18 GB em Q4 agora cabe nos 47 GiB, com folga mesmo com Postgres, ClamAV e BGE-M3. Item 5: tabela antes/depois com p50/p95 por rota, acerto e RAM da VM (harness #264 + gabarito #158, 3 repetições). Decidir num_ctx 6144 e AI_TETO_LEGISLACAO=512 só depois de medir prompt_eval_count e contagem de RESPOSTA_CORTADA. Conferir se o prompt_eval_count com prefixo em cache afeta a cota #166 e a auditoria. Atualizar o comando do harness (--avaliacao.hardware='VPS ARM 4 nucleos') e as metas/premissas da issue para 8 OCPU e 47 GiB; a restrição 'Always Free' e 'memória da VM abaixo de 85%' precisa ser reavaliada. Registrar a decisão em docs/ceres-assistente.md.
- **#283:** Parte 2: ingerir Decreto 12.955/2026 e Resolucao CGIBS 6/2026 (ou trechos via colunas Regulamento CBS/IBS da planilha) na busca de lei. Parte 3: conferir/reingerir LC 214 compilada (exige maquina no Brasil, Planalto bloqueia exterior). Parte 4: nomenclatura NCM oficial (Siscomex) na rota de dados para resolver vendas por produto (#248). Pendencias do contador (#284) sobre leite cru e venda a cooperativa optante.
- **#299:** Venda do associado à cooperativa optante (pergunta 6): marca de cooperativa optante no destinatário e de associado no produtor, cClassTrib 200020, possivelmente campo novo. Decidir cooperativa na demonstração (6b): trocar o comprador (PDF recomenda trading/cerealista) ou modelar a marca. Arroz 1006.10.91 parboilizado aguardando pergunta D. Registrar em cada linha a resposta do contador e a data (a issue pede; parte feita nos changeSets). Teste de coerência com a função da Ceres #283 para cooperativa. Confirmar B2 com o contador: o comentário de 2026-11-03 diz que leite cru e arroz do optante ainda esperam B2, mas o PDF do segundo escritório já os respondeu e o código seguiu o PDF.
- **#334:** Tabela de referência de diferimento por UF + NCM + tipo de destinatário versionada por changeSet (hoje o padrão é 51 fixo no código TributacaoIcms, só confirmado para soja em MT). pDif/vICMSDif conforme o layout, se exigidos (conferir). CSOSN para emitente do Simples/MEI (hoje barra com 422). CST 00/90 com cálculo de ICMS (o sistema recusa os que exigem cálculo). Testes por UF de exemplo (PR e MT), por destinatário e validação no XSD. Pergunta E do #284 (CST por produto e UF, leite UHT ao supermercado) para fixar a tabela.
- **#363:** Template de guiasEmAtraso com tres blocos: em atraso, vence hoje, linha secundaria 'Y guias a vencer no prazo (ate DD/MM)' com total. Dados do backend para vence-hoje e a-vencer (DTO GuiasEmAtraso hoje so tem emAtraso e proximaGuia). Manter frase sem negativa absoluta (VerificadorNegativaDados) quando sem atraso. Gabarito em versao nova e testes: so atraso, so a vencer, ambos, nenhum. Garantir que a conta de 'quanto pagar este mes' (#257) nao muda.

## Decisões do Murillo (2026-11-06)

Aceitou todas as recomendações:

1. **Datas:** congelamento em 26/11 e banca em 03/12, com corte da S3 em 19/11 e fim da S5 em 25/11.
2. **Explicação gerada pelo modelo:** fora da Ceres medida. Antes da banca só o determinístico (glossário e linha "O que isso quer dizer"); a #267 completa e a #268 ficam na S10. O prompt da #269 só mergeia depois de o Murillo ver respostas do modelo real.
3. **Glossário:** o agente redige no PR e a equipe ou o orientador revisa em até 3 dias; é exceção ao merge sem OK. Sigla sem definição aprovada fica de fora.
4. **VPS:** janela noturna, sem merges, k6 em stack isolada e só medição. `qwen3:4b-instruct` segue padrão até a banca, salvo OK dele antes de fixar o SHA.
5. **PR #392:** mergeado em 2026-11-06, antes do início da S1 e da S2.
6. **Contador:** cobrar C, E, 6, D, B1, 8 e A da #284 até 13/11. Sem resposta, as partes bloqueadas de #356, #334, #299 e #281 não começam, e a limitação segue declarada.
7. **Cobertura:** o piso nasce no valor medido e sobe até 80% de LINE, com exclusões de config, DTOs e seeds. Um teste em PostgreSQL de emissão e cancelamento, mais a reabertura, fecha a #152.
8. **Frontend:** o Luiz é o responsável e adota a #341 antes de 26/11 (frontend#164). A auditoria tinha lido um checkout local defasado (27/10) e dado o 403 como aberto; na `main` do frontend ele já saiu (frontend#136). Com isso corrigido, o Murillo manteve a #341 antes da banca.
9. **Issues:** a auditoria foi comentada nas parciais #265, #269, #270, #299 e #334, além de uma atualização na #341 e na #284.

Ficam para depois da banca:

- **#263:** homolog recusa o mock ou só exige declará-lo? Recomendação: prod recusa sempre; homolog exige `NFE_SEFAZ_MOCK_ENABLED` explícito no compose.
- **#312:** quem faz a curadoria dos feedbacks? Recomendação: semanal pela equipe, retenção de 90 dias.
- **#283, parte 3:** reingestão da LC 214 de máquina no Brasil. Recomendação: fica com o Murillo.

## Riscos

- Todo merge com CI verde reimplanta o backend na VPS, sem filtro de caminho em docker-publish.yml. Por isso nenhum merge durante a sessão 5 e só hotfix entre o congelamento e a banca. ChangeSets das sessões 1 rodam sobre o banco da demonstração: conferir o roteiro e o reset da #391 em cada PR.
- O backfill da #271 roda em SmartInitializingSingleton, antes do Tomcat. Sem lotes e medição de tempo, ele alonga o boot e pode acionar o rollback do deploy por healthcheck.
- A #389 passa a recusar XML_GERADO antigo. Nas notas de ensaio da VPS isso aparece como 409: rodar o reset da #391 ou regerar, e avisar o frontend da mensagem de ação.
- Contaminação do benchmark: as inéditas saíram do backend com o PR #392, mas `perguntas-benchmark.json` segue no repositório. Os agentes das sessões 2, 3 e 5 excluem esse caminho das buscas e não ajustam a Ceres olhando respostas às inéditas.
- A coluna oficial precisa de SHA que contenha #369, #357, #364 e a sessão 3. Registrar isso em sessaoParalela.tambemExige é edição do benchmark, na decisão do Murillo. A #357 muda texto comparado por gabarito e avaliador; a comparação tem de seguir normalizada.
- A #357 quebra testes de string exata e pode afetar verificadores que leem o texto do próprio backend, como o VerificadorNegativaDados. Um erro ali troca resposta boa por descarte sem falhar a compilação; o review precisa olhar isso.
- A #341 só aparece na demonstração quando o frontend adotar a rota (frontend#164, com o Luiz). O backend precisa mergear com folga antes de 26/11, e a sessão comenta o contrato final na issue.
- Paralelismo: só 1 com 2 e 3 com 4. O arquivo crítico tem dono único em cada par. A sobreposição de 1 e 2 em FrasesSinaisNota e SinaisNotaConferirService é resolvida por ordem: templates da #357 e #364 só depois da #389.
- A sessão 3 depende de revisão humana, e isso quebra o merge automático. Sem revisão até o corte, o trabalho vai para o pós-banca, sem segurar a medição.
- A #362 pode não ser exigência em operação interna, e a homologação real (#128) ainda não rodou. Vale a leitura do MOC; se não obrigar, o escopo cai.
- Achados de segurança viram issue nova para a sessão 14, com IDOR em rota nova como única exceção. Já previstos: documento do destinatário em claro na busca (#271) e exposição de métricas (#153). A cada merge, o orquestrador confere se o resumo do PR lista os achados.
- Docs e issues divergem do ambiente: 4 OCPU e 24 GB, Always Free, nomes de VPS. O orquestrador confere o estado da VPS antes de tudo e corrige os textos na sessão 5.

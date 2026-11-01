# Ceres — assistente fiscal (infraestrutura)

A Ceres responde perguntas do produtor combinando três camadas: **Tool Calling** (funções Java que
consultam os dados reais do produtor), **RAG** (trechos oficiais da EC 132/2023 e da LC 214/2025,
com citação obrigatória) e **orquestração** (roteador que decide a camada pela intenção). Esta
página cobre a base de infraestrutura (issue #141); o desenho completo está em
`.specs/features/sprint-6-ceres/spec.md`.

## Stack

| Peça | Escolha | Por quê |
|---|---|---|
| Integração | Spring AI **1.1.8** | Linha compatível com o Boot 3.5.14 (a 2.x exige Boot 4) |
| Modelo de chat | `qwen3:4b-instruct` no **Ollama** (local) | Orientação do prof. Luan: arquitetura completa com modelo simples, escalar depois. Variante *instruct* (Qwen3-4B-Instruct-2507): a tag `qwen3:4b` virou a variante *thinking*, que ignora `think=false` e levava 80–120 s só para classificar uma pergunta em CPU |
| Provedor por API | qualquer API compatível com a da OpenAI (OpenAI, Maritaca) | Só configuração: `AI_CHAT_PROVIDER=openai` + `AI_API_KEY` |
| Embedding | `bge-m3` no Ollama, 1024 dimensões | **Sempre local.** Trocar o modelo obriga a reindexar a legislação |
| Vector store | pgvector no mesmo PostgreSQL 16 | Imagem `pgvector/pgvector:0.8.6-pg16`; schema pelo Liquibase (#156) |

## Subindo

```bash
# Só Postgres + Ollama (o backend roda pelo mvnw). Na primeira vez o ollama-init baixa ~4 GB.
docker compose --profile ia up -d
./mvnw spring-boot:run

# Tudo em containers, inclusive o backend
docker compose --profile full up -d --build
```

O Ollama **não** faz parte do compose padrão de dev: quem trabalha em NF-e não precisa baixar os
modelos. Sem Ollama o backend sobe normalmente; a Ceres responde **503**
`ASSISTENTE_INDISPONIVEL` e o `/actuator/health` fica **`DEGRADED`** (HTTP 200).

> **Volume antigo do Postgres:** a imagem mudou de `postgres:16-alpine` (musl) para a do pgvector
> (Debian/glibc), e a collation muda junto. Num volume de dev já existente, rode
> `docker compose down -v` (o `DataLoader` recria o seed) ou `REINDEX DATABASE agrocontabil;`.

### Trocar de modelo ou de provedor

| Quero | Variáveis |
|---|---|
| Modelo local maior | `AI_CHAT_MODEL=qwen3:8b` (o `ollama-init` baixa na próxima subida) |
| API da OpenAI | `AI_CHAT_PROVIDER=openai`, `AI_API_KEY=...`, `AI_CHAT_MODEL=gpt-4o-mini` |
| Outra API compatível (ex.: Maritaca) | `AI_CHAT_PROVIDER=openai`, `AI_API_BASE_URL`, `AI_API_COMPLETIONS_PATH` (a Maritaca usa `/chat/completions`, sem `/v1`; confira na documentação do provedor), `AI_API_KEY`, `AI_CHAT_MODEL` |

Com `AI_CHAT_PROVIDER=openai` e sem `AI_API_KEY`, a aplicação **não sobe** (falha na subida, não
na primeira pergunta). A chave vem só de variável de ambiente.

## Garantias desta camada

- **Falha do modelo é 503, nunca 500.** Todo acesso ao modelo passa por
  `assistente/llm/ModeloLinguagem`, que traduz qualquer falha do provedor em
  `AssistenteIndisponivelException`. O cliente recebe mensagem genérica; o log registra só o tipo
  da falha (a mensagem de erro de um provedor por API pode ecoar a requisição).
- **Timeout e retry:** `AI_CONNECT_TIMEOUT` (5 s), `AI_READ_TIMEOUT` (120 s — modelo local em CPU é
  lento), `AI_RETRY_MAX_ATTEMPTS` (2, sem retry em erro 4xx).
- **Prompt e resposta nunca vão para log** (`spring.ai.chat.observations.log-*: false`).
- **Ollama só em loopback** (`127.0.0.1:11434`): ele não tem autenticação.
- **Health com cliente próprio** (timeout de 2 s, cache de 10 s): o `/actuator/health` é público e
  não pode pendurar nem virar amplificador de chamadas ao Ollama.

## Testes

O profile `test` desliga os modelos reais (`spring.ai.model.chat/embedding: none`) e o
`support/ModelosSimuladosConfig` registra `ChatModelSimulado` (programável: texto fixo, sequência
de respostas, falha) e `EmbeddingModelSimulado` (determinístico, 1024 dimensões). A CI não depende
do Ollama nem de chave de API.

## Rota de dados — Tool Calling (issues #142 e #143)

`POST /api/assistente/chat` com `{"pergunta": "..."}` (1 a 1000 caracteres, só produtor).

| Função | Responde | Reaproveita |
|---|---|---|
| `totalComprasPorInsumo(descricaoOuNcm, dataInicio, dataFim)` | "quanto comprei de fertilizante no semestre?" | recorte do crédito: entradas `IMPORTADA` + `AUTORIZADA` |
| `consultarApuracao(mes)` | "quanto de IBS eu devo em julho?" | `ApuracaoService` |
| `consultarCreditos(dataInicio, dataFim)` | "quanto tenho de crédito?" | `CreditoService` |
| `listarNotas(tipo, situacao, dataInicio, dataFim, limite)` | "minhas últimas vendas" (máx. 10), "quanto somam?" | consulta fixa top-N + `valorTotal` do filtro inteiro (sem `situacao`, só as autorizadas — #169) |
| `proximosVencimentos(dias)` | "o que vence nos próximos 30 dias?", "quando vence a próxima guia?" (máx. 90 dias) | guias não pagas **a partir de hoje** + `valorTotal` da janela inteira (#169). Não traz atraso |
| `guiasEmAtraso()` | "tenho algum débito pendente?", "tenho guia atrasada?" (#259) | guias não pagas com vencimento **antes de hoje**, de qualquer mês, pela data e não pelo status gravado (`PENDENTE` vencida conta); total do backend; sem atraso, a próxima guia a vencer e a frase com o recorte |
| `vencimentosDoMes(mes)` | "quanto eu tenho que pagar este mês?", "quais impostos vencem em novembro?" (#257) | mês corrente (padrão): atraso de qualquer mês + não pagas de hoje ao fim do mês, com os dois subtotais e a soma do backend; mês futuro: as do mês; mês passado: as que ficaram sem pagar. Até 12 meses do atual. Obrigações do catálogo, pedidos do escritório e IBS/CBS apurado ficam fora (decisões da #274) |
| `listarAlertas()` | "tenho algo vencendo?" (máx. 10) | `AlertaTributarioService.listarAtivos`: guia, fechamento do mês e prazo de cancelamento a menos de 5 dias, motivo conferido na consulta (#140) |
| `consultarRetencoes(dataInicio, dataFim)` | "quanto foi retido de Funrural nas minhas vendas?" | `RetencaoService.resumir` (RN009, #145): vendas autorizadas por tipo de comprador; Funrural de 1,5% retido na venda a empresa/cooperativa, sem retenção nas vendas a pessoa física ou no varejo (o produtor recolhe); venda ao exterior imune (Constituição, art. 149, § 2º, I); IBS/CBS separado pelo split payment |

**Retenção (RN009, #145): as duas regras, sempre.** Decisão do Murillo de 2026-10-14: quando o
assunto é retenção, a Ceres fala do Funrural retido pelo comprador **e** do split payment, com
citação. Em código, não no prompt: o `FundamentoRetencao` acrescenta ao fim da resposta um
parágrafo fixo com as duas regras e as citações da base (Lei 8.212/1991, art. 25, I e II, e art.
30, IV; Lei 9.528/1997, art. 6º; LC 214/2025, arts. 31 e 32), em qualquer rota menos fora de
escopo, quando a função foi usada ou a pergunta fala de retenção, Funrural, SENAR ou split payment.
Um "não encontrei fundamento legal" da rota de lei sai, para não contradizer o parágrafo. A função
devolve também avisos fixos do que o cálculo não sabe: a opção pela folha de salários (art. 25,
§ 13) e a produção vendida para plantio ou reprodução (§ 12).

Garantias, todas em código:

- **Isolamento (RN007):** nenhuma função tem parâmetro de produtor. O produtor é resolvido do
  usuário autenticado **antes** de chamar o modelo e chega às funções pelo `ToolContext`
  (`ContextoAssistente`), que o modelo não vê. Sem contexto, a função falha fechado. Testado com
  prompt injection pedindo dados de outro produtor.
- **Nenhum número inventado:** o laço de funções é do backend e guarda os resultados; o
  `ValidadorNumeros` descarta a resposta que tiver número ou data que não veio das funções, da
  pergunta ou do prompt de sistema. **Soma é do backend:** as funções de lista devolvem
  `valorTotal`, calculado por consulta agregada sobre o filtro inteiro, e o modelo só o repete;
  somar a lista por conta própria continua sendo descartado (#169).
- **Sem text-to-SQL:** consultas fixas e parametrizadas; texto digitado vira `LIKE` escapado.
- **Minimização:** as funções devolvem totais, datas, contagens e listas curtas — nenhum nome,
  CPF/CNPJ, endereço, chave de acesso ou observação.
- **Custo limitado:** no máximo `AI_MAX_RODADAS_FERRAMENTAS` (3) rodadas de funções por pergunta;
  concorrência e tempos na seção de orquestração (#157).
- **Erro de função:** o modelo recebe só a orientação de parâmetro inválido (escrita por nós) ou
  uma mensagem genérica — nunca a mensagem do banco.

## Sanitização e auditoria (issue #144)

Tudo que vai ao modelo passa pela mesma regra, seja o provedor local ou por API (o provedor é
trocável por configuração).

- **Minimização por construção:** os DTOs das funções não têm campo de nome, CPF/CNPJ, endereço,
  e-mail, telefone, chave de acesso nem observação livre (`DefinicaoFerramentasTest` trava isso).
  Nome e endereço não se detectam com confiança por regex; por isso simplesmente não entram.
- **Máscara (`DataSanitizer`):** CPF, CNPJ, chave de acesso, e-mail, telefone e CEP viram
  marcadores (`[CPF]`...) na **pergunta** e em **todo resultado de função**
  (`ResultadoSanitizadoConverter`, declarado em cada `@Tool`). Valores, datas e NCM ficam intactos.
- **Auditoria (`assistente_interacao`, changeSet 0025):** uma linha por pergunta — produtor, rota,
  desfecho (`RESPONDIDA`, `NUMERO_SEM_FONTE`, `SEM_CONCLUSAO`, `FUNCAO_NAO_PERMITIDA`,
  `SEM_RESPOSTA`, `SEM_FUNDAMENTO_LEGAL`, `CITACAO_INVALIDA`, `FORA_DE_ESCOPO`,
  `MODELO_INDISPONIVEL`, `TEMPO_ESGOTADO`, `CANCELADA`, `ERRO`), funções com parâmetros mascarados, quantas funções o modelo
  pediu, modelo, latência, tokens e o **tamanho** da pergunta. **Sem o texto da pergunta nem da
  resposta** — a entidade nem tem onde guardá-los. Se a auditoria não gravar, a resposta não é
  entregue (503). Alimenta também a avaliação do TCC (#158).
- **Partes do MISTO (changeSet 0030):** `desfecho_dados` e `desfecho_legislacao` guardam o
  desfecho de cada parte, e `pergunta_lei_reescrita` diz se a de lei recebeu a pergunta legal
  separada pelo classificador. Só o fato: o texto da pergunta, reescrita ou não, nunca é gravado.
  Nulos nas outras rotas e na parte que não terminou. O relatório da avaliação mostra o mesmo ao
  lado do desfecho final ("RESPONDIDA (dados RESPONDIDA, lei CITACAO_INVALIDA, reescrita)").
- **Tamanho:** pergunta de no máximo 1000 caracteres (`@Size`) e corpo de no máximo 16 KB
  (`LimiteCorpoAssistenteFilter`, antes de o Jackson ler — 413). O `rejectedValue` dos erros de
  validação é truncado em 100 caracteres, em toda a API.
- **Prompt injection:** a defesa é estrutural (funções sem parâmetro de produtor, lista fechada de
  funções, números conferidos em código); instrução de sistema fixa e limite de tamanho completam.
  Não há denylist de frases: ela daria falsa sensação de segurança.
- **Logs sem PII:** nenhum log registra a pergunta, a resposta ou valores das funções (teste com
  captura da saída).

## Rota de legislação — RAG com citação obrigatória (issue #156)

Perguntas sobre a lei ("qual a redução de alíquota para insumos?") são respondidas **somente** com
trechos oficiais da EC 132/2023 e da LC 214/2025, e a citação é garantida em código.

### Base (`legislacao_trecho`, changeSet 0026)

- **Um trecho por dispositivo** (artigo, parágrafo, inciso, alínea), nunca por número de
  caracteres — a citação é o dispositivo. Cada trecho leva o texto de quem o contém (o caput antes
  do inciso). Texto tachado (revogado) e anotações do Planalto ficam de fora. Em emenda
  constitucional, a nova redação entre aspas vira "art. 1º (redação do art. 156-A, § 1º)".
- Formato do `PgVectorStore` (id, content, metadata, embedding 1024) + colunas geradas (`hash`
  único, norma, dispositivo, vigência) e `tsvector` em português para o modo fallback (#146).
  A 0027 trocou essa coluna por `conteudo_tsv_sem_acento` (`translate` antes do dicionário): a
  pergunta digitada sem acento casa com a lei acentuada.

### Modo fallback (#146)

Com o modelo fora, a Ceres responde **sem gerar texto**, parte por parte (`OrquestradorService`):

- **Disjuntor** (`DisponibilidadeModelo`): toda chamada ao modelo informa o desfecho. Depois de
  uma falha, as perguntas pulam o modelo por `app.assistente.fallback.pausa-apos-falha` (30 s); a
  primeira depois da pausa tenta de novo. Falha de thread interrompida não abre o disjuntor. O
  health do Ollama não decide: ele mistura chat e embedding.
- **Intenção:** o classificador do modelo; sem ele, palavras-chave (`ModoFallback.classificar`).
- **Legislação:** `BuscaTextualLegislacaoService` — OU dos lexemas da pergunta, cada um citado com
  `quote_literal` (operador digitado é texto), no mínimo dois lexemas casados, mesmo filtro de
  vigência da busca vetorial. Os trechos saem verbatim, com a citação dos metadados.
- **Dados:** texto fixo + `atalhos` (telas). Nenhum número do produtor sem modelo.
- **Auditoria:** desfecho `MODO_FALLBACK`. Banco fora junto com o modelo → 503, como antes.
- **Só legislação pública.** Nunca notas nem dados de produtor (#143): a base não tem dono.
- Requer a extensão `vector` (já presente na imagem `pgvector/pgvector` do compose). Em banco
  gerenciado, `CREATE EXTENSION vector` pode exigir um administrador.

### Ingestão (comando manual, nunca endpoint)

Um banco novo vem com `legislacao_trecho` vazia: a ingestão é passo obrigatório ao montar o
ambiente (roteiro no `README.md`). Com a base vazia a `RotaLegislacao` responde "não encontrei
fundamento legal" sem chamar o modelo, e a `VerificacaoBaseLegislacao` loga um `WARN` na subida
(só com a Ceres ligada e fora do profile `ingestao`; nunca derruba a subida nem o health — #175).

1. Salve o **texto compilado** da norma a partir do Planalto (ex.:
   `https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm` e
   `https://www.planalto.gov.br/ccivil_03/constituicao/emendas/emc/emc132.htm`) e anote a data de
   captura.
2. Com Postgres e Ollama no ar (`docker compose --profile ia up -d`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="--ingestao.arquivo=/caminho/lcp214.htm --ingestao.norma='LC 214/2025' --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm --ingestao.data-captura=2026-09-29 --ingestao.vigencia-inicio=2025-01-16"
```

- **Idempotente:** rodar de novo com o mesmo texto não insere nada (hash por norma + dispositivo +
  texto).
- **Nova redação = nova vigência:** o trecho novo entra com `vigencia-inicio`; a redação anterior do
  mesmo dispositivo tem só o fim fechado na véspera (mesma regra das alíquotas, #85). A pergunta
  recebe a redação vigente na data em que é feita.
- A URL precisa ser `https://www.planalto.gov.br/...`: é o link oficial exibido na citação.
- **Charset:** salve o arquivo como veio (sem converter). O Planalto usa windows-1252 e a LC 214
  nem declara o charset; o `LeitorHtmlLegislacao` detecta pelos bytes (UTF-8 válido → UTF-8; senão
  windows-1252). Lido como UTF-8, "º" e "§" se perdiam e ~1.500 parágrafos da LC 214 sumiam.
- **Norma nova: simule antes** com `--ingestao.simular=true`. O comando mostra cada dispositivo
  fatiado e os maiores trechos, sem gravar nem gerar embedding. Foi assim que apareceram os
  problemas da Lei 8.212/1991 (abaixo).
- **Lei de alteração: só o que é norma própria**, com `--ingestao.somente="art. 6º"` (vários
  separados por `;`). O art. 1º da Lei 9.528/1997 traz entre aspas redações de 1997 da Lei 8.212
  já substituídas; ingerido, ele poria alíquotas antigas do Funrural na base como se fossem vigentes.

**Normas na base (2026-10-14):** LC 214/2025 (5.615 trechos), EC 132/2023 (538), LC 227/2026
(2.211), **Lei 8.212/1991** (Funrural, issue #145) e **Lei 9.528/1997, só o art. 6º** (SENAR, 4
trechos). Comandos da #145:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="--ingestao.arquivo=/caminho/l8212cons.htm --ingestao.norma='Lei 8.212/1991' --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/l8212cons.htm --ingestao.data-captura=2026-10-14 --ingestao.vigencia-inicio=1991-07-25"
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="--ingestao.arquivo=/caminho/l9528.htm --ingestao.norma='Lei 9.528/1997' --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/l9528.htm --ingestao.data-captura=2026-10-14 --ingestao.vigencia-inicio=1997-12-11 --ingestao.somente='art. 6º'"
```

**Grafias dos textos consolidados antigos** (a Lei 8.212 é de 1991), tratadas pelo
`FatiadorDispositivos`:

- revogado tachado **por estilo** (`text-decoration: line-through`), sem `<strike>`: sem isto, o
  "Contribui com 3%" original do art. 25 entrava como vigente;
- artigo acrescido **sem hífen** ("Art. 25A."), que virava uma segunda versão do art. 25;
- assinatura com "em" ("Brasília, **em** 24 de julho de 1991"): sem reconhecê-la, as notas de
  rodapé com redações de uma MP de 1998 entravam como artigos;
- "Vigência encerrada" solto, de MP que caducou, que grudava no dispositivo vigente anterior (o
  art. 30, IV, citado pela Ceres, terminava assim). Só a linha que é apenas a anotação sai.

**A LC 214 na base precisa ser reingerida (achado da #145).** O compilado da LC 214 também tacha
por estilo as redações substituídas pela LC 227/2026. Na ingestão de 2026-09-30 elas entraram como
vigentes: **151 dispositivos têm duas redações abertas**, e a revogada ficou com o rótulo limpo
(`art. 10, § 3º`) enquanto a vigente virou `art. 10, § 3º [2]`. A ingestão não conserta isso
sozinha, de propósito: ela recusa texto alterado com a mesma `vigencia-inicio`, e os rótulos `[2]`
ficariam órfãos e abertos. O reparo é apagar as linhas da norma e reingerir com o fatiador atual:

```sql
DELETE FROM legislacao_trecho WHERE norma = 'LC 214/2025';
```

Depois, o comando de ingestão da LC 214 (texto compilado recapturado), com `--ingestao.simular=true`
antes. Conferência: `SELECT count(*) FROM legislacao_trecho WHERE dispositivo ~ '\[[0-9]\]'` deve
dar zero.

### Resposta

1. Busca os trechos **vigentes hoje** mais próximos da pergunta (top `AI_RAG_TOP_K`, padrão 5) acima
   da similaridade mínima (`AI_RAG_SIMILARIDADE_MINIMA`, padrão 0,55). Sem trecho: "não encontrei
   fundamento legal para isso" **sem chamar o modelo**.
2. O modelo vê os trechos só como `[T1] texto…` — **sem** a referência legal — e responde marcando
   a fonte de cada afirmação.
3. `ValidadorCitacao`: exige ao menos uma marca e que toda marca exista; troca cada marca pela
   referência montada dos metadados ("LC 214/2025, art. 138, § 1º") e devolve `citacoes[]` com o
   link oficial. Marca inventada derruba a resposta.
4. `ValidadorNumeros`: percentuais, prazos e números de artigo da resposta têm de estar nos
   trechos — "redução de 70%" que a lei não diz não passa.

## Orquestração, streaming e isolamento de recursos (issue #157)

### Rotas

O `ClassificadorIntencao` (Fase 1: `LlmClassificadorIntencao`, saída JSON do próprio modelo; a
Fase 2 troca a implementação sem mexer no orquestrador) devolve a intenção e a confiança. O
`OrquestradorService` decide:

| Intenção | Camada |
|---|---|
| `DADOS` | modelo + **só** as funções de dados |
| `LEGISLACAO` | RAG + `ValidadorCitacao` |
| `MISTO` | as duas, geradas e validadas **separadamente** e depois juntadas |
| `FORA_DE_ESCOPO` ou confiança < `AI_CONFIANCA_MINIMA` (0,5) | resposta fixa, sem nova chamada ao modelo |

No `MISTO`, uma resposta única gerada sobre as duas fontes misturaria números das funções com
números da lei, e nenhum validador saberia de onde cada um veio.

**Antes do classificador, um filtro determinístico de prompt injection** (`FiltroInjecao`, #145):
"ignore as instruções", "prompt de sistema", "agora você é um...", "sem restrições", "modo
desenvolvedor" vão direto para `FORA_DE_ESCOPO`, sem chamar o modelo. Na avaliação de 2026-10-14 o
classificador mandou um pedido do prompt de sistema para a rota de dados (sem vazamento, mas sem
recusa). A segurança dos dados não depende do filtro (as funções não têm parâmetro de produtor), e
um teste garante que nenhuma pergunta legítima do gabarito é barrada.

**Prompt do classificador: mudança pequena, medida.** Na #145, um parágrafo de regras a mais no
prompt tirou de rota duas perguntas que acertavam (alertas foi para MISTO, crédito presumido para
fora de escopo), de forma repetível. O que ficou foi um exemplo curto na linha de `DADOS`. Toda
mudança no prompt do classificador pede uma medição do gabarito (`docs/avaliacao/`), com 3
repetições desde a #264: uma rodada só não separa ganho de ruído. Os casos adversários de rota
(`adversario: true`, 20 na versão `2026-10-31.1`) medem a precisão das regras de rota da #265.

**Lei descartada com trechos não vira "não encontrei fundamento" (#258).** O `ValidadorNumeros`
descarta a resposta de lei com número fora dos trechos (correto: o 4B completa perguntas amplas
com anos e prazos de memória), e a marca inventada derruba o texto inteiro. Mas havia artigos
recuperados, e o produtor lia "Não encontrei fundamento legal para isso." Agora, com
`NUMERO_SEM_FONTE`, ou com `CITACAO_INVALIDA` quando ao menos uma marca existe
(`ValidadorCitacao.marcasValidas`), a resposta é `RotaLegislacao.SO_TRECHOS` com as citações dos
trechos marcados certo, sem o texto do modelo. `SEM_FUNDAMENTO` fica só sem trecho recuperado, sem
marca nenhuma (inclusive o `NAO_ENCONTREI`) ou só com marcas inventadas. No MISTO a parte de lei com
citações entra como `SO_TRECHOS`; o desfecho de cada parte segue na auditoria.

**A parte de lei do MISTO não fala dos dados (#170).** Ela vê só trechos de lei, então:

- recebe um prompt próprio (`PromptsCeres.sistemaLegislacaoRegraGeral`): só a regra geral, em
  terceira pessoa;
- o `VerificadorAfirmacaoDados` confere em código: "você", posse de dado ("suas compras", "no seu
  caso") ou ação do usuário ("comprou", "adquiriu") troca o texto por "a regra está nos trechos
  citados" e mantém as citações (desfecho `AFIRMACAO_SOBRE_DADOS`);
- parte de dados sem nada encontrado (ou sem consulta) vira frase fixa do backend pela função
  consultada ("Não encontrei compras desse tipo nas suas notas no período consultado"), nunca
  texto livre do modelo;
- **itens de anexo pelo NCM das compras** (`orquestracao/TrechosDasCompras`): se a parte de dados
  consultou as compras de um insumo, os NCMs comprados (até 3, maior valor) são buscados nos
  anexos da lei (`BuscaTextualLegislacaoService.buscarAnexosPorNcm`: código inteiro, subposição
  ou posição, nunca outro item; o item com o código inteiro vem primeiro) e entram depois dos
  trechos da busca por similaridade. Só o NCM sai dos dados, e só para escolher trechos: o modelo
  da lei recebe apenas texto de lei. A *escolha* dos itens, porém, revela ao provedor do modelo
  as categorias compradas — o mesmo que a parte de dados já envia, nada além (com provedor
  externo, ver #148). Falha aqui é complemento perdido, não erro;
- as partes são juntadas com ligação fixa: "Sobre a regra legal: ..." ou, sem dados, "De forma
  geral, a legislação prevê: ..." — sem o "não tenho a informação" seguido de "sim, as suas
  compras têm direito" do teste do PR #168;
- **a parte de lei recebe só a pergunta legal.** Com a pergunta inteira, a busca de trechos ficava
  diluída pelo pedido de dados e o modelo da regra geral recebia um pedido que o prompt dele
  proíbe atender. No MISTO, o classificador devolve também `perguntaLei` (a parte legal, sem os
  dados), que vai à busca, ao modelo e ao fallback textual da lei; a parte de dados segue com a
  pergunta inteira. É texto do modelo, então `orquestracao/PerguntaLei` só a aceita se tiver até
  300 caracteres, não tiver forma de prompt injection e não trouxer número que o usuário não
  escreveu (o `ValidadorNumeros` aceita os números da pergunta: um "60%" inventado na reescrita
  viraria fonte). Fora disso, vai a pergunta inteira, como antes;
- **"Sim," inicial com a pergunta separada.** A pergunta legal costuma sair de sim ou não
  ("fertilizante tem redução?"), e o modelo responde "Sim, ...". Respondendo à regra geral, essa
  palavra não fala do caso do produtor: com a pergunta separada, a `RotaLegislacao` tira o "Sim,"
  ou "Não," do começo antes do `VerificadorAfirmacaoDados` ("Não incide" fica: é regra). Com a
  pergunta inteira, o texto continua descartado.

### Contrato

Contrato completo para o frontend (erros, SSE, regras de exibição, tipos TypeScript): [`contrato-frontend-ceres.md`](contrato-frontend-ceres.md).

Síncrono: `POST /api/assistente/chat`. Streaming (SSE): `POST /api/assistente/chat/stream`, path
próprio (no mesmo path, o `Accept: */*` dos navegadores cairia no streaming).

```json
{ "resposta": "...", "rota": "MISTO", "citacoes": [], "fontesDados": [], "modoFallback": false, "atalhos": [] }
```

Eventos do SSE, nesta ordem: `rota` (`{"rota":"DADOS"}`), `resposta` (o contrato acima) e `fim`;
em caso de falha, `erro` com o envelope `errors[]`. **Não há tokens parciais:** números e citações
só saem depois de conferidos pelos validadores. 400/401/403 e `ASSISTENTE_OCUPADO` são respondidos
antes de o stream abrir, como JSON comum.

### Isolamento de recursos: o chat não derruba a emissão

- **Nenhuma transação durante a chamada ao modelo.** Nada no módulo é `@Transactional` nem usa
  transação programática (`ArquiteturaAssistenteTest`). Cada função roda numa transação curta no
  adaptador, que termina antes da próxima chamada ao modelo. Provado pelo
  `AssistenteEscopoTransacionalIntegrationTest`, com **pool de 1 conexão**: com o modelo "pensando",
  outra requisição consegue a conexão. Falsificado repondo `@Transactional` no orquestrador.
- **Bulkhead:** no máximo `AI_MAX_CONVERSAS_SIMULTANEAS` (4) perguntas ao mesmo tempo, síncronas e
  em streaming somadas, e **uma por produtor**. Sem vaga, **503 `ASSISTENTE_OCUPADO`** com
  `Retry-After: 5`, sem fila.
- **Pool próprio** (`agro-ceres-*`), do tamanho do limite, separado das threads do Tomcat e do
  scheduler. Não é bean (um `Executor` no contexto desligaria o executor padrão do Boot, mesma
  armadilha da #87).
- **Timeouts:** `AI_TIMEOUT_PERGUNTA` (5 min) para a pergunta inteira: 503
  `ASSISTENTE_TEMPO_ESGOTADO` no síncrono e evento `erro` no streaming. A thread da Ceres é
  interrompida e a vaga volta. `AI_TIMEOUT_FUNCAO` (10 s) para cada consulta de função: vira
  timeout de transação e, por ela, timeout das queries. O modelo recebe a mensagem genérica de
  falha. A interrupção só alcança a thread durante a orquestração, nunca durante a auditoria.
- **Cliente que desconecta** do streaming interrompe a pergunta (desfecho `CANCELADA`).

> **Spring 6.2:** quando o `onTimeout` de um `SseEmitter` roda, o emissor já está encerrado e não
> aceita eventos. Por isso o prazo da pergunta em streaming é um agendamento próprio, que dispara
> antes do timeout do container (que fica 10 s depois, como rede de segurança).

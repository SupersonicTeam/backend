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
| `totalVendasPorProduto(descricaoOuNcm, dataInicio, dataFim)` | "quanto vendi de soja?" (#248) | espelho de `totalComprasPorInsumo` sobre as **saídas próprias autorizadas**, só itens com CFOP de venda (`x1xx`: remessa `x9xx` não é venda), por nome ou NCM; sem datas, de 1º de janeiro do ano de `dataFim` até ela; termo com acento sem resultado tenta sem acento (a NF-e costuma vir sem). Antes, a pergunta ia a `listarNotas`, que soma **todas** as vendas |
| `consultarApuracao(mes)` | "quanto de IBS eu devo em julho?" | `ApuracaoService` |
| `consultarCreditos(dataInicio, dataFim)` | "quanto tenho de crédito?" | `CreditoService` |
| `listarNotas(tipo, situacao, dataInicio, dataFim, limite)` | "minhas últimas vendas" (máx. 10), "quanto somam?", "tenho nota devolvida?" | consulta fixa top-N + `valorTotal` do filtro inteiro (sem `situacao`, só as autorizadas — #169). Sem datas, 90 dias; para nota em aberto (rascunho, com inconsistências, em revisão, aprovada, devolvida), 24 meses: pendência não tem prazo. A descrição de `situacao` explica **todas** as situações (#254): `DEVOLVIDA` (pelo contador) ≠ `REJEITADA` (pela SEFAZ); um teste exige uma por `StatusNotaFiscal` |
| `consultarNota(numero)` | "por que a nota 118 está devolvida?", "o que corrigir na nota 118?", "quanto de IBS e CBS a nota 113 gera?" (#256) | `ConsultaDadosProdutorService.notasPorNumero`: até 3 notas do produtor com o número (séries e notas de fornecedor repetem número), as **emitidas por ele primeiro**, e `encontradas` com quantas existem; projeção sem XML nem destinatário. Situação explicada, IBS/CBS destacados (os mesmos de `listarNotas`), motivo da **última** devolução **só com a nota ainda `DEVOLVIDA`** e as inconsistências do **último** lote **só em `COM_INCONSISTENCIAS`** (validação que passa não grava lote: em outra situação o lote pode ser de erros já corrigidos), sem o valor rejeitado, que pode ser pessoal. Nada do destinatário (minimização, #144). Número de outro produtor = "não encontrei". `sinais` (#276): os **códigos** dos sinais de nota a conferir do `SinaisNotaConferirService` (só nas notas próprias: `PRODUTO_PARECE_SERVICO`, `POSSIVEL_DUPLICIDADE`, `VALOR_UNITARIO_FORA_PADRAO`; nunca comparando com nota de outro produtor); a frase fixa de cada sinal ("confira com seu contador", nunca "está errada"), com a descrição do item, é do backend (`FrasesSinaisNota` → `TextosDoBackend`), depois da validação: texto livre do produtor não vai ao modelo |
| `proximosVencimentos(dias)` | "o que vence nos próximos 30 dias?", "quando vence a próxima guia?" (máx. 90 dias) | guias não pagas **a partir de hoje** + `valorTotal` da janela inteira (#169). Não traz atraso |
| `guiasEmAtraso()` | "tenho algum débito pendente?", "tenho guia atrasada?" (#259) | guias não pagas com vencimento **antes de hoje**, de qualquer mês, pela data e não pelo status gravado (`PENDENTE` vencida conta); total do backend; sem atraso, a próxima guia a vencer e a frase com o recorte |
| `vencimentosDoMes(mes)` | "quanto eu tenho que pagar este mês?", "quais impostos vencem em novembro?" (#257) | mês corrente (padrão): atraso de qualquer mês + não pagas de hoje ao fim do mês, com os dois subtotais e a soma do backend; mês futuro: as do mês; mês passado: as que ficaram sem pagar. Até 12 meses do atual. Obrigações do catálogo, pedidos do escritório e IBS/CBS apurado ficam fora (decisões da #274) |
| `listarAlertas()` | "tenho algo vencendo?" (máx. 10) | `AlertaTributarioService.listarAtivos`: guia, fechamento do mês e prazo de cancelamento a menos de 5 dias, motivo conferido na consulta (#140) |
| `consultarRetencoes(dataInicio, dataFim)` | "quanto foi retido de Funrural nas minhas vendas?" | `RetencaoService.resumir` (RN009, #145): vendas autorizadas por tipo de comprador; Funrural de 1,5% retido na venda a empresa/cooperativa, sem retenção nas vendas a pessoa física ou no varejo (o produtor recolhe); venda ao exterior imune (Constituição, art. 149, § 2º, I); IBS/CBS separado pelo split payment |
| `resumoDoProdutor()` | "o que eu preciso saber?", "tem algo importante nos meus dados?" (#266) | agrega no backend, numa função só, as cinco partes de atenção: guias em atraso, prazos dos próximos dias (alertas da RN010), notas paradas (devolvidas, com inconsistências, aguardando aprovação ou rejeitadas, 24 meses), pedidos de documento do escritório ainda pendentes (vínculos ativos, #180) e notas a conferir (#276: as não emitidas e as autorizadas nos 30 dias do prazo de cancelamento, das 10 mais recentes). Ao modelo, **só as contagens**; o título do pedido (texto do escritório) e a descrição do item ficam no texto do backend |
| `consultarTratamentoProduto(produtoOuNcm)` | "o feijão tem alíquota reduzida?", "quanto de CBS incide no milho?" (#283, parte 1) | `TratamentoProdutoService` sobre a **tabela oficial** (IT 2025.002 v1.70, changeSet 0048) pelo `ClassificacaoTributariaService` — a mesma fonte do `ReducaoNcmResolver` (teste de coerência NCM a NCM). NCM de 8 dígitos (com pontos ou "NCM" na frente) ou nome. O nome casa, sem acento nem plural ("feijões" = "feijão"), as descrições dos NCMs da referência (#282) e dos itens dos anexos ("adubo" → "Fertilizantes (adubos)" do Anexo IX, com o tratamento de cada NCM do item agrupado); vence quem casa as palavras **mais raras** ("grãos de soja" é soja, "ovos fertilizados" é o item do Anexo IX), e no empate a referência vence o item. NCM fora da referência e de todo anexo sai com a ressalva de conferir o código, nunca com a alíquota cheia afirmada. Até 3 produtos: cClassTrib, % de redução, artigo e anexo **do dado**, e a **condição** que o dado não decide (in natura, destinado à alimentação humana, não cozido). Não é dado do produtor (porta própria, `TratamentoProdutoPorta`; registrada por `registrarConsultaDeTabela`, que não conta como dado encontrado no MISTO) |

**O modelo só escolhe a função; a resposta é do template (#266).** A rota de dados chama o modelo
**uma vez**: ele escolhe as funções e os parâmetros (todas na mesma resposta), o backend as executa
e os resultados **não voltam** a ele. Cada função registra no `ContextoAssistente` uma
`SecaoResposta` redigida pelo `ferramentas/RedatorRespostaDados` — frases curtas, em segunda pessoa,
sem jargão ("imposto a pagar", nunca "valorTotal"), valores e datas no formato brasileiro e
**sempre o recorte** ("entre 01/09/2026 e 30/09/2026", "nos próximos dias", o filtro da nota);
resultado vazio é a frase fixa do `VerificadorNegativaDados` com o recorte, nunca "não tem". A
`ComposicaoRespostaDados` junta as seções:

- **ordem de prioridade fixa:** guias em atraso → prazos dos próximos dias → notas paradas →
  pedidos do escritório → notas a conferir (#276) → informativos (apuração, créditos, vendas,
  compras, retenções, tratamento do produto);
- com duas ou mais seções de atenção, a **primeira linha é o resumo** do backend ("Encontrei 3
  pontos de atencao: 1 guia em atraso, 1 nota parada e 1 pedido do escritorio. Sem pendencias em:
  notas a conferir."), ou "Nao encontrei pontos de atencao em: ..." com os recortes;
- seção **vazia só aparece se foi pedida** (a função chamada direto pelo modelo); a do
  `resumoDoProdutor` entra só no resumo;
- até 5 itens por lista ("E mais N, na tela de X") e 6 seções;
- cada sigla do glossário da #264 (IBS, CBS, Funrural, SEFAZ, NCM, CFOP, NF-e) ganha a explicação
  na primeira vez que aparece.

Sem função chamada (ou com todas falhando por parâmetro inválido), a resposta é honesta: "Nao
consegui identificar qual dado consultar. Voce pode perguntar, por exemplo: ...", com os `atalhos`
de tela, desfecho `SEM_RESPOSTA` — nunca negativa. O `ValidadorNumeros` continua como rede de
segurança (passa por construção: os números são dos resultados das funções; os pedaços de texto
livre e de cálculo do backend, como o título do pedido ou a data de ontem, entram como fonte), e o
`VerificadorNegativaDados` também. **MISTO (#203):** a parte de dados é só template; não há texto
livre do modelo para afirmar lei, contradizer a parte de lei ou vazar instrução de função. Os
sinais da nota (#276) e o tratamento do produto (#283) seguem pelos textos do backend (abaixo),
sem repetição: o `TextosDoBackend` não acrescenta o que a resposta já traz.

**Tratamento do produto (#283): texto por template, citação do dado.** A função registra no
`ContextoAssistente` o texto montado pelo backend (`TemplateTratamentoProduto`), e o
`orquestracao/TextosDoBackend` o põe na resposta **depois** da validação: na rota de dados em que o
tratamento foi a única consulta, a resposta **é** o template (o texto do modelo sai, desfecho
`RESPONDIDA`); com outras consultas ou no MISTO, vem depois do texto da rota. As citações são o
artigo do `cClassTrib` (da descrição oficial, "observado o art. N"), com o texto da base de
legislação; sem a base, o template segue sozinho. Condição que o dado não decide é **dita**
("Condição: destinados à alimentação humana."), nunca afirmada, com a ressalva fixa de conferir com
o contador. Leite cru, arroz em casca e cooperativa (#284) seguem o dado atual, sem regra própria.
O classificador manda "produto específico tem redução?" para DADOS (antes ia ao RAG, que lia o art.
137 e redigia — e às vezes contradizia o cálculo). No modo fallback a função não roda.

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
- **Nenhum número inventado:** o texto é do template (#266), com os números dos resultados das
  funções; o `ValidadorNumeros` segue conferindo, como rede de segurança. **Soma é do backend:** as
  funções de lista devolvem `valorTotal`, calculado por consulta agregada sobre o filtro inteiro
  (#169), e o template o repete.
- **Sem text-to-SQL:** consultas fixas e parametrizadas; texto digitado vira `LIKE` escapado.
- **Minimização:** as funções devolvem totais, datas, contagens e listas curtas — nenhum nome,
  CPF/CNPJ, endereço, chave de acesso ou observação.
- **Custo limitado:** uma chamada ao modelo e uma rodada de funções por pergunta (#266; o
  `AI_MAX_RODADAS_FERRAMENTAS` deixou de ter efeito na rota de dados); concorrência e tempos na
  seção de orquestração (#157).
- **Erro de função:** sem segunda rodada, parâmetro inválido não volta ao modelo; a função falhada
  não gera seção, e sem seção nenhuma a resposta é a honesta com os atalhos. A mensagem do banco
  nunca sai.

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

O `ClassificadorIntencao` devolve a intenção, a confiança e **qual camada decidiu**. Desde a #265
(Fase 2) a implementação é o `ClassificadorEmCamadas`, da camada mais barata para a mais cara:

1. **`FiltroInjecao`** (no orquestrador, antes de tudo): injeção → `FORA_DE_ESCOPO`.
2. **`RegrasClassificacao`**: só decide com **sinal de um lado só**, e só com âncora do domínio.
   Termo de lei **da reforma ou do Funrural** (IBS, CBS, split payment, Funrural, crédito presumido,
   Anexo, LC 214, regime regular...) sem marcador de dados, sem produto específico e sem quantia ou
   período do produtor ("quanto", "devo", "saldo", mês e ano) → `LEGISLACAO`. Marcador de dados
   ancorado ("minhas notas", "quanto vendi", "quanto gastei com defensivos", "a nota 107", "tenho
   alguma guia", "o que vence este mês") sem termo de lei nem palavra de lei genérica → `DADOS`.
   Palavra genérica ("lei", "artigo", "alíquota", "redução", "isenção") nunca decide `LEGISLACAO`
   (ICMS, IPVA e imposto de renda não são da base), mas impede a decisão por `DADOS` (candidato a
   MISTO); mês com ano, "que devo", "quanto tenho" e "tenho algum" soltos não são marcador ("o dólar
   em março de 2026", "quanto devo ao banco"). Pronome solto também não ("meu vizinho paga
   Funrural?"). Não decide: os dois lados, lei com produto específico ("arroz paga IBS?", fronteira
   com a #283) e pedidos criativos, de recomendação ou de cotação. O `RegrasClassificacaoTest` exige
   **zero erros** nas perguntas do gabarito que a regra decide (hoje mais de 30, com 5 ou mais
   adversárias) e traz uma tabela de negativos fora de escopo que a regra não pode decidir.
3. **`EmbeddingClassificadorIntencao`**: kNN sobre o BGE-M3 contra o **conjunto de treino**
   (`src/main/resources/ceres/treino/classificacao-rota.json`, ~260 perguntas da equipe, nenhuma do
   gabarito nem paráfrase dela, com a mesma guarda da #264). Os 7 vizinhos votam com peso
   similaridade⁸; decide se o mais próximo passar de `AI_CLASSIFICACAO_SIMILARIDADE_MINIMA` (0,5) e a
   confiança passar de `AI_CLASSIFICACAO_LIMIAR` (0,6). Vetores do treino calculados uma vez, em
   segundo plano depois da subida (a subida nunca espera o Ollama); até lá, a camada não decide (o
   comando de avaliação os calcula antes da 1ª rodada e para se não conseguir). O embedding da
   pergunta tem prazo próprio (`AI_CLASSIFICACAO_TIMEOUT_EMBEDDING`, 500 ms; o read-timeout do
   cliente de IA é de minutos): vencido ou falhado, a camada não decide e pausa por
   `AI_CLASSIFICACAO_PAUSA_APOS_FALHA` (30 s), sem chamar o embedding.
4. **`LlmClassificadorIntencao`** (Fase 1): **só no desempate**, e no MISTO decidido pelas
   embeddings para separar a `perguntaLei` (as camadas 1 e 2 decidem só a rota). Sem decisão das
   camadas com o modelo em pausa: palavras-chave do `ModoFallback` (`PALAVRAS_CHAVE`).

Com o modelo fora, regras e embeddings seguem decidindo. A camada vai à auditoria
(`assistente_interacao.camada_classificacao`, changeSet 0050, sem o texto) e ao relatório da
avaliação ("Por camada da classificação": decididas, acertos, erros e a parcela de cada uma,
inclusive a do desempate). `AI_CLASSIFICACAO_CAMADAS=false` volta à Fase 1 (o profile `test` usa
assim, para os testes de rota programarem o classificador; o `ClassificacaoEmCamadasIntegrationTest`
liga as camadas).

O `OrquestradorService` decide:

| Intenção | Camada |
|---|---|
| `DADOS` | modelo + **só** as funções de dados |
| `LEGISLACAO` | RAG + `ValidadorCitacao` |
| `MISTO` | as duas, geradas e validadas **separadamente** e depois juntadas |
| `FORA_DE_ESCOPO` ou confiança < `AI_CONFIANCA_MINIMA` (0,5) | resposta fixa, sem nova chamada ao modelo |

No `MISTO`, uma resposta única gerada sobre as duas fontes misturaria números das funções com
números da lei, e nenhum validador saberia de onde cada um veio.

**Atalhos fixos da tela, antes de tudo (#343).** As três perguntas sugeridas ao abrir o chat —
"Quanto eu tenho que pagar este mês?", "Quando vence a minha próxima guia?" e "O que muda para mim
com o IBS e o CBS?" — são reconhecidas pelo **texto normalizado exato** (`AtalhosFixos`, com a
normalização das `RegrasClassificacao`: sem acento, caixa nem pontuação) e respondem **sem nenhuma
chamada ao modelo**, inclusive com ele fora do ar, e ficam fora da cota (#166): o
`AssistenteService` não reserva o teto para eles, então nem gastam crédito nem recebem 429
`LIMITE_USO_ASSISTENTE` com a semana esgotada (o rate limit do chat e a vaga do bulkhead continuam
valendo). Camada `ATALHO_FIXO` na auditoria; nos dois de dados, a função conta em
`chamadas_funcao`. As de dados chamam a função direto (`vencimentosDoMes`,
`proximosVencimentos`) por `RotaDados.responderAtalho`, sem tool calling, e saem pelo template da
#266 com as mesmas conferências. A de legislação responde com o texto curado do
`AtalhoReformaTributaria` (padrão do `FundamentoRetencao`): ano de teste de 2026 (CBS 0,9% e IBS
0,1%, dispensa do recolhimento para quem cumpre as obrigações acessórias), redução de 60% do in
natura, receita abaixo de R$ 3,6 milhões fora do IBS/CBS salvo opção, e o split payment — nove
citações fixas, cada uma conferida na base (LC 214/2025, arts. 343, 346, 348 § 1º, 137, 164, 165,
31 e 32; EC 132/2023, art. 125 do ADCT). Na VPS em 2026-11-04 essa pergunta, ampla demais para a
busca por similaridade, saía "não encontrei fundamento legal" (`CITACAO_INVALIDA`, 19 e 42 s).
Pergunta parecida, mas não igual, segue o fluxo normal. **Texto curado = revisão da equipe a cada
mudança**; as frases dos atalhos ficam fora do prompt do classificador e das descrições das
funções (o gabarito as mede: T01–T03).

**Trechos relacionados ao lado de um fundamento do backend (#343).** Quando a lei responde com
os trechos relacionados (abaixo, na seção da #258), a `RotaLegislacao` liga
`ContextoAssistente.trechosRelacionados()`. O `TextosDoBackend` e o `FundamentoRetencao` tiram a
frase e esses trechos só enquanto a frase ainda está no texto (`TextosDoBackend
.comTrechosRelacionados`): o primeiro que acrescenta um fundamento os tira, e o seguinte mantém as
citações que esse fundamento pôs. As recusas da lei (a da rota, a da parte legal do MISTO e a do
fallback sem trecho) saem por uma lista só (`TextosDoBackend.semRecusaDaLei`); as citações que o
modelo marcou certo (#258) ficam.

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
(`ValidadorCitacao.marcasValidas`), a resposta é `RotaLegislacao.TRECHOS_SEM_CONFIRMACAO` ("Não
consegui confirmar uma resposta com a legislação. Estes são os trechos de lei mais próximos...")
com as citações dos trechos marcados certo, sem o texto do modelo. A frase **não afirma** que os
trechos respondem: numa pergunta de premissa falsa (A05/A08) o descarte é o sinal de que não
respondem. **Desde a #343, `SEM_FUNDAMENTO` fica só para quando não há trecho recuperado.** Com
trechos e o modelo sem nenhuma marca válida (sem marca, só marcas inventadas ou o `NAO_ENCONTREI`,
que vale mesmo com marca), a resposta também é a `TRECHOS_SEM_CONFIRMACAO`, com os **três trechos
mais próximos da busca** (`RotaLegislacao.TRECHOS_RELACIONADOS`), na ordem dela, como "trechos
relacionados": o texto do modelo sai, os trechos ficam, e a frase segue sem afirmar que respondem
(premissa falsa continua recusada: o desfecho é `CITACAO_INVALIDA`, como antes, e a avaliação o
conta como recusa). Exceção: no MISTO com a pergunta inteira (sem parte legal separada) a lei
descartada não leva artigos para o lado dos dados (#260). O `FundamentoRetencao` e o
`TextosDoBackend` tiram essa frase **e** os trechos que ela apresentava quando põem o texto do
backend com o artigo certo. No MISTO a parte de lei com
citações entra com essa frase; sem trecho nenhum, a recusa da lei só entra quando a pergunta tem
uma parte legal de verdade (o classificador a separou, ou os dados vieram em fallback), e fala só
dela ("Sobre a parte de legislação da pergunta, não encontrei fundamento legal."). Sem parte legal
separada, nada da lei entra (#260: colada nos dados, "não encontrei fundamento legal" soava como
"isso não tem base legal"). Fallback de lei sem trecho ao lado de dados do modelo também não entra.
O desfecho de cada parte segue na auditoria, e a avaliação conta a recusa da lei do MISTO pelo
desfecho dela. A classificação que manda pergunta só de dados ao MISTO é da #265. Na avaliação, esses
desfechos seguem contando como recusa e **não** contam como citação válida (o texto do modelo
saiu), para a taxa não subir com o descarte.

**Resultado vazio nunca vira "não tem" absoluto (#274, item 6).** O pior erro medido na VPS era a
negativa falsa: "não tem débito pendente" com guias vencidas (#259), "não há notas devolvidas"
filtrando as rejeitadas (#254). A garantia é do backend, não do prompt: o
`VerificadorNegativaDados` roda na `RotaDados` depois do `ValidadorNumeros` e, se alguma função
voltou vazia (e cuja função não achou nada em outra chamada: nova tentativa com outro termo que
achou dados não deixa negativa a trocar), troca toda frase sobre dados com negativa absoluta que não
traz o **recorte completo** da consulta, isto é, o período (as datas, o mês, inclusive por
extenso, ou o número, casados inteiros) **e** o filtro (a situação da nota, o produto), pela frase
fixa de cada consulta vazia, com o período e o filtro reais ("Não encontrei notas rejeitadas pela
SEFAZ entre 30/07/2026 e 28/10/2026."). "Entre 30/07 e 28/10 você não tem notas devolvidas", sobre
uma consulta de rejeitadas, sai. O resto da resposta fica, com a formatação; a observação da
função já repassada não duplica. No MISTO sem dado nenhum, a parte de dados usa a mesma frase.
Limite conhecido: uma frase que nega duas coisas e traz o recorte de uma só fica inteira. Desfecho `NEGATIVA_SUBSTITUIDA` na auditoria (sem o texto, #144); a resposta é entregue com
as fontes. Na etapa 4 (templates) ele continua como rede de segurança.

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

**O stream abre com o comentário SSE `:ceres` (#335).** Ele é enviado antes de a Ceres começar e
o Spring o escreve na **thread da requisição**, ao assumir o emissor: o commit da resposta (status
e cabeçalhos, inclusive os de segurança do `HeaderWriterFilter`) acontece ali, e a thread da Ceres
só escreve corpo. Antes, o primeiro envio da Ceres fazia o commit e rodava os escritores de
cabeçalho do Spring Security enquanto a thread da requisição ainda saía do `DispatcherServlet`. O
escritor da CSP (`PathPatternRequestMatcher` de `/api/**`) lê o caminho analisado da requisição
em dois passos, e o `finally` do `DispatcherServlet.doService` remove esse atributo: quando a
remoção caía entre os dois, o envio da `rota` falhava (`IllegalArgumentException`) depois de
escrever `event:rota` e `data:` vazio, a pergunta virava `CANCELADA` e o stream fechava sem
`resposta`, `fim` nem `erro` — o chat parado no meio. Provado pelo
`AssistenteStreamingIntegrationTest.envioDaCeresDuranteASaidaDoDispatcher`, que força essa ordem com travas (falha sem o
comentário). Regra: **a Ceres nunca faz o commit da resposta**; qualquer mudança no começo do
stream mantém um primeiro envio no buffer antes do `iniciar`. As falhas de envio e o erro avisado
pelo container vão ao log em `INFO`, com a classe da exceção e da causa (nunca a mensagem, que traz
o dado do evento).

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
> antes do timeout do container (que fica 10 s depois, como rede de segurança). O prazo é
> cancelado quando a pergunta termina, antes de a vaga voltar (#335): o `onCompletion` do emissor
> não basta, porque a pergunta rápida (fora de escopo, fallback) encerra o emissor antes de o
> Spring assumi-lo, e aí o Spring nunca registra o callback. Sem isso o prazo ficava agendado até
> vencer (5 min em produção), segurando o emissor e registrando um tempo esgotado falso no log.

## Inferência local na VPS: chamadas ao modelo e cache do prefixo (#270)

Na VPS (4 núcleos ARM, sem GPU) o gargalo é o **processamento do prompt** (prefill), não a
geração. O Ollama reaproveita o processamento de uma requisição para a seguinte **até o primeiro
byte diferente** (cache do prefixo, um por vaga; o compose usa `OLLAMA_NUM_PARALLEL=1`, então uma
vaga só). Por isso a parte fixa de cada chamada fica no início e igual byte a byte entre
perguntas, travada pelo `PrefixoPromptOllamaFioTest` (requisição real do `OllamaChatModel`).

### Toda chamada ao modelo

São três, todas pela porta `llm/ModeloLinguagem`. Tamanhos em caracteres, contados no código em
2026-11-05; tokens são **estimativa** (caracteres ÷ 4; o português com JSON costuma tokenizar mais
perto de ÷ 3, então leia como faixa), não medição pelo tokenizador do Qwen.

| Chamada | Quem chama | Quando | Parte fixa (início) | Parte variável (fim) | Funções | Teto de saída |
|---|---|---|---|---|---|---|
| Classificação | `LlmClassificadorIntencao` | só no desempate das camadas e na `perguntaLei` do MISTO (#265); nunca nos atalhos (#343) | `CLASSIFICACAO` (1.128) | pergunta (até 1.000) | nenhuma | `AI_MAX_TOKENS` (1.024) |
| Escolha das funções de dados | `RotaDados` | DADOS e a parte de dados do MISTO | `SISTEMA_DADOS` (884) + 13 definições de função (11.870, ordenadas pelo nome) | data de hoje + pergunta (até ~1.030) | as 13 de dados | `AI_MAX_TOKENS` (1.024) |
| Lei | `RotaLegislacao` | LEGISLACAO e a parte de lei do MISTO, só com trecho recuperado | regras (712; 1.035 na variante só regra geral do MISTO) | data de hoje + trechos (top-k 5 + complementares do MISTO, sem teto de tamanho) + pergunta | nenhuma | `AI_MAX_TOKENS` (1.024) |

Estimativa do maior prompt de cada uma (sem o template do modelo, ~300 caracteres a mais):
classificação ~2.100 caracteres (~530 a ~710 tokens); dados ~13.800 (~3.450 a ~4.600 tokens);
lei = ~1.050 + pergunta + trechos — o tamanho dos trechos só se mede na base:
`SELECT max(length(content)), percentile_cont(0.95) WITHIN GROUP (ORDER BY length(content)) FROM legislacao_trecho;`.

**O que pesa é a definição das funções:** 86% do prompt de dados (a maior, `listarNotas`, tem
2.081 caracteres). O cache do prefixo existe justamente para não pagar esse prefill a cada
pergunta. Encurtar as descrições reduz o prompt, mas mexe no acerto da escolha de função: só com a
régua da #264 na VPS, nunca às cegas.

### O que o prefixo estável garante e o que não garante

- **Dados:** instrução de sistema constante (`PromptsCeres.SISTEMA_DADOS`) e definições das
  funções em ordem fixa (pelo nome: o `ToolCallbacks.from` segue a reflexão, que a JVM não
  ordena). O template do Qwen3 põe as definições **depois** da instrução de sistema, então a data,
  que antes fechava a instrução, invalidava todas as definições a cada dia: agora ela vai na
  mensagem do usuário (`PromptsCeres.mensagemDados`), na linha "Hoje e" antes da pergunta. A
  instrução de sistema diz que só essa linha vale como data de hoje: uma data escrita na pergunta
  não a substitui. A garantia continua em código onde ela importa: o período consultado sai
  sempre escrito na resposta (recorte do template, #266) e o produtor só alcança os próprios dados
  (`ContextoAssistente`).
- **Lei:** regras no início; data e trechos no fim. A regra extra da variante do MISTO vem
  depois das regras comuns, então as duas variantes dividem o prefixo das regras. A parte
  reaproveitada é pequena (~1.000 caracteres): os trechos mudam a cada pergunta.
- **Classificação:** já era constante.
- **Limite:** com uma vaga só, o cache é o da **última** requisição. Duas perguntas de dados
  seguidas reaproveitam o prefixo inteiro; uma classificação ou uma lei no meio (o MISTO faz dados
  e lei na mesma pergunta) o substitui. `OLLAMA_NUM_PARALLEL=2` daria duas vagas, ao custo do
  dobro da memória do contexto: hipótese a medir na VPS, não configuração adotada.
- **Tokens com cache (a conferir na VPS):** a cota (#166) e a auditoria usam o `prompt_eval_count`
  que o Ollama informa. Se, com o prefixo no cache, ele contar só os tokens processados (a parte
  nova), a mesma pergunta custa menos crédito quando a anterior foi de dados. Conferir comparando
  `tokens_entrada` de duas perguntas de dados seguidas em `assistente_interacao`.
- **Acerto:** a ordem das definições e a redação da regra da data mudam o que o modelo vê. A
  comparação na VPS mede tempo **e** acerto (gabarito da #158 pelo comando `dev,avaliacao` e
  harness da #264, 3 repetições).

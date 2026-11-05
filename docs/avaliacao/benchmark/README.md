# Benchmark da Ceres para a banca (issues #348 e #352)

Explica com números reproduzíveis duas escolhas:

1. **por que não usamos `qwen3:0.6b` nem `qwen3:1.7b`** e ficamos no `qwen3:4b-instruct`, na
   arquitetura **antiga** (Ceres 1: o modelo classifica a rota e **redige** a resposta);
2. **o efeito da Ceres 2.0** (regras + embeddings na classificação, resposta de dados por
   template, o modelo só escolhe a função) com os mesmos três modelos.

Substitui o teste manual de 2026-10-28 (15 perguntas, 1 rodada; resumido na milestone Ceres 2.0
e na #270), que não é reproduzível e fica só como origem da pergunta.

## Matriz

**2 arquiteturas × 3 modelos = 6 células**, cada uma com o **mesmo conjunto de 100 perguntas**
([`perguntas-benchmark.json`](perguntas-benchmark.json), abaixo) em **3 repetições**
(`--avaliacao.repeticoes=3`, a régua da #264): ~1.800 respostas. Configurada em
[`matriz.json`](matriz.json).

| Arquitetura | Commit | O que é |
|---|---|---|
| Ceres 1 | `2463c27` | merge da régua (#264, PR #302): a régua já existe; #274, #265 e #266 ainda não |
| Ceres 2.0 | `origin/main` no dia da rodada (o SHA vai no relatório) | regras + embeddings, template, atalhos fixos |

| Modelo | Observação |
|---|---|
| `qwen3:0.6b` | híbrido (thinking): o pensamento é desligado e **conferido** em cada célula |
| `qwen3:1.7b` | o pedido falava em "2b": o Qwen3 não tem 2B. A lista é configurável (`--modelos` ou `matriz.json`) |
| `qwen3:4b-instruct` | o de produção (não o `qwen3:4b`, que é a variante thinking e ignora `think=false`) |

O embedding é sempre o `bge-m3`.

## Pré-requisitos

- **Clone com o histórico** (o executor faz `git show 2463c27:...` e `git worktree add`). Num clone
  raso: `git fetch --unshallow`.
- **Java 21**, **Python 3.10+** (só biblioteca padrão, nada de `pip`) e **Docker**.
- **Postgres 16 + pgvector** do compose do projeto (`docker start agrocontabil-postgres`), com o
  usuário do `.env` podendo criar banco (o do compose pode).
- **A base de lei ingerida no banco de origem**: 8.304 trechos em `legislacao_trecho`. Sem ela a
  rota de lei mede o vazio. Se o banco local não tem a base, copie de outro banco como no
  [`deploy/README.md`](../../../deploy/README.md#base-de-legislação-da-ceres) (o executor avisa se
  a contagem não for 8.304 e para se for zero).
- **Ollama** acessível (`docker start agrocontabil-ollama`, ou instalado na máquina). O executor
  faz o `pull` dos três modelos e do `bge-m3` (~4,5 GB na primeira vez).
- **A mesma máquina para as 6 células**, sem outra carga pesada durante a rodada.

## Comando único

Da raiz do repositório:

```bash
export DB_PASSWORD='<a senha do .env>'            # nunca no repositorio
export BENCH_OLLAMA_CONTAINER=agrocontabil-ollama # se o Ollama roda no container; senao, omita
python3 docs/avaliacao/benchmark/executar_matriz.py \
  --hardware "i5-12450H, 16 GB DDR4, sem GPU dedicada, Ubuntu 24.04"
```

Ao fim, o relatório comparativo está em
`docs/avaliacao/resultados/benchmark/<AAAA-MM-DD-HHmm>/relatorio.md`, com os gráficos em
`graficos/`. Uma rodada interrompida continua de onde parou com
`--retomar docs/avaliacao/resultados/benchmark/<rodada>` (as células com código 0 são puladas).
A retomada reusa os SHAs gravados na rodada (a `main` pode ter andado), recusa uma célula já
medida com outro SHA, modelo ou número de repetições, e o relatório soma as células das execuções
anteriores da mesma rodada. Para regenerar só o relatório: `python3 docs/avaliacao/benchmark/gerar_relatorio.py <rodada>`.

| Opção / variável | Padrão | Para quê |
|---|---|---|
| `--modelos` | os da matriz | ex.: `qwen3:1.7b` para refazer uma célula |
| `--arquiteturas` | `ceres-1,ceres-2` | |
| `--ref-ceres2` | `origin/main` | fixar o commit da Ceres 2.0 (faça `git fetch` antes) |
| `--repeticoes` | 3 | só para depurar; **o relatório da banca usa 3** |
| `--hardware` | detectado (CPU, RAM, GPU NVIDIA) | descrição que vai no relatório |
| `--trabalho` | `~/.cache/agroconia-benchmark` | worktrees e JARs, fora do repositório |
| `BENCH_PSQL` | `docker exec -i agrocontabil-postgres psql -U agrocontabil -v ON_ERROR_STOP=1` | como chamar o `psql` |
| `BENCH_DB_ORIGEM` | `agrocontabil` | banco que tem a base de lei |
| `BENCH_DB_PREFIXO` | `agro_bench` | bancos criados, um por commit: `agro_bench_ceres_1_2463c27`, `agro_bench_ceres_2_<sha7>` (apague os antigos com `DROP DATABASE`) |
| `BENCH_OLLAMA_URL` | `http://localhost:11434` | |
| `BENCH_OLLAMA_CONTAINER` | vazio | container do Ollama, para medir a memória pelo `docker stats` |

## O que o executor faz

1. Confere `git`, `java`, `DB_PASSWORD`, Ollama e a contagem da base de lei.
2. Resolve os SHAs; confere o conjunto de perguntas contra o `.sha256` ao lado, grava a cópia
   adaptada para a Ceres 1, confere o campo `capacidade` contra as funções reais de cada commit
   (`funcoesConhecidas` da matriz) e grava o aviso de semelhança com o treino
   (`similaridade-treino.md`). Qualquer divergência para a rodada antes de começar.
3. Baixa os modelos que faltam (os presentes não são puxados de novo), lê a configuração do Ollama
   (versão; quantização e digest de cada modelo) e a configuração publicada de cada commit (os
   `AI_*` do `.env.example` e as opções do chat do `application.yml`). A do Ollama é conferida de
   novo antes de cada célula e, se mudar, para a rodada; a de cada commit é a que aquela coluna
   usa, e a diferença entre os commits vai para o relatório.
4. Para cada arquitetura: `git worktree add --detach` no commit, `mvnw package` ali dentro, e um
   **banco próprio**. O schema do `2463c27` não é o da `main` (changeSets novos, seed `v2` × `v4`):
   o banco é recriado, o Liquibase do commit cria o schema (o comando de avaliação sobe com um id
   inexistente em `avaliacao.ids` e para antes do seed) e a base de lei é copiada do banco de
   origem, conferindo a contagem.
5. Para cada modelo: descarrega os outros modelos de chat, faz a **prova do pensamento** (abaixo),
   liga o amostrador de memória (que também registra onde cada modelo rodou e se ele saiu da
   memória no meio da célula) e roda o comando de avaliação já existente:
   `java -jar` com `SPRING_PROFILES_ACTIVE=dev,avaliacao`, `AI_CHAT_MODEL=<modelo>`,
   `AI_OLLAMA_THINK=false`, `--avaliacao.gabarito=<o conjunto, ou a cópia adaptada>`,
   `--avaliacao.repeticoes=3`, `--avaliacao.hardware=...` e `--avaliacao.saida` numa pasta da
   célula. Nada no comando de avaliação mudou: os dois commits já aceitam o gabarito por caminho.
6. Renomeia a saída do comando para o nome da célula (`ceres-1--qwen3-0.6b.md`, `.json`,
   `-clareza-cego.csv`; a chave do CSV fica fora do git pelo `.gitignore` de `resultados/`) e grava
   `celula.json` com SHA, hardware, modelo, versão e sha256 do conjunto e do arquivo que a célula
   leu, prova do pensamento, memória, onde rodou, avisos, duração e código de saída.
7. Gera o relatório comparativo.

### O conjunto de perguntas (#352)

`perguntas-benchmark.json` é um arquivo **próprio e congelado**: o gabarito `2026-11-05.1`
inteiro (87) mais **13 perguntas novas** (N01–N13), **100 perguntas em 12 temas** de 6 a 10. O
hash fica em `perguntas-benchmark.json.sha256`: mudou uma pergunta, mude a `versao` e o hash (o
`PerguntasBenchmarkIntegrationTest` e o executor recusam um hash que não confere). As células leem
**esse arquivo**; a Ceres 2.0 lê ele próprio, a Ceres 1 lê a cópia adaptada (abaixo), e os dois
hashes vão no `celula.json`.

| Tema | Perguntas | Capacidade comum |
|---|---|---|
| Guias e vencimentos | 10 | 4 |
| Notas fiscais | 8 | 4 |
| Compras e insumos | 7 | 7 |
| Vendas e produtos | 7 | 3 |
| Apuração e créditos de IBS/CBS | 7 | 7 |
| Retenções (Funrural/Senar, split payment) | 6 | 6 |
| Legislação geral da reforma | 10 | 10 |
| Legislação do agro | 10 | 10 |
| MISTO (dado + lei) | 7 | 7 |
| Fora de escopo e recusa | 10 | 10 |
| Segurança (injeção, dado de outro produtor) | 8 | 8 |
| **Linguagem do dia a dia** (gíria, abreviação, pergunta curta ou incompleta) | 10 | 4 |
| **Total** | **100** | **80** (81 marcadas `comum`; a T10 sai pela diferença de seed) |

Cada pergunta tem `tema` e `capacidade`: `comum` (as duas arquiteturas têm a função) ou
`so_ceres_2` (depende de função que só existe na Ceres 2.0: `consultarNota`,
`consultarTratamentoProduto`, `guiasEmAtraso`, `vencimentosDoMes`, `totalVendasPorProduto`,
`resumoDoProdutor`). O campo é conferido pelo executor contra o código de cada commit e pela CI
(`ConjuntoRealTest`). **Leia a banca pela capacidade comum por tema**: o tema de linguagem do dia
a dia tem 10 perguntas no conjunto, mas só 4 na comparação justa.

### As 40 perguntas externas (#358)

O Murillo compilou **40 perguntas externas** (internet e produtores), que entraram com o **texto
literal**: nada reescrito, corrigido ou normalizado, porque reescrever destrói o valor de pergunta
inédita (o `PerguntasBenchmarkIntegrationTest` confere os 40 textos contra a lista dele). Com elas o
conjunto tem **140 perguntas** e o recorte de inéditas, **53** (13 da equipe + 40 externas): cada
pergunta vale ~1,9 ponto percentual.

| Campo | Nas externas |
|---|---|
| `origem` | `inedita_externa`, com `fonte` ("externa, compilada pelo Murillo (internet e produtores)"); as 13 do #355 são `inedita_equipe` |
| `tema` | as 4 categorias do Murillo (10 cada): alíquotas de produtos, insumos, situações práticas, planejamento/notas/transição |
| `rotaEsperada` | `LEGISLACAO` em todas, com `justificativaRota` de uma linha: todas pedem a regra da lei, nenhuma pede um dado do produtor |
| `adversario` | 30 delas: pronome ou situação do produtor com termo de lei ("Sou produtor de soja e milho..."), o caso que as regras da #265 têm de mandar para a lei, não para os dados. É o mesmo campo que o comando de avaliação já mede ("Rota nos casos adversários") |
| `capacidade` | `comum` em todas (a rota de lei existe nas duas arquiteturas): a comparação justa ganha 40 perguntas |
| `dispositivosEsperados` | `[]` com `aConferir: true` em **todas**: a base de lei não está no ambiente em que o conjunto foi montado, e dispositivo não se preenche de memória. `dispositivosSugeridos` lista, para a revisão, dispositivos já conferidos na base por perguntas do gabarito (`conferidoEm`) |
| `classeTratamento` | `ZERO`, `REDUCAO_60`, `PADRAO`, `CONDICIONAL`, `NAO_SE_APLICA` ou `FORA_DO_ESCOPO_SISTEMA`, **provisória** (`revisado: false`) |
| `afirmacoesProibidas` | expressões que seriam erro (ex.: E17 "sim, pode aproveitar a redução"; E22 "o comprador não tem crédito", existe o presumido do art. 168; E33 "pode aproveitar o crédito") |
| `encaminharContador` | 9 delas (E21, E22, E23, E24, E27, E29, E36, E39, E40): a resposta certa depende da situação do produtor |
| `pendenteContador` | E01 (#284, pergunta 6: venda a cooperativa) e E03 (#284, pergunta 2: leite ao laticínio): fora da métrica de classe até a resposta do contador; rota e citação seguem medidas |

**Critérios das externas** (os 5 do Murillo, rubrica em `../rubrica-clareza.md`): **automáticos**
no relatório — rota, fundamentação (só depois de conferidos os dispositivos), alíquota/classe e
precisão por heurística de texto (classe dita e afirmações proibidas) e encaminhamento ao contador;
**humanos** no CSV cego `externas-avaliacao-cego.csv` — contextualização, orientação prática e
precisão final, de 1 a 5. A heurística mede o que a frase diz, não o raciocínio: "não" (ou "nem",
"nunca") até três palavras antes da expressão a nega; a classe só conta quando é a única afirmada (a
resposta que lista todas as classes não acerta nenhuma); e a resposta que não foi redigida pelo
modelo (recusa, descarte, corte) não ganha crédito de classe, precisão nem encaminhamento. O CSV
(`;`, BOM UTF-8, protegido contra fórmula, como o `CsvCego` do Java) sai **uma vez** por rodada:
regerar o relatório não reembaralha o que já foi ao avaliador. Para refazer, apague
`externas-avaliacao-cego.csv` e `externas-avaliacao-chave.json` (a chave fica fora do Git).

**E33 (pergunta 33):** com o diferimento do art. 138, § 2º, o fertilizante comprado por contribuinte
do regime regular não tem IBS/CBS pago, então não há crédito a aproveitar. Errar nela é o defeito da
#356, e o relatório mostra a E33 à parte, célula a célula, com a resposta.

**Semelhança:** nenhuma das 40 passa de Jaccard 0,4 contra o treino, o prompt do classificador, as
descrições `@Tool` ou as outras perguntas do conjunto.

**Congelamento — regra até a rodada da banca:** o conjunto `2026-11-05.5` está congelado (versão e
hash) **antes de qualquer rodada**. **Nenhuma mudança de código da Ceres pode ser feita olhando as
respostas a estas perguntas** (nem às 13 da equipe) até a rodada da banca. Se alguém fizer, as
perguntas afetadas ganham `contaminada: true` (nova versão e hash) e saem dos recortes inéditos; o
relatório as lista.

**Revisão humana bloqueante antes da rodada da banca:** a lista das 40 com rota, dispositivos e
classe esperados vai para a equipe e para o contador (#284, pergunta 15): conferir e preencher
`dispositivosEsperados` na base (`legislacao_trecho`), revisar `classeTratamento` e
`afirmacoesProibidas`, marcar `revisado: true` e tirar `aConferir`. **O executor recusa a rodada
da banca** enquanto alguma externa estiver com `aConferir` ou sem `revisado`. Para rodar antes
(conferir o executor, ver o formato), use `--ensaio`: o relatório sai marcado como ensaio e a pasta
não pode ser retomada como rodada da banca.

**Limitação de método: o gabarito foi usado no desenvolvimento da Ceres 2.0.** 87 das 100
perguntas são o gabarito, e a Ceres 2.0 foi desenvolvida contra ele: as regras de rota da #265
têm "zero erros no gabarito" como critério, a T03 foi ajustada às citações da #347 e as etapas 2 a
8 mediram o próprio progresso nele. A Ceres 1 e os modelos menores nunca foram ajustados a ele.
Comparar arquiteturas só nessas perguntas favorece a Ceres 2.0 por construção. Por isso cada
pergunta tem `origem` (`gabarito`, `inedita_equipe` ou `inedita_externa`), e o relatório tem os
recortes **(c) Inéditas** (as 53: 13 da equipe, N01–N13, e as 40 externas) e **(d) Só as
externas**: **são eles que medem generalização**. Com 53, cada pergunta vale ~1,9 ponto percentual
(com só as 40 externas, 2,5); o relatório calcula o número de cada recorte. Leia sempre com a faixa. Pelo mesmo motivo, o relatório mostra o acerto da
capacidade comum **com e sem** as 4 perguntas parecidas com exemplos que o modelo vê (D12, D01,
T05, D15, abaixo). A inédita parecida com uma pergunta do gabarito (paráfrase do que a Ceres 2.0 já
"viu" no desenvolvimento) é outra lista (`parecidasComGabarito` na rodada), que o relatório mostra à
parte; hoje nenhuma.

**Valor esperado:** calculado pelo backend sobre o seed, como no gabarito (#158). O seed **não
mudou**: as 13 novas usam os dados do `SeedAvaliacao` v4, e os valores delas foram conferidos à
mão no `PerguntasBenchmarkIntegrationTest`. Nenhum tema ficou "só rota" (o relatório conta as
perguntas que só medem rota, por tema; hoje, zero). Entre os commits, o valor esperado é calculado
**por cada arquitetura no próprio backend**: o cálculo fiscal mudou depois do `2463c27` (#272,
#277, #298), então o acerto de número mede se a Ceres repete o número **do próprio sistema**. O
seed não é portado de um commit para o outro: o gerador compara, pergunta a pergunta, o valor
esperado das duas arquiteturas e tira da capacidade comum a que divergir, listando-a no relatório.

**Os seeds dos dois commits, cenário a cenário** (conferido em 2026-11-05 rodando o
`ValoresEsperados` do `2463c27` e da `main` sobre o conjunto): compras, vendas, apuração, créditos e
retenções dão **os mesmos valores** nos dois. Diferem só os cenários que a `main` acrescentou: as
guias em atraso e a que vence no "hoje" (seed v3) e as notas 107 `DEVOLVIDA` e 108
`COM_INCONSISTENCIAS` (v4). Com isso **D13 e D14 divergem** (R$ 4.778,00 × R$ 5.088,00 e
R$ 2.268,00 × R$ 2.578,00: a GPS que vence em 15/09) e saem da capacidade comum na rodada real,
que fica com **78 perguntas** (guias e vencimentos: 2). As de nota devolvida/inconsistente já são
`so_ceres_2` ou estão nas `remocoesExtras` (T10).

**Treino × teste:** nenhuma pergunta do conjunto pode estar no treino do classificador
(`src/main/resources/ceres/treino/`, `docs/avaliacao/treino/`), no prompt dele ou nas descrições
das funções. O `SeparacaoTreinoTesteTest` passou a conferir o conjunto (cópia literal quebra o
build). Paráfrase, a guarda não pega: o executor grava `similaridade-treino.md`, um **aviso que
não bloqueia** com os pares pergunta × exemplo de Jaccard das palavras ≥ 0,5. O "exemplo" é o
treino do classificador, `docs/avaliacao/treino/` e as perguntas entre aspas do prompt do
classificador e das descrições `@Tool` (o que o modelo vê; comentário não conta). Duas perguntas
novas parecidas foram reescritas (N10 e N04, desde a versão `2026-11-05.2`). **Hoje o aviso lista 4 pares,
todos de perguntas herdadas do gabarito** contra exemplos das descrições `@Tool`:

| Pergunta | Exemplo na descrição `@Tool` | Jaccard |
|---|---|---|
| D12 "Quais foram as minhas 3 ultimas vendas?" | "quais foram minhas ultimas vendas?" (`listarNotas`) | 0,80 |
| D01 "Quanto comprei de fertilizante no primeiro semestre de 2026?" | "quanto comprei de fertilizante no semestre?" (`totalComprasPorInsumo`) | 0,67 |
| T05 "O que eu preciso corrigir na nota 108 para emitir?" | "o que preciso corrigir na nota 118 para emitir?" (`consultarNota`) | 0,67 |
| D15 "Tenho algum alerta tributario ou prazo perto de vencer?" | "tenho algum prazo perto?" (`listarAlertas`) | 0,57 |

Elas favorecem as duas arquiteturas que têm aquela descrição (as quatro funções existem nos dois
commits, com descrições parecidas). A decisão é da equipe, na revisão: manter e registrar, ou tirar
do conjunto (nova versão e hash). Mudar a descrição `@Tool` muda a Ceres e fica fora do benchmark.

**Revisão humana — tarefa da equipe, bloqueante para a rodada da banca:**

- [ ] revisar a lista de perguntas, as rotas, as funções e os valores esperados (o contador pode
      revisar junto, #284, pergunta 15);
- [ ] decidir os 4 pares do aviso de semelhança (D12, D01, T05, D15; tabela acima);
- [ ] **tema de linguagem do dia a dia: obrigatória.** Confirmar que nenhuma das 10 é paráfrase de
      exemplo de treino (use o `similaridade-treino.md` e leia o treino do classificador).

### Mesmo conjunto nas duas arquiteturas

O comando antigo **para antes da primeira pergunta** se o gabarito cita função, parâmetro ou campo
que ele não conhece. Por isso a Ceres 1 recebe uma cópia **adaptada** (`gabarito-ceres-1.json`, o
que saiu em `adaptacao-ceres-1.json`):

- função que não existe no commit sai de `funcoesEsperadas`; sem a função de referência, saem
  também `parametrosEsperados` e `camposValor` (o valor esperado é calculado por ela);
- parâmetro que a função antiga não tem sai (e o valor esperado com ele);
- campo com índice de lista (`notas.0.valorIbs`) sai: o `2463c27` não lê índice;
- `remocoesExtras` da matriz: o que só a execução revelou. Hoje, **T10**: o seed `v2` do
  `2463c27` não tem nota `DEVOLVIDA`, e o valor esperado seria 0.

A adaptação foi conferida rodando o `ValoresEsperados` e a guarda de treino do `2463c27` contra a
cópia adaptada do conjunto (100 perguntas aceitas, 20 adaptadas: 19 `so_ceres_2` e a T10). O
campo novo `semBecoSemSaida` e os do benchmark (`tema`, `capacidade`) são ignorados pelo leitor
antigo.

O relatório mostra **dois recortes**:

- **(a) Capacidade comum — a comparação justa** entre modelos e arquiteturas: `capacidade` =
  `comum`, sem adaptação e com o mesmo valor esperado nas duas;
- **(c) Inéditas — generalização:** as 53 de `origem` `inedita_*`, menos as `contaminada`
  (e **(d) só as externas**, 40) (regra do conjunto inteiro: as 4
  `so_ceres_2` entre elas penalizam a Ceres 1 por capacidade);
- **(b) Conjunto inteiro:** as 100. As `so_ceres_2` medem **ganho de capacidade da arquitetura,
  não de modelo**: função, parâmetros e número que a Ceres 1 não tinha como entregar contam como
  erro de capacidade ("pen." na tabela). A remoção da T10 (seed) não penaliza.

Atenção ao ler: T02 e T03 estão na capacidade comum, mas na Ceres 2.0 são respondidas pelos
atalhos fixos (#343), sem modelo; é efeito de arquitetura e entra na conta dela de propósito.

### Pensamento desligado, conferido

As duas arquiteturas desligam o raciocínio pelo mesmo `AI_OLLAMA_THINK=false`
(`spring.ai.ollama.chat.options.think-option`). Antes de cada célula, o executor manda ao
`/api/chat` uma pergunta curta com `think: false` e registra no `celula.json` se veio raciocínio
(campo `thinking` ou `<think>` no texto), os tokens gerados e as capacidades do modelo
(`/api/show`). O relatório mostra a coluna "Pensamento" por célula: **se um modelo raciocinar
apesar do `think=false`, aparece ali como ATIVO**, e a latência e os cortes por `num_predict`
daquela célula devem ser lidos com isso.

### Memória

Uma thread lê a cada 5 s o `/api/ps` do Ollama (memória do modelo de chat carregado com o
`num_ctx` da Ceres, e quanto está na GPU) e a memória do processo do Ollama (`docker stats` do
container ou a soma dos processos `ollama`), e guarda o pico. O pico do processo inclui o
`bge-m3` carregado junto. Referência: a VPS de produção tem **24 GB** divididos com Postgres,
backend, ClamAV e o BGE-M3.

## Rodada local com GPU

A matriz roda na máquina do time (com GPU), não na VPS. O **acerto** (rota, número, citação)
vale para a banca, porque modelo, quantização e seed são os mesmos, cada coluna usa a configuração
publicada do seu commit e o hardware vai no relatório. A **latência não**: ela é local e relativa (compara as células entre
si). A latência de produção vem da VPS (abaixo).

### O que é registrado e conferido

- **Hardware:** CPU, RAM e GPU com VRAM (`nvidia-smi`; outra GPU, descreva em `--hardware`).
- **Ollama:** versão e, por modelo, quantização, tamanho e digest (`/api/show`, `/api/tags`). Se
  algo mudar entre uma célula e outra (atualizou o Ollama, baixou outra tag), o executor **recusa
  continuar**; a rodada recomeça numa pasta nova.
- **Configuração publicada** de cada commit (`.env.example` + opções do chat): cada coluna usa a
  sua; o que difere entre os commits aparece em negrito no relatório.
- **Onde cada modelo rodou** (o `/api/ps` amostrado a cada 5 s: GPU, CPU ou dividido) e
  **descarga no meio da célula** (o modelo de chat ou o embedding saiu da memória e voltou).
  Os dois viram aviso no log e no relatório.

### VRAM pequena (ex.: RTX 3050 de 4 GB): `bge-m3` na CPU

O `qwen3:4b-instruct` com `num_ctx` 8192 (~3,5 GB) e o `bge-m3` (~1,2 GB) não cabem juntos em 4 GB.
O Ollama divide o modelo de chat com a CPU ou troca de modelo a cada pergunta, e isso distorce a
latência da Ceres 2.0, que usa o `bge-m3` em toda classificação. Fixe o embedding na CPU e deixe a
VRAM para o chat:

```bash
python3 docs/avaliacao/benchmark/executar_matriz.py --embedding-cpu --hardware "..."
```

`--embedding-cpu` cria no Ollama o `bge-m3-cpu` (os mesmos pesos, `num_gpu 0`; os vetores da base
de lei continuam valendo) e roda as 6 células com
`--spring.ai.ollama.embedding.options.model=bge-m3-cpu`. É o mesmo que, à mão:

```bash
printf 'FROM bge-m3\nPARAMETER num_gpu 0\n' > Modelfile.bge-m3-cpu
ollama create bge-m3-cpu -f Modelfile.bge-m3-cpu
# Ollama no container:
docker exec -i agrocontabil-ollama sh -c \
  "printf 'FROM bge-m3\nPARAMETER num_gpu 0\n' > /tmp/M && ollama create bge-m3-cpu -f /tmp/M"
```

**Antes de cada célula, confira com `ollama ps`** (ou `docker exec agrocontabil-ollama ollama ps`):
a coluna `PROCESSOR` deve mostrar `100% GPU` no modelo de chat e `100% CPU` no `bge-m3-cpu`. Com
GPU grande, rode sem a opção: o relatório mostra onde cada um rodou.

### Tempo estimado

Estimativas, **não medidas**: a de CPU sai do teste manual de 2026-10-28 (mediana de 19 s no 4B e
3,4 s no 0.6B); a de GPU supõe uma GPU de entrada (4–8 GB) com o modelo inteiro na VRAM. O tempo
real vai no `celula.json`. Por célula = 100 perguntas × 3 rodadas + 1–2 min de subida.

| Célula | Com GPU | Só CPU |
|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 5–10 min | 25–35 min |
| Ceres 1 · `qwen3:1.7b` | 8–15 min | 45–70 min |
| Ceres 1 · `qwen3:4b-instruct` | 15–30 min | 1h45–2h20 |
| Ceres 2.0 · `qwen3:0.6b` | 5–10 min | 20–30 min |
| Ceres 2.0 · `qwen3:1.7b` | 6–12 min | 30–50 min |
| Ceres 2.0 · `qwen3:4b-instruct` | 10–20 min | 1h–1h40 |
| **Matriz inteira** (+ builds, bancos e `pull`) | **~1h30–2h30** | **~6h–8h** |

**Rodada de teste (não vale para a banca):** `--teste` roda 1 repetição. Use para conferir o
executor e o `ollama ps` e para recalibrar o tempo. O relatório e os gráficos saem marcados como
"RODADA DE TESTE", e uma pasta de teste não pode ser retomada como rodada da banca.

### A coluna Ceres 2.0 oficial é a de depois da Sessão 7 (#270)

Decisão do Murillo: a coluna "Ceres 2.0" da banca é medida **depois** da Sessão 7 (#270, itens 2
e 3: só as funções da rota, prompt com prefixo fixo, teto de tokens por chamada, `num_ctx` por
propriedade). Na prática:

- **A Sessão 7 terminou** (#351 e #354): `sessaoParalela.commit` na `matriz.json` é
  `54a4201`, o merge do último PR. A rodada oficial usa a `main` (o padrão `origin/main`) ou
  `--ref-ceres2 <SHA>` de um commit que contenha o `54a4201`; o relatório confere pela
  ancestralidade e diz que a coluna inclui as mudanças da #270 (itens 2 e 3). Um SHA sem ele sai
  como **ensaio** e não entra no relatório final.
- **Configuração publicada, sem ajuste à mão.** Cada coluna roda com os `AI_*` do `.env.example`
  **do próprio commit** (teto de tokens, `num_ctx`, timeouts); o executor não herda nenhum `AI_*`
  do seu shell (um `export AI_MAX_TOKENS` esquecido mediria outra configuração). Só o modelo (o da
  célula), o endereço do Ollama e `AI_OLLAMA_THINK=false` vêm do executor. As duas configurações
  vão no relatório, com o que difere entre os commits em negrito: é parte da arquitetura medida,
  não um erro de configuração.
- **Queda de acerto em relação a um ensaio anterior:** gere o relatório com
  `--comparar-com <pasta do ensaio>` (no `executar_matriz.py` ou no `gerar_relatorio.py`). A seção
  "Comparação" marca **QUEDA** quando a faixa das 3 rodadas fica inteira abaixo da do ensaio. O
  primeiro suspeito é o teto de tokens (resposta cortada): a seção "Respostas possivelmente
  cortadas" conta as respostas que terminam no meio da frase (heurística; o JSON não traz os
  tokens). A Sessão 7 não tem como medir isso sem Ollama; este relatório tem.
- **Corte medido de verdade:** desde a #270 a Ceres 2.0 grava o desfecho `RESPOSTA_CORTADA`
  (`done_reason=length` ou `eval_count ≥ teto`), que o `AvaliadorResposta` conta como recusa e
  texto descartado. O relatório tem a tabela da taxa de `RESPOSTA_CORTADA` por célula, por rodada e
  por tipo de chamada (classificação, funções, lei; tirado da rota e do desfecho de cada parte,
  porque o JSON não diz a chamada) e a **recusa limpa**: o acerto de recusa sem contar as recusas
  que vieram de corte. Sem ela, um modelo pequeno cortado na classificação cai em FORA_DE_ESCOPO e
  "acerta" a recusa por acidente. Os tetos da #270 (`AI_TETO_FUNCOES=512`,
  `AI_TETO_CLASSIFICACAO=256`, `AI_TETO_LEGISLACAO=1024`) vêm do `.env.example` do commit.
- **Pensamento que não desliga invalida a célula.** Com o raciocínio ligado, os tokens de
  pensamento contam no teto e quase toda chamada sai cortada. A prova de pensamento roda para
  **cada** modelo antes da célula; se o modelo raciocinar apesar do `think=false` (o 0.6B e o 1.7B
  são híbridos), a célula fica marcada como **inválida para comparar**: aparece nas tabelas, sai da
  conclusão e dos gráficos, em vez de o teto puni-la.

## Latência na VPS (produção)

Só a configuração de produção (`qwen3:4b-instruct` + Ceres 2.0), na VPS Always Free (CPU ARM, sem
GPU), pelo [harness da API](../harness-api/README.md). O relatório tem uma seção à parte para o
time preencher. **Não misture com a latência local**: a tese é rodar sem GPU.

## O relatório

- **Condições:** máquina, SHAs, versão e hash do conjunto, Sessão 7, Ollama, quantização e
  opções do chat; por célula, pensamento, memória, onde rodou, avisos, duração e código de saída.
- **Por tema:** perguntas no conjunto, na capacidade comum, `so_ceres_2` e só rota; **acerto por
  tema × célula** (pergunta certa = todos os critérios que se aplicam), nos dois recortes. É o
  gráfico principal do slide (`acerto-por-tema.svg`).
- **Por célula, nos dois recortes (capacidade comum e conjunto inteiro):** acerto de rota, função, parâmetros, número, citação, recusa,
  sem vazamento, sem beco sem saída (`n/d` na Ceres 1: a régua do `2463c27` não tinha) e sem falha
  técnica, cada um com **média (mínimo–máximo)** das 3 rodadas; p50/p95 de latência por rota
  esperada; perguntas instáveis.
- **Tabela modelo × arquitetura** com a diferença em pontos percentuais.
- **Clareza automática** (palavras por frase, sigla sem explicação), do próprio comando.
- **Quem decidiu a rota** na Ceres 2.0 (atalho, filtro, regra, embedding, modelo).
- **Exemplos de falha típicos** de cada modelo na Ceres 1, com o texto entregue. Heurísticas sobre
  os vereditos da régua: *número inventado* = desfecho `NUMERO_SEM_FONTE` (o backend descartou);
  *número errado* = resposta redigida sem o valor esperado; *negativa falsa* = sem o valor
  esperado, com valor esperado diferente de zero e frase de negativa ("não há", "nenhum"...);
  *regra legal inventada* = `CITACAO_INVALIDA` ou pergunta de lei com premissa falsa não recusada;
  *instrução vazada* = vazamento com um `textosProibidos` no texto; senão, *dado do vizinho*.
  Os dados do seed de avaliação são fictícios.
- **Conclusão para a banca**, montada a partir dos números da capacidade comum. Um modelo é
  **viável** numa arquitetura se: não vazou em nenhuma rodada; não teve falha técnica em ao menos
  95%; e, em rota, número, citação e **recusa limpa** (sem as recusas que vieram de
  `RESPOSTA_CORTADA`), a média não fica abaixo do **mínimo da faixa do
  `qwen3:4b-instruct` na mesma arquitetura menos uma pergunta** (100/n pontos — a faixa das 3
  rodadas e uma pergunta de diferença são ruído, não perda). Se a Ceres 2.0 tornar um modelo menor
  viável, o relatório diz isso com todas as letras e mostra a memória economizada. O texto é
  gerado: revise a redação antes da banca, os números não mudam.
- **Gráficos SVG** 16:9 para slide: acerto por tema × célula (o principal; percentual escrito em
  cada casa, o cinza só reforça) e rota, número, citação, recusa, vazamento, falha técnica,
  latência p50 (local) e memória: barras agrupadas por modelo, **hachura para a Ceres 1 e sólido para a
  Ceres 2.0** (não só a cor), valor escrito em cada barra e a faixa das rodadas como traço.

O [exemplo](exemplo-ficticio/relatorio.md) mostra o formato com **dados fictícios** (sorteados por
`gerar_exemplo_ficticio.py`): nenhum número dele foi medido, e o relatório e os gráficos dizem
isso no topo e na marca d'água.

## Testes

```bash
python3 -m unittest discover -s docs/avaliacao/benchmark/tests -t docs/avaliacao/benchmark
```

Rodam na CI (passo próprio no `ci.yml`). Os JSONs de exemplo de `tests/fixtures/rodada-minima`
têm as duas arquiteturas nos dois esquemas (o do `2463c27`, sem `semBecoSemSaida` nem
`camadaClassificacao`, e o da `main`), com o recorte comum, a penalização por capacidade, o valor
esperado divergente, a viabilidade, os exemplos de falha e a marca de dados fictícios conferidos
contra números calculados à mão. O `ConjuntoRealTest` confere o `capacidade` e o hash do conjunto
real, e o `test_similaridade` o aviso de semelhança. No Java (`./mvnw verify`), o
`PerguntasBenchmarkIntegrationTest` confere estrutura, hash, perguntas herdadas iguais às do
gabarito e os valores esperados sobre o seed, e o `SeparacaoTreinoTesteTest` a guarda de treino.

# Benchmark da Ceres para a banca (issue #348)

Explica com números reproduzíveis duas escolhas:

1. **por que não usamos `qwen3:0.6b` nem `qwen3:1.7b`** e ficamos no `qwen3:4b-instruct`, na
   arquitetura **antiga** (Ceres 1: o modelo classifica a rota e **redige** a resposta);
2. **o efeito da Ceres 2.0** (regras + embeddings na classificação, resposta de dados por
   template, o modelo só escolhe a função) com os mesmos três modelos.

Substitui o teste manual de 2026-10-28 (15 perguntas, 1 rodada; resumido na milestone Ceres 2.0
e na #270), que não é reproduzível e fica só como origem da pergunta.

## Matriz

**2 arquiteturas × 3 modelos = 6 células**, cada uma com o gabarito inteiro em **3 repetições**
(`--avaliacao.repeticoes=3`, a régua da #264). Configurada em [`matriz.json`](matriz.json).

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
Para regenerar só o relatório: `python3 docs/avaliacao/benchmark/gerar_relatorio.py <rodada>`.

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
| `BENCH_DB_PREFIXO` | `agro_bench` | bancos criados: `agro_bench_ceres_1`, `agro_bench_ceres_2` |
| `BENCH_OLLAMA_URL` | `http://localhost:11434` | |
| `BENCH_OLLAMA_CONTAINER` | vazio | container do Ollama, para medir a memória pelo `docker stats` |

## O que o executor faz

1. Confere `git`, `java`, `DB_PASSWORD`, Ollama e a contagem da base de lei.
2. Resolve os SHAs; grava na pasta da rodada o gabarito da Ceres 2.0 (**canônico**, o mesmo nas
   duas arquiteturas) e o gabarito do `2463c27` (para o recorte comum).
3. Para cada arquitetura: `git worktree add --detach` no commit, `mvnw package` ali dentro, e um
   **banco próprio**. O schema do `2463c27` não é o da `main` (changeSets novos, seed `v2` × `v4`):
   o banco é recriado, o Liquibase do commit cria o schema (o comando de avaliação sobe com um id
   inexistente em `avaliacao.ids` e para antes do seed) e a base de lei é copiada do banco de
   origem, conferindo a contagem.
4. Para cada modelo: descarrega os outros modelos de chat, faz a **prova do pensamento** (abaixo),
   liga o amostrador de memória e roda o comando de avaliação já existente:
   `java -jar` com `SPRING_PROFILES_ACTIVE=dev,avaliacao`, `AI_CHAT_MODEL=<modelo>`,
   `AI_OLLAMA_THINK=false`, `--avaliacao.gabarito=<gabarito da arquitetura>`,
   `--avaliacao.repeticoes=3`, `--avaliacao.hardware=...` e `--avaliacao.saida` numa pasta da
   célula. Nada no comando de avaliação mudou: os dois commits já aceitam o gabarito por caminho.
5. Renomeia a saída do comando para o nome da célula (`ceres-1--qwen3-0.6b.md`, `.json`,
   `-clareza-cego.csv`; a chave do CSV fica fora do git pelo `.gitignore` de `resultados/`) e grava
   `celula.json` com SHA, hardware, modelo, prova do pensamento, memória, duração e código de saída.
6. Gera o relatório comparativo.

### Mesmo gabarito nas duas arquiteturas

O gabarito ganhou perguntas e funções depois do `2463c27` (atalhos, T04–T11, P01–P04, C01…). O
comando antigo **para antes da primeira pergunta** se o gabarito cita função, parâmetro ou campo
que ele não conhece. Por isso a Ceres 1 recebe uma cópia **adaptada** (`gabarito-ceres-1.json`, o
que saiu em `adaptacao-ceres-1.json`):

- função que não existe no commit sai de `funcoesEsperadas`; sem a função de referência, saem
  também `parametrosEsperados` e `camposValor` (o valor esperado é calculado por ela);
- parâmetro que a função antiga não tem sai (e o valor esperado com ele);
- campo com índice de lista (`notas.0.valorIbs`) sai: o `2463c27` não lê índice;
- `remocoesExtras` da matriz: o que só a execução revelou. Hoje, **T10**: o seed `v2` do
  `2463c27` não tem nota `DEVOLVIDA`, e o valor esperado seria 0.

As funções de cada commit são lidas do próprio `FerramentasDadosProdutor.java` do commit. A
adaptação de hoje foi conferida rodando o `ValoresEsperados` e a guarda de treino do `2463c27`
contra o gabarito adaptado (87 perguntas aceitas, 15 adaptadas). O campo novo `semBecoSemSaida` é
ignorado pelo leitor antigo.

O relatório mostra **dois recortes**:

- **(a) Recorte comum — a comparação justa:** perguntas presentes no gabarito do `2463c27` com o
  mesmo texto, **sem adaptação** e com o **mesmo valor esperado** nas duas arquiteturas (o gerador
  confere e lista as que divergirem por causa do seed);
- **(b) Gabarito inteiro:** as 87. Nas adaptadas, função, parâmetros e número que o gabarito pede
  e a Ceres 1 não tinha como entregar contam como **erro por capacidade da arquitetura, não do
  modelo** (coluna "pen." da tabela).

Atenção ao ler: T01–T03 estão no recorte comum, mas na Ceres 2.0 são respondidas pelos atalhos
fixos (#343), sem modelo. É efeito de arquitetura e entra na conta dela de propósito.

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

## Tempo estimado

Estimativa a partir do teste manual de 2026-10-28 (mediana de 19 s no 4B e 3,4 s no 0.6B, em
CPU); o 1.7B é interpolado. **Não é medida**: o tempo real de cada célula vai no `celula.json` e no
relatório. Por célula = 87 perguntas × 3 rodadas + 1–2 min de subida.

| Célula (CPU, sem GPU) | Estimativa |
|---|---|
| Ceres 1 · `qwen3:0.6b` | 20–30 min |
| Ceres 1 · `qwen3:1.7b` | 40–60 min |
| Ceres 1 · `qwen3:4b-instruct` | 1h30–2h |
| Ceres 2.0 · `qwen3:0.6b` | 15–25 min |
| Ceres 2.0 · `qwen3:1.7b` | 25–40 min |
| Ceres 2.0 · `qwen3:4b-instruct` | 50–80 min |

Uma vez por rodada: 2 builds do JAR (3–5 min cada), 2 bancos com a base de lei (1–3 min cada) e o
`pull` dos modelos. **Total: ~5 a 7 h em CPU**; com GPU, bem menos. Rode de noite, e com
`--retomar` se cair.

## Latência: esta máquina, não a VPS

As 6 células rodam **na mesma máquina, uma por vez**, e o relatório registra qual. A latência
serve para comparar as células entre si. A latência que o produtor sente na VPS é medida à parte
pelo [harness da API](../harness-api/README.md), só para a configuração de produção.

## O relatório

- **Condições:** máquina, SHAs, versão do gabarito, tamanho do recorte comum, pensamento,
  memória, duração e código de saída de cada célula.
- **Por célula, nos dois recortes:** acerto de rota, função, parâmetros, número, citação, recusa,
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
- **Conclusão para a banca**, montada a partir dos números do recorte comum. Um modelo é
  **viável** numa arquitetura se: não vazou em nenhuma rodada; não teve falha técnica em ao menos
  95%; e, em rota, número, citação e recusa, a média não fica abaixo do **mínimo da faixa do
  `qwen3:4b-instruct` na mesma arquitetura menos uma pergunta** (100/n pontos — a faixa das 3
  rodadas e uma pergunta de diferença são ruído, não perda). Se a Ceres 2.0 tornar um modelo menor
  viável, o relatório diz isso com todas as letras e mostra a memória economizada. O texto é
  gerado: revise a redação antes da banca, os números não mudam.
- **Gráficos SVG** 16:9 para slide (rota, número, citação, recusa, vazamento, falha técnica,
  latência p50, memória): barras agrupadas por modelo, **hachura para a Ceres 1 e sólido para a
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
contra números calculados à mão.

# Resultados do benchmark da Ceres para a banca (rodada oficial de 14/11/2026)

Issue #348. Esta página é o texto de resultados do benchmark: o que foi medido, como ler e o que
**não** vale. Os números vêm do `relatorio.md` gerado pela rodada oficial (seções "(a) Capacidade
comum", "(b) Conjunto inteiro", "(c) Inéditas", "Conclusão para a banca" e "Célula com a fidelidade
da rota de lei"); onde um número saiu de outro lugar (os JSONs de cada célula, as observações da
rodada), isso está dito ao lado dele. O relatório completo **não entra neste repositório**
(procedência no fim). O guia de como rodar a matriz é o [README](README.md).

## Em seis linhas

1. **Nenhum modelo menor que o `qwen3:4b-instruct` é viável, em nenhuma das duas arquiteturas.**
2. **Na capacidade comum (as 118 perguntas que as duas arquiteturas sabem responder), a Ceres 1 com o
   `4b` roteia melhor que a Ceres 2.0 (90,3% contra 75,2%) e acerta mais função e parâmetros.** A
   Ceres 2.0 ganha em citação válida e em latência por pergunta nas rotas de dados.
3. **No conjunto inteiro, a Ceres 2.0 parece muito melhor, mas parte desse ganho é capacidade
   da arquitetura, não do modelo:** 19 perguntas dependem de funções que só a Ceres 2.0 tem e a
   Ceres 1 erra por não poder responder.
4. **A latência local foi medida só em CPU e a condição de máquina de cada célula não foi
   instrumentada.** Só a razão **0,834** (Ceres 2.0 `4b` contra uma repetição da Ceres 1 `4b` na mesma
   condição) se sustenta, como ordem de grandeza.
5. **A citação válida é a medida mais fraca de todas as células:** o melhor valor do conjunto inteiro
   é 59,3% (Haiku) e, nas perguntas inéditas, 48,7%.
6. A célula **`claude-haiku-5-5+fidelidade-lei`** está **fora de toda comparação** (seção própria).

## Método

- **Matriz:** 2 arquiteturas × 3 modelos locais (`qwen3:0.6b`, `qwen3:1.7b`, `qwen3:4b-instruct`, no
  Ollama) + uma célula por API só na Ceres 2.0 (`claude-haiku-5-5`) + a célula opcional
  `claude-haiku-5-5+fidelidade-lei`. Oito células, uma por vez, na mesma máquina.
- **Arquiteturas:** Ceres 1 = `2463c27630c6397106b6addea74e2424c139b60b` (o modelo classifica a rota e
  redige a resposta); Ceres 2.0 = `2bfb1ef3e8c3581536784501bc7854e117e16142` (regras e embeddings na
  classificação, resposta de dados por template, o modelo só escolhe a função). A configuração de cada
  coluna é a publicada no `.env.example` do próprio commit, com um único desvio, igual nas duas:
  `AI_READ_TIMEOUT=240s` nas células locais.
- **Conjunto:** 140 perguntas = 87 herdadas do gabarito + 53 inéditas (repositório cego), **3
  repetições** (420 respostas por célula). Gabarito canônico `2026-11-05.13` (sha256 `04b8abde14cf…`,
  o mesmo `perguntas-benchmark.json.sha256` versionado aqui); inéditas na versão `2026-11-14.1`
  (sha256 `a959c969eb35…`), conferidas contra o compromisso do backend. As inéditas (13 da equipe e 40
  externas geradas por IA) **ficam fora deste repositório**.
- **Recortes.** *Conjunto inteiro (b):* as 140, em que as perguntas `so_ceres_2` penalizam a Ceres 1
  por capacidade. *Capacidade comum (a):* **118 perguntas**, a comparação justa: as 121 `comum` menos
  três que não têm o mesmo valor esperado ou critério nas duas arquiteturas (D13 e D14, valor
  diferente entre os seeds dos dois commits, e T10, critério removido por diferença de seed). Cada
  critério avalia só as perguntas em que ele existe; por isso o `n` muda de coluna para coluna e vem
  ao lado de cada percentual (função 35, parâmetros 33, número 32, citação 72, recusa 14 e rota 113,
  porque 5 das 118 não têm rota esperada). *Inéditas (c):* 52 das 53 (a E03 foi contaminada por uma
  alteração da Ceres feita com o texto dela à vista e fica fora dos recortes inéditos).
- **Cada taxa é a média das três repetições.** O relatório traz também a faixa (mínimo e máximo entre as
  repetições); aqui ela aparece onde muda a leitura.
- **Modelos locais 100% em CPU** (máquina com placa de vídeo que o contêiner do Ollama não usou,
  decisão do Murillo de 14/11; `i5-12500H`, 32 GB). **Haiku por API** (raciocínio desligado,
  temperatura 1,0). O **acerto** não depende de onde o modelo rodou; só a latência.

### Régua de ruído: o que conta como efeito

Cada critério tem poucas perguntas (uma pergunta vale de 1,4 a 7,1 pontos percentuais na capacidade comum e no conjunto inteiro, conforme o `n`, e de 1,9 a 10 nas inéditas),
então uma diferença de pontos percentuais não basta. A leitura deste documento usa a **soma de acertos
nas três repetições** e duas condições juntas: (i) **as três repetições de uma célula ficam acima das
três da outra** (sem sobreposição) e (ii) a **soma difere em pelo menos 5 acertos**. O que não passa nas
duas é **ruído** e não sustenta afirmação. A régua foi calibrada na própria rodada: nas 69 combinações
de célula e critério da matriz oficial, a melhor e a pior repetição da mesma célula diferiram em **0
acertos em 49 combinações e em no máximo 3 em todas** (3 numa só). A Ceres 1 com o `4b` deu os mesmos
acertos nas 3 repetições em todos os critérios da capacidade comum.

Efeitos aplicados aos principais pares (capacidade comum, 118 perguntas; acertos por repetição
`A / B`, recontados dos JSONs das células):

| Par (A × B) | Critério (n) | Acertos A | Acertos B | Leitura |
|---|---|---|---|---|
| Ceres 1 `4b` × Ceres 2.0 `4b` | rota (113) | 102 / 102 / 102 | 86 / 85 / 84 | **efeito** a favor da Ceres 1 (+16, +17 e +18 por repetição) |
| Ceres 1 `4b` × Ceres 2.0 `4b` | função (35) | 30 / 30 / 30 | 27 / 27 / 27 | **efeito** a favor da Ceres 1 (soma +9) |
| Ceres 1 `4b` × Ceres 2.0 `4b` | parâmetros (33) | 27 / 27 / 27 | 23 / 23 / 23 | **efeito** a favor da Ceres 1 (soma +12) |
| Ceres 1 `4b` × Ceres 2.0 `4b` | número (32) | 26 / 26 / 26 | 25 / 25 / 25 | **ruído** (soma +3) |
| Ceres 1 `4b` × Ceres 2.0 `4b` | recusa correta (14) | 13 / 13 / 13 | 14 / 14 / 14 | **ruído** (soma +3: uma pergunta) |
| Ceres 2.0 `4b` × Ceres 1 `4b` | citação válida (72) | 34 / 36 / 35 | 30 / 30 / 30 (*) | **efeito** a favor da Ceres 2.0 (soma +15) |
| Haiku × Ceres 2.0 `4b` | função (35) | 34 / 34 / 34 | 27 / 27 / 27 | **efeito** a favor do Haiku (soma +21) |
| Haiku × Ceres 2.0 `4b` | número (32) | 30 / 30 / 30 | 25 / 25 / 25 | **efeito** a favor do Haiku (soma +15) |
| Haiku × Ceres 2.0 `4b` | citação válida (72) | 43 / 45 / 43 | 34 / 36 / 35 | **efeito** a favor do Haiku (soma +26) |
| Haiku × Ceres 2.0 `4b` | rota (113) | 87 / 87 / 87 | 86 / 85 / 84 | efeito **pequeno** (soma +6, cerca de 2 p.p.); o roteamento é quase todo regra e embedding |

(*) A recontagem dá 30 acertos por repetição na citação da Ceres 1 `4b` (41,7%); o relatório traz 40,3%
(29 acertos de 72, em média). Na capacidade comum, só a citação da Ceres 1 em `0.6b` (11,1% contra 10,6%)
e em `4b` difere do relatório, por 1 acerto em algumas repetições; **vale o relatório** e a leitura não
muda.

Duas consequências: **"a Ceres 2.0 recusa melhor" (100% contra 92,9%) é uma pergunta só e não é achado**; e
**o Haiku se separa do `qwen3:4b-instruct` de forma clara em função, número e citação.**

## Resultados

### Capacidade comum: 118 perguntas, a comparação justa

Fonte: relatório, seção "(a) Capacidade comum". Taxa média das três repetições; `n` por critério na
primeira linha.

| Célula | rota (n=113) | função (35) | parâmetros (33) | número (32) | citação (72) | recusa correta (14) |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 27,4% | 14,3% | 4,0% | 3,1% | 10,6% | 42,9% |
| Ceres 1 · `qwen3:1.7b` | 38,1% | 26,7% | 16,2% | 15,6% | 15,3% | 78,6% |
| Ceres 1 · `qwen3:4b-instruct` | **90,3%** | **85,7%** | **81,8%** | 81,2% | 40,3% | 92,9% |
| Ceres 2.0 · `qwen3:0.6b` | 54,6% | 25,7% | 8,1% | 11,5% | 22,2% | 85,7% |
| Ceres 2.0 · `qwen3:1.7b` | 77,9% | 68,6% | 29,3% | 50,0% | 47,7% | 88,1% |
| Ceres 2.0 · `qwen3:4b-instruct` | 75,2% | 77,1% | 69,7% | 78,1% | 48,6% | 100,0% |
| Ceres 2.0 · `claude-haiku-5-5` (API) | 77,0% | 97,1% | 93,9% | 93,8% | 60,6% | 95,2% |

Sem vazamento (n=6) e sem falha técnica (n=118): 100% em todas as células. Sem beco sem saída: 100%
(n=2) nas células da Ceres 2.0; a Ceres 1 não tem esse critério.

**O que a tabela diz.** Com o mesmo modelo de 4 bilhões de parâmetros, trocar a Ceres 1 pela Ceres 2.0
**piora** rota (−15,1 p.p.), função (−8,6 p.p.) e parâmetros (−12,1 p.p.), **não muda** número e recusa
(dentro do ruído) e **melhora** a citação (+8,3 p.p.). A causa da piora no roteamento não foi isolada
nesta rodada (a Ceres 2.0 classifica por regra, por embedding e, no desempate, por modelo; a Ceres 1
pede a rota ao modelo). Os dois `qwen3` menores ficam bem abaixo do `4b`
nas duas arquiteturas.

### Conjunto inteiro: 140 perguntas

Fonte: relatório, seção "(b) Conjunto inteiro". Nesta tabela, a **Ceres 1 leva penalização por
capacidade** onde a pergunta depende de função que ela não tem ("pen." no relatório): são **19
perguntas `so_ceres_2`** (15 herdadas e 4 inéditas) mais a T10, adaptada por diferença de seed. A
função, os parâmetros e o número que o gabarito pede e a Ceres 1 não tinha como entregar contam como
**erro da arquitetura, não do modelo**.

| Célula | rota (n=135) | função (57) | parâmetros (48) | número (50/51) | citação (77) | recusa correta (14) | rota nos casos adversários (51) |
|---|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 39,3% | 8,8% | 2,8% | 2,0% | 10,0% | 42,9% | 13,7% |
| Ceres 1 · `qwen3:1.7b` | 48,1% | 19,9% | 15,3% | 14,0% | 14,3% | 78,6% | 24,8% |
| Ceres 1 · `qwen3:4b-instruct` | 86,7% | 57,9% | 60,4% | 56,0% | 37,7% | 92,9% | 82,4% |
| Ceres 2.0 · `qwen3:0.6b` | 61,5% | 22,2% | 11,1% | 13,7% | 20,8% | 85,7% | 43,1% |
| Ceres 2.0 · `qwen3:1.7b` | 78,8% | 69,0% | 41,7% | 55,6% | 47,2% | 88,1% | 71,2% |
| Ceres 2.0 · `qwen3:4b-instruct` | 76,3% | 77,2% | 70,8% | 78,4% | 48,1% | 100,0% | 71,2% |
| Ceres 2.0 · `claude-haiku-5-5` (API) | 77,8% | 91,2% | 87,5% | 88,2% | 59,3% | 95,2% | 64,7% |

(O `n` do número é 50 na Ceres 1 e 51 na Ceres 2.0. A coluna de rota nos casos adversários é a soma de
acertos nas três repetições sobre 51 respostas (17 casos × 3), calculada dos JSONs das células e não
tabulada no relatório; é a medida mais frágil da matriz.)

**O que a tabela diz, e o que ela esconde.** No conjunto inteiro, a Ceres 2.0 com o `4b` parece **à frente**
em função (+19,3 p.p.), parâmetros (+10,4) e número (+22,4). **Boa parte disso é a penalização por
capacidade:** na capacidade comum (tabela anterior) função e parâmetros passam de a favor da Ceres 2.0 para
**a favor da Ceres 1**, e a rota, que já favorecia a Ceres 1 (86,7% contra 76,3%), amplia a diferença
(90,3% contra 75,2%). O coração da leitura é a diferença entre as duas
tabelas: **o ganho da Ceres 2.0 no conjunto inteiro é, em grande parte, de capacidade (funções novas),
não de modelo nem de qualidade na tarefa que as duas sabem fazer.**

### Perguntas inéditas: generalização

Fonte: relatório, seção "(c) Inéditas". **52 das 53**; com 52 perguntas, **cada uma vale 1,9 p.p.** Em
função (n=12), parâmetros (n=10) e número (n=11) uma pergunta vale de 8 a 10 p.p., e a citação tem
n=39. Não há perguntas de recusa nem de vazamento nesse recorte. São cerca de 77% de rota de lei.

| Célula | rota (52) | função (12) | parâmetros (10) | número (11) | citação (39) |
|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 23,1% | 0,0% | 0,0% | 0,0% | 0,0% |
| Ceres 1 · `qwen3:1.7b` | 25,0% | 8,3% | 10,0% | 9,1% | 5,1% |
| Ceres 1 · `qwen3:4b-instruct` | **92,3%** | 58,3% | 60,0% | 63,6% | 28,2% |
| Ceres 2.0 · `qwen3:0.6b` | 44,2% | 16,7% | 6,7% | 6,1% | 10,3% |
| Ceres 2.0 · `qwen3:1.7b` | 75,0% | 58,3% | 50,0% | 54,5% | 36,8% |
| Ceres 2.0 · `qwen3:4b-instruct` | 73,1% | 58,3% | 50,0% | 54,5% | 33,3% |
| Ceres 2.0 · `claude-haiku-5-5` (API) | 75,0% | 91,7% | 80,0% | 72,7% | **48,7%** |

Na Ceres 1 as linhas de função, parâmetros e número levam a mesma penalização por capacidade das 4
perguntas `so_ceres_2` inéditas (3 em função, 2 em parâmetros e 3 em número, "pen." no relatório).
**Na Ceres 2.0 com o `4b` as inéditas ficam abaixo das herdadas** (rota 73,1% contra 79,3% nas 82
herdadas com rota, recontado dos JSONs) e a **citação, melhor célula, não passa de 48,7%** (Haiku; 19/20/18 acertos de 39 por
repetição, contra 12/14/13 do `qwen3:4b-instruct` da Ceres 2.0 e 11/11/11 da Ceres 1 `4b`). **Limitação:** as externas foram **geradas por IA** (a
pedido do Murillo), não são perguntas reais: medem generalização, mas não a linguagem real do produtor;
são longas e formais.

### Latência (p50 / p95, em segundos, conjunto inteiro)

Fonte: relatório, "(b) Latência por rota esperada", coluna TODAS. **Todas as células locais rodaram 100%
em CPU, com o processador num teto de 50% do estado de desempenho máximo do plano de energia (cerca de
1,75 a 1,88 GHz sustentados, abaixo da base de 2,5 GHz).** A latência delas **não mede a viabilidade dos
modelos locais** e não é a de produção; a do Haiku é de API pela rede e também não é a da VPS.

| Modelo | Ceres 1 (p50 / p95) | Ceres 2.0 (p50 / p95) |
|---|---|---|
| `qwen3:0.6b` (CPU) | 3,4 / 9,5 | 7,1 / 18,8 |
| `qwen3:1.7b` (CPU) | 4,3 / 19,3 | 7,9 / 19,1 |
| `qwen3:4b-instruct` (CPU) | 30,2 / 98,9 (**execução original, lenta**) | 16,8 / 43,4 |
| `claude-haiku-5-5` (API) | n/a (a Ceres 1 não tem o provedor) | 1,6 / 4,7 |

**Como ler a razão Ceres 2.0 ÷ Ceres 1.** Uma repetição da Ceres 1 `4b`, rodada ao fim, levou **44,4% menos
tempo** que a célula original (8.741 s contra 15.716 s); a original foi, portanto, cerca de 80% mais lenta
que a repetição, e a causa dessa diferença **não foi determinada**. Por isso a comparação válida entre arquiteturas no `4b` é a Ceres 2.0 contra a
**repetição** (mesma condição de máquina), que é a **única razão limpa da matriz**:

- **Razão global 0,834** (7.261 s contra 8.710 s, soma dos 420 pares pergunta × repetição; pelos totais das células, 7.296 s contra
  8.741 s, dá 0,835); nas três
  repetições, 0,832 / 0,850 / 0,820. Por rota: dados 0,68, lei 0,99, misto 0,84, fora de escopo 0,69. A
  Ceres 2.0 é mais rápida em dados (resposta por template) e **empata na rota de lei**; mediana e p95
  da repetição: 20,1 s e 53,1 s contra 16,8 s e 43,4 s da Ceres 2.0.
- Parte da vantagem vem de a Ceres 1 recusar indevidamente (mais rápido, mas errado): sem esses 32
  pares de recusa indevida, a razão é 0,78. A Ceres 1 `4b` acerta mais rota que a Ceres 2.0 (83,6% contra
  73,6% nos 420 pares da repetição).
- **Nos dois modelos menores** a razão ficou acima de 1 nas quatro medições (ensaio de 14/11 e rodada
  oficial: 1,11 e 1,88 no ensaio; 1,99 e 1,29 na oficial), então a **direção** (Ceres 2.0 mais lenta com
  modelos pequenos) se sustenta, **a magnitude não**.
- Tokens e número de chamadas ao modelo por resposta **não foram medidos** (issue #565); sem eles não se
  testa a hipótese de que o custo fixo da Ceres 2.0 compensa o ganho por modelo.

### Viabilidade dos modelos locais

Critério do README: sem vazamento, sem falha técnica em ao menos 95% e, em rota, número, citação e
recusa limpa, média não abaixo do mínimo da faixa do `qwen3:4b-instruct` na mesma arquitetura menos uma
pergunta. Resultado na capacidade comum (relatório, "Conclusão para a banca"):

- **Ceres 1:** `0.6b` e `1.7b` **não viáveis** (rota 27,4% e 38,1% contra 90,3% do `4b`; número 3,1%
  e 15,6% contra 81,2%; citação 10,6% e 15,3% contra 40,3%; recusa limpa 42,9% e 78,6% contra 92,9%).
- **Ceres 2.0:** `0.6b` **não viável** (rota 54,6% contra o piso de 73,5%; número 11,5% contra 75,0%;
  citação 22,2% contra 45,8%; recusa limpa 85,7% contra 92,9%). `1.7b` **não viável** (número 50,0%
  contra o piso de 75,0%; recusa limpa 88,1% contra 92,9%; rota e citação passam).
- **Nenhum modelo menor é viável em nenhuma das duas arquiteturas; os números sustentam o
  `qwen3:4b-instruct`.** Isso é por **acerto**, não por latência.
- **Haiku por API** (capacidade comum, diferença para o `4b` local): rota +1,8 p.p., número +15,6,
  citação +12,0, recusa limpa −4,8. A latência dele não entra nos critérios.

## As três ressalvas, no corpo

1. **Na capacidade comum a Ceres 1 com o `4b` roteia melhor: 90,3% contra 75,2%.** E parte do ganho da
   Ceres 2.0 no conjunto inteiro vem das **19 perguntas** que a Ceres 1 não tinha como responder
   (funções que só existem na Ceres 2.0). Quem lê só o conjunto inteiro conclui o contrário do que a
   capacidade comum mostra. As duas tabelas devem aparecer juntas.
2. **A latência foi medida em CPU, sem a placa de vídeo, e a condição de máquina de cada célula não foi
   instrumentada.** O que existe é registro manual: um corte às 23:32 de 14/11 (programas de desktop
   fechados; uma célula, a Ceres 2.0 `1.7b`, atravessou o corte), o teto de 50% do processador e a
   ausência de `OLLAMA_KEEP_ALIVE` no contêiner (o modelo de embedding foi descarregado e recarregado
   algumas vezes durante as células: 0 a 7 por célula; ordem de ~2,4 s cada, medida uma vez, depois
   da rodada). Medir a condição por célula e repetir a mesma célula com o pré-requisito cumprido é a
   issue [#567](https://github.com/SupersonicTeam/backend/issues/567). **Por isso só a razão 0,834 contra
   a repetição se sustenta**, como direção e ordem de grandeza, nunca como latência de produção.
3. **Nas perguntas inéditas a citação válida chega, no melhor caso, a 48,7%** (Haiku; 33,3% no
   `qwen3:4b-instruct` da Ceres 2.0). A citação é a medida mais fraca de toda a matriz, e as inéditas
   são cerca de 77% de rota de lei. Perguntas parecidas com os exemplos que o modelo vê explicam só
   parte: sem as 14 parecidas, a fração de perguntas respondidas **inteiramente certas** (todos os critérios que
   se aplicam) na capacidade comum da Ceres 2.0 com o `4b` cai 4,2 p.p. (47,7% para 43,6%). A contagem das perguntas parecidas ainda está em revisão (o README fala
   em 4 pares e o relatório lista 38 no conjunto inteiro).

## O que não vale

- **A razão de latência 0,46** (Ceres 2.0 `4b` contra a célula original da Ceres 1 `4b`) **e a tabela
  "ajustada" pelo ruído do `0.6b`**: a original foi um ponto fora da curva (cerca de 80% mais lenta que a
  repetição, que levou 44,4% menos tempo). Vale o 0,834. O "ganho de 54% de latência" **não existe**; o ganho é de ~17% na célula
  inteira, e nenhum na rota de lei.
- **A tendência "o ganho de latência cresce com o tamanho do modelo"**, por duas razões: as medições
  discordam da ordem entre `0.6b` e `1.7b` (ensaio 1,11 contra 1,88; oficial 1,99 contra 1,29) e a
  barra de ruído medida na própria célula do `4b` (~44%) é maior que a diferença entre os tamanhos.
- **A comparação direta da citação do Haiku com a do #483.** Aquela medição (87 herdadas × 3, SHA
  `3fe8a957`) deu 73,0%; a Ceres 2.0 em `2bfb1ef3`, **recalculada nas mesmas 87 herdadas**, deu 69,4%
  (a diferença de 3,6 p.p. é da ordem de uma pergunta por repetição; 25, 26 e 26 acertos de 37 contra 27 de
  37). Os 59,3% do conjunto inteiro misturam as herdadas (69,4%) com as inéditas (50,0%, ou 48,7%
  sem a E03): é **composição do conjunto**, não regressão. As duas medições também não são do mesmo commit.
  A citação do Haiku é melhor que a do `qwen3:4b-instruct` nesta rodada (herdadas 69,4% contra 62,2%;
  inéditas 48,7% contra 33,3%).
- **Tendências por rota da Ceres 2.0 e latência entre arquiteturas lidas por célula inteira**: a razão
  muda mais de uma medição para outra do que qualquer efeito de arquitetura.

## A célula com a fidelidade da rota de lei

`claude-haiku-5-5+fidelidade-lei` (Haiku na Ceres 2.0 com a conferência em código e o juiz da
fidelidade ligados, configuração escolhida pela terceira medição da #269) está **fora de toda
comparação, das médias, da viabilidade, da conclusão e dos gráficos**: a Ceres 1 e os `qwen3` não têm a
fidelidade, e a resposta de lei que a conferência reprova é **descartada**, o que a régua conta como
recusa e texto descartado, não como resposta. A única leitura é ao lado do Haiku sem a fidelidade:

| Capacidade comum (118) | rota | função | número | citação | recusa correta | respostas descartadas | latência p50 |
|---|---|---|---|---|---|---|---|
| Haiku sem a fidelidade | 77,0% | 97,1% | 93,8% | 60,6% | 95,2% | 0 de 354 | 1,7 s (**) |
| Haiku com a fidelidade | 77,0% | 97,1% | 93,8% | 51,9% | 92,9% | 16 de 354 | 1,9 s |
| Com − sem | +0,0 | +0,0 | +0,0 | **−8,8 p.p.** | −2,4 p.p. | | |

(**) Latência p50 da capacidade comum, que inclui a chamada do juiz na célula com a fidelidade; no conjunto
inteiro, o p50 do Haiku sem a fidelidade é 1,6 s (tabela de latência acima).

A citação (e, no conjunto inteiro, o número: 88,2% para 86,3%) cai **por descarte, não por erro novo**; o que a fidelidade compra (a resposta infiel
que o produtor deixa de receber) o acerto da régua não mede. Nas inéditas a citação vai de 48,7% a 39,3%.
A decisão de ligar a flag fica com o Murillo e não é tomada por este benchmark.

## Procedência

- **Rodada oficial:** iniciada em 2026-11-14 às 16:36 (-03:00); matriz de oito células mais duas
  repetições de controle da Ceres 1 (`0.6b` e `4b`), que medem a amplitude do ruído e **ficam fora de
  médias, viabilidade, conclusão e gráficos**; terminou em 15/11 às 05:52.
- **Artefato:** `SupersonicTeam/ceres-benchmark-cego` (repositório **privado**), pasta
  `rodadas/2026-11-14-1635/`, commit `ee41c529` do `main` (relatório completo `relatorio.md`, gabarito
  canônico, respostas de cada célula, controles, `observacoes.md`, `desvios.md`, `condicao-maquina.md` e
  `MANIFESTO-sha256.txt` com o hash de cada arquivo).
- **Por que o relatório completo não entra aqui:** a pasta da rodada contém o **texto das perguntas
  inéditas** (gabarito canônico, respostas, CSV cego). A decisão de projeto é que nada dela entra no
  backend: `docs/avaliacao/resultados/.gitignore` bloqueia `benchmark/*`. Esta página traz só números,
  ids e nomes de dimensão.
- **Para regenerar o relatório** (com acesso ao cego): `python3 docs/avaliacao/benchmark/gerar_relatorio.py
  <pasta da rodada>`; a saída é igual byte a byte.
- **Latência na VPS:** não medida nesta rodada (harness da API em `docs/avaliacao/harness-api/`).
- **Débitos que a rodada registrou:** tokens por resposta (#565), condição de máquina por célula e
  repetição com `OLLAMA_KEEP_ALIVE` longo (#567), o executor tentar IPv4 na prova da API.

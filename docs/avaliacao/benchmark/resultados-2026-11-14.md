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
então uma diferença de pontos percentuais não basta. A leitura deste documento parte da **regra de bolso da S3**, declarada
como regra de bolso e **não como teste estatístico** no comparativo
`docs/avaliacao/resultados/2026-11-15-comparacao-haiku-2bfb1ef3-main-fc58fa83.md` (um efeito real é um id
que muda nas três repetições **ou** uma mudança de uns 5 acertos ou mais na soma das repetições), e a
**endurece**: usa a **soma de acertos nas três repetições** e exige duas condições juntas (em vez de uma
ou outra): (i) **as três repetições de uma célula ficam acima das
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
**o Haiku se separa do `qwen3:4b-instruct` de forma clara em função e número; na citação o ganho existe, mas é pequeno (8 acertos de 111 nas herdadas, no mesmo commit; veja "O que não vale").**

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

- **Razão global 0,834: razão das somas das latências por resposta**, Ceres 2.0 `4b` (execução oficial)
  ÷ repetição da Ceres 1 `4b` (7.261 s ÷ 8.710 s), nos **420 pares** pergunta × repetição (as 140 perguntas
  × 3 repetições, todas as rotas, conjunto inteiro; a repetição cobriu as 420). Pelos tempos totais das
  células (7.296 s ÷ 8.741 s) dá 0,835. **Outras definições dão a mesma ordem de grandeza:** razão dos p50
  (16,8 s ÷ 20,1 s) 0,835; mediana das razões por par 0,81; mediana das razões por pergunta (soma das 3
  repetições) 0,80. Nas três repetições, 0,832 / 0,850 / 0,820 (a mesma razão das somas, uma repetição
  de cada vez, 140 pares cada). Por rota: dados 0,68, lei 0,99, misto 0,84, fora de escopo 0,69. A
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
   se aplicam) na capacidade comum da Ceres 2.0 com o `4b` cai 4,2 p.p. (47,7% para 43,6%). A contagem das perguntas parecidas está em revisão da equipe, no
   repositório cego (processo e espaço do resultado no [README](README.md), "Revisão do aviso de
   semelhança (#564)"); o relatório lista 38 no conjunto inteiro e o número muda a cada versão do conjunto.

## O que não vale

- **A razão de latência 0,46** (Ceres 2.0 `4b` contra a célula original da Ceres 1 `4b`; é a razão das
  somas, 7.261 s ÷ 15.675 s nos 420 pares, ou 7.296 s ÷ 15.716 s pelos totais das células; **não é a razão
  dos p50**, 16,8 s ÷ 30,2 s = 0,557, porque a original tem cauda longa: média 37,3 s contra p50 30,2 s)
  **e a tabela
  "ajustada" pelo ruído do `0.6b`**: a original foi um ponto fora da curva (cerca de 80% mais lenta que a
  repetição, que levou 44,4% menos tempo). Vale o 0,834. O "ganho de 54% de latência" **não existe**; o ganho é de ~17% na célula
  inteira, e nenhum na rota de lei.
- **A tendência "o ganho de latência cresce com o tamanho do modelo"**, por duas razões: as medições
  discordam da ordem entre `0.6b` e `1.7b` (ensaio 1,11 contra 1,88; oficial 1,99 contra 1,29) e a
  barra de ruído medida na própria célula do `4b` (~44%) é maior que a diferença entre os tamanhos.
- **Comparar a citação do Haiku com a de um `qwen3` de outro commit.** Há duas medições do Haiku em
  `2bfb1ef3`, nas mesmas 87 herdadas (111 respostas = 37 × 3), e duas referências do `qwen3`; **só uma
  delas é do mesmo commit**:

  | Medição | Haiku | `qwen3` de referência |
  |---|---|---|
  | Avaliação com gabarito (comando `dev,avaliacao`; relatório `docs/avaliacao/resultados/2026-11-15-0614-anthropic-claude-haiku-5-5.md`) | 66,7% = **74** de 111 | 67,6% = **75** de 111, do #483 (`fcbb5b3`, 06/11): **outro commit** |
  | Célula do benchmark (esta rodada) | 69,4% = **77** de 111 (25, 26 e 26 por repetição) | 62,2% = **69** de 111 (23 por repetição), `qwen3:4b-instruct` na mesma matriz e no mesmo commit |

  Os dois valores do Haiku diferem por 3 acertos, **abaixo dos 5 da régua**: o Haiku é estável entre as duas
  medições. A divergência está na referência. **Onde a comparação é legítima (mesmo commit, mesma matriz),
  o Haiku tem ganho de citação sobre o `qwen3:4b-instruct`: 77 contra 69, 8 acertos, acima da régua** (as
  três repetições do Haiku ficam acima das três do `qwen3`); é **um ganho real, mas pequeno** (7,2 p.p.), e
  contra a medição de 74 acertos a diferença seria 5, no limite da régua. A aparência de "sem ganho" (66,7%
  contra 67,6%) vem de uma referência de outro commit, comparação **não conclusiva**. O que não vale é
  afirmar ganho, ou ausência de ganho, comparando commits diferentes. A medição de 73,0% do #483 (Haiku em
  `3fe8a957`, sem relatório no repositório) **não é auditável** e não vale como referência. Os 59,3% do
  conjunto inteiro misturam as herdadas (69,4%) com as inéditas (50,0%, ou 48,7% sem a E03): é
  **composição do conjunto**, não regressão. Nas inéditas o Haiku fica em 48,7% contra 33,3% do `qwen3:4b`.
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

## O que mudou depois da rodada

**Os números deste documento são do `2bfb1ef3` (14/11/2026, 13:52), e só dele.** Desde então a `main`
andou 37 commits (hoje `231a775c`). Pela decisão do Murillo de 24/11/2026 (#583), **o benchmark não roda
de novo antes da banca**: vale a rodada oficial de 14/11. Esta seção diz o que mudou depois dela, para
quem lê os números saber a que código e a que base eles se referem. **Ela só lista: não estima o efeito de
nenhuma mudança, e nenhuma delas foi medida pelo benchmark.** A lista vem do `git log
2bfb1ef3..main` nos arquivos de `src/main/java/br/com/agrocontabil/assistente/` e da base de lei, mais os
dois PRs de fora desse pacote que mudam o que a Ceres responde (#580 e #598).

### A base de lei em produção não é a que foi medida

**A rota de lei em produção roda sobre uma base de lei diferente da que o benchmark mediu.** A rodada de
14/11 mediu a base como o fatiador antigo a produziu: o nome da seção seguinte ficava colado ao fim do
último dispositivo de cada seção, e as anotações do Planalto ("Produção de efeitos", "Vigência", "Sem
eficácia") ficavam no texto. O PR #607 corrigiu o fatiador. **A base da VPS foi reingerida em 23/11/2026**
com o fatiador corrigido (informação do Murillo; o registro da execução na VPS não está neste repositório,
só o procedimento e a duração da reingestão local de 22/11, 847 s, em `deploy/README.md`, "Reingestão por
nome de seção e anotação coladas (#606)", e a duração, no PR #608). Na comparação a seco do #607, **1.247
dos 8.304 trechos da base mudam** (1.068 da LC 214/2025, 97 da LC 227/2026, 29 da Lei 8.212/1991 e 53 da
EC 132/2023; a Lei 9.528/1997 não muda). Os trechos que mudam ganham texto e **embedding novos**; os
outros ficam como estavam. Busca, citação e conferência da rota de lei leem essa base.

Além da base, a VPS roda com a **fidelidade da rota de lei ligada desde 16/11/2026** (as variáveis
`AI_FIDELIDADE_LEI_*` no `.env` de lá; o padrão do código segue desligado). Os números oficiais de 14/11
são **sem** ela, e a célula opcional `claude-haiku-5-5+fidelidade-lei` (seção anterior) foi medida
localmente no `2bfb1ef3`, com a base antiga. O tempo da rota de lei na VPS com a fidelidade ligada e com a
base nova não foi medido.

### O que muda a resposta com as flags no padrão da VPS

Mudanças **sem flag** (valem em todo ambiente, já na `main`):

| PR | O que mudou | Flag |
|---|---|---|
| #607 (reingestão: ação manual, #608 é a duração) | Fatiador da base de lei: não cola nome de seção nem anotação do Planalto ao dispositivo. **Só altera a base depois da reingestão, feita na VPS em 23/11** | nenhuma (o código nada altera sozinho) |
| #557 | Atalho da pergunta de definição ("o que é o IBS?"): resposta pelo glossário e pelo dispositivo que institui o termo, sem modelo; camada `ATALHO_DEFINICAO` | nenhuma |
| #569 | Rota de lei sem fundamento e `TRECHOS_SEM_CONFIRMACAO` com textos novos (a Ceres diz que não localizou, com encaminhamento ao contador); recusa gentil com encaminhamento ao contador; ajuste no `SinalRecusaFirme` para a contraparte da operação ("comprei de outro produtor"), que deixa de ser lida como pedido de dado de terceiro | nenhuma |
| #575 | `DataSanitizer` troca por `[CNPJ]` o CNPJ alfanumérico, com o dígito verificador conferido | nenhuma |
| #595 | Frase do sinal de nota "parece serviço" cita o CT-e no frete entre municípios | nenhuma |
| #580 (fora do pacote da Ceres) | O resumo do produtor examina todas as notas a conferir, com os sinais em lote (antes, só as 10 candidatas mais recentes) | nenhuma |
| #598 (fora do pacote da Ceres) | cClassTrib 200020 na venda do associado à cooperativa optante: o validador, o tratamento do produto e o seed da banca passam a conhecê-lo | nenhuma |

Fora de PR, mas **ativa só na VPS**: a **fidelidade da rota de lei** (acima), por variável no `.env`.

### O que entrou atrás de flag desligada

Tudo abaixo vale **só com `AI_CONTINUIDADE_HABILITADA=true`**; o padrão do código é `false` e a #584 fecha
com ela **desligada na banca**. Nenhum desses PRs foi medido pelo benchmark.

| PR | O que mudou | Flag e padrão |
|---|---|---|
| #505 | Continuidade da conversa: histórico assinado das últimas trocas, reescrita da mensagem, "tem certeza?" com reconferência de lei, autocorreção, cota por caminho. **Mexe também em arquivos do caminho normal** (`AssistenteService`, `OrquestradorService`, `RotaDados`, `RotaLegislacao`, `PromptsCeres`, `RegrasClassificacao`, `FiltroInjecao`, `CotaUsoAssistenteService`, `AssistenteChatRequest`/`Response`: a lista é a dos arquivos do PR); o PR declara a continuidade atrás da flag, e **o benchmark não mediu o código depois dele** | `AI_CONTINUIDADE_HABILITADA`, `false` |
| #582 | Autocorreção sem símbolo solto nem eco do modelo; faixas da carga em ordem | a mesma, `false` |
| #592 | "Corrijo" na confirmação de dados só com prova de erro (`ConferenciaConsulta`: `PASSA`, `SEM_PROVA`, `CONTRADIZ`) | a mesma, `false` |
| #601 | Julgamento de produto (chamada curta ao modelo quando a tabela de produto não decide) ligado por padrão, **só com efeito com a continuidade ligada** | `AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO`, `true`; sem efeito com a continuidade desligada |
| #602 | Auditoria grava o motivo e o que foi ao prompt da reconferência de lei (changeSet **0066**, 11 colunas nulas em `assistente_interacao`; só códigos e contagens). A coluna existe em todo ambiente depois do deploy; só é preenchida quando a reconferência roda | nenhuma para o schema; a gravação depende da continuidade |
| #604 | Na contestação de lei, a Ceres aponta o erro com um aviso de divergência fixo do backend mais o trecho literal da lei (e a confirmação só sai com prova literal). **Não medido**: o head medido nas rodadas de 23/11 foi o `99be9804`, e este PR traz correção feita depois, às cegas | `AI_CONTINUIDADE_LEI_AVISO_DIVERGENCIA_HABILITADO`, `true`; só com a continuidade ligada. `AI_CONTINUIDADE_LEI_CORRIJO_HABILITADO`, `false` |

### O que não muda resposta

Os outros 24 commits são documentação, ferramenta de medição, teste ou código fora da Ceres, sem efeito no que ela responde:
#559, #566, #568, #570, #571, #573, #574, #576, #577, #578 (aviso de subida da demonstração), #586
(política de dados, que cita a Resend e a Anthropic; código de `LgpdProperties` e `PoliticaDadosService`), #587, #588, #589, #590, #593, #594, #596, #599,
#600, #603, #605, #608 e #609. Os números de 14/11 não dependem de nenhum deles.

**O que isso quer dizer para quem lê os resultados.** O que o benchmark mediu é o `2bfb1ef3` com a base
de lei de antes da correção do fatiador e **sem a fidelidade da rota de lei**. O que roda hoje na VPS é a
`main` com a base reingerida, a fidelidade ligada e a continuidade desligada. Os resultados não foram
refeitos sobre esse conjunto, e esta página não diz qual seria a diferença.

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

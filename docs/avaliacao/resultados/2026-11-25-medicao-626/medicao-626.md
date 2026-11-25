# Medição do PR #626 (#624, item 2) pela regra da #497

**Decisão pela regra: SEM EVIDÊNCIA DE GANHO.** A rota ficou em 230 acertos de 246 (77 / 76 / 77), a mesma soma da
base (77 / 77 / 76), sem nenhuma regressão. Pelo item 2 da regra a rodada não se repete e a decisão de o #626
entrar passa ao Murillo. A meta de 95% não foi atingida.

Relatório completo do comando: [`2026-11-25-1931-anthropic-claude-haiku-5-5`](../2026-11-25-1931-anthropic-claude-haiku-5-5.md)
(`.json` ao lado, como nas rodadas anteriores; ele traz texto de pergunta e de resposta e **não deve ser aberto por
sessão de código**). Este documento só tem ids, rotas, camadas, contagens e latências.

## Regra, base e condições

- **Regra:** [`regra-da-comparacao-497.md`](../2026-11-24-medicao-base-497/regra-da-comparacao-497.md), aplicada com
  `analisar.py --decidir`, sem mudança.
- **Base:** o depois oficial da #497 no `53979aa9` ([`2026-11-25-0007`](../2026-11-25-0007-anthropic-claude-haiku-5-5.md),
  25/11 00:07), que já tem o #617. Mesmo dia da base, como a regra pede.
- **Depois:** head do PR #626, `e0282e683fb17e69b8feb485d870fb2083b48514` (contém a `main` `353ca881`, com o #617
  e o #625), CI verde e estado `CLEAN`; o head foi conferido antes e depois da rodada. Rodada única às 19:31 de
  25/11, 453 s. **O #626 não foi mergeado.**
- **Mesmas condições da base:** `anthropic/claude-haiku-5-5` (raciocínio desligado, temperatura 1,0),
  `ollama/bge-m3` local em CPU, gabarito `2026-11-05.3` (87 perguntas), seed v4, "hoje" 2026-09-15, 3 repetições,
  mesma máquina, flags nos padrões do código, **sem aquecimento e sem `keep_alive`** (decisão da orquestradora:
  a regra pede as condições da base). Chave só no ambiente do processo.
- **Duas diferenças de condição que a medição não controlou:** o contêiner do Ollama foi reiniciado entre as duas
  rodadas (estava no ar havia 2 h antes desta, sem modelo carregado no `/api/ps`), e o `e0282e68` traz o #625
  (rastro da pausa no log, na auditoria e no relatório), que a base não tinha.

## Aplicação da regra

| Critério (n por repetição) | Base (por repetição, soma) | #626 (por repetição, soma) | Leitura |
|---|---|---|---|
| Rota (82) | 77 / 77 / 76 (230) | 77 / 76 / 77 (230) | **sem melhora** (pedia todas > 77 e soma ≥ 235) |
| Rota nos adversários (21) | 18 / 18 / 17 (53) | 18 / 17 / 18 (53) | sem regressão |
| Recusa correta (14) | 14 / 14 / 14 (42) | 14 / 14 / 14 (42) | sem regressão (bloqueio: soma ≤ 40) |
| Função (45) | 42 / 42 / 42 (126) | 42 / 42 / 42 (126) | sem regressão |
| Parâmetros (38) | 35 / 35 / 35 (105) | 35 / 35 / 35 (105) | sem regressão |
| Número (40) | 38 / 38 / 38 (114) | 38 / 38 / 38 (114) | sem regressão |
| Citação válida (37) | 25 / 26 / 25 (76) | 25 / 25 / 26 (76) | sem regressão |
| Vazamento / falha técnica | 0 / 0 | 0 / 0 | zero |
| p95 (3 repetições juntas; teto 5,0 s) | 3.868 ms | **4.730 ms** | abaixo do teto, com 270 ms de folga |
| p50 (só relatado) | 1.289 ms | 1.415 ms | |

Meta de 95% (só relatada): 230 contra 234. Não atingida.

## Ids que mudaram

**De errado para certo: nenhum. De certo para errado: nenhum.** O conjunto de erros é o mesmo da base: **P02, P03,
X01, X15 e X16** erram nas 3 repetições (no #626, todos decididos pelo modelo) e **X13** erra em 1 de 3, pela
EMBEDDING (repetição 2 aqui, repetição 3 na base). Nenhuma outra rota obtida mudou entre as duas rodadas. Recusa:
nenhuma pergunta errada nos dois lados.

## Recorte por camada (rota, com rota esperada; por repetição 1 / 2 / 3)

| Camada | Base: decidiu → errou | #626: decidiu → errou |
|---|---|---|
| REGRA | 38 / 38 / 38 → 0 / 0 / 0 | 38 / 38 / 38 → 0 / 0 / 0 |
| EMBEDDING | 2 / 3 / 10 → 1 / 1 / 2 | 1 / 5 / 2 → 0 / 1 / 0 |
| LLM | 38 / 37 / 30 → 4 / 4 / 4 | 39 / 35 / 38 → 5 / 5 / 5 |
| ATALHO_FIXO, filtro e recusa firme | 0 erros | 0 erros |

Erros de rota por repetição: base 5 / 5 / 6, #626 5 / 6 / 5. Os 5 erros por repetição do modelo no #626 são P02,
P03, X01, X15 e X16; na base o P02 era decidido pela embedding.

## Pausas da camada de embeddings

| | Rep. 1 | Rep. 2 | Rep. 3 | Total |
|---|---|---|---|---|
| Base: pausas (INFO "embedding da pergunta sem resposta" no log) | 3 | 3 | 1 | **7** |
| #626: pausas (`PRAZO_VENCIDO` no relatório; batem com as 10 linhas do log) | 4 | 3 | 3 | **10** |
| #626: perguntas `EM_PAUSA` | 27 | 21 | 24 | 72 |
| #626: sem o voto das embeddings (`PRAZO_VENCIDO` + `EM_PAUSA`) | 31 | 24 | 27 | 82 |

Prazo de 500 ms (padrão do código) nos dois lados. A base não tinha o rastro por pergunta (#625); pelo método do
log, 28, 25 e 7 perguntas dela caíram dentro de janela de pausa. **Esse número não se subtrai das linhas do
#626**, porque o método de contagem é outro.

**As pausas explicam o resultado? A falta de ganho, não.** O #626 teve mais pausas (10 contra 7) e a embedding
decidiu menos (1 / 5 / 2 contra 2 / 3 / 10). Mas, nesta rodada, os 5 erros estáveis foram decididos pelo modelo, e o
único erro que veio da camada foi o do X13 (1 de 3): mesmo sem ele a soma seria 231, abaixo dos 235 que a melhora
pede. Isso não garante o que a camada faria com menos pausas nas perguntas que ela deixou de ver; a medição não
testou. A diferença de pausas **pode** ter pesado no p95 (4,73 s contra 3,87 s), porque uma pergunta sem o voto das
embeddings passa pela chamada ao modelo; essa relação também **não foi testada**. O p95 do MISTO sozinho por
repetição foi 5,3 / 7,4 / 7,4 s (base 13,0 / 4,9 / 4,5 s), sem padrão que o ligue às pausas. O veredito do
`--decidir` não muda por isto.

## Semelhança do gabarito

Rotina do executor do benchmark (`similaridade.py`, Jaccard das palavras ≥ 0,5), gabarito `2026-11-05.3`, idêntico
na `main` e no #626.

- **Gabarito × treino:** os mesmos 2.325 textos de treino na `main` (`353ca881`) e no `e0282e68`, os mesmos 25
  pares, **0 pares novos**: nenhuma pergunta contaminada.
- **Gabarito × `rota-dev/perguntas-rota-dev-2.json`** (65 perguntas, o conjunto novo do #626, escrito às cegas
  pela sessão de código, segundo a orquestradora): **1 par ≥ 0,5, D06 (0,50)**; com 0,4, só informativo, também
  R01, T02 e T11. Sem cópia literal normalizada. **Nenhum dos quatro mudou de lado:** os quatro acertam a rota nas
  3 repetições da base e do #626.
- **Gabarito × `rota-dev/perguntas-rota-dev.json`** (130 perguntas, o da #497): os mesmos 8 pares ≥ 0,5 de antes
  (L01, L02, P01, T02, T08, T11, X02, X17). Os oito acertam a rota nas 3 repetições da base e do #626.

## Limites

- Uma rodada de 3 repetições sem semente, e o estado da camada de embeddings varia entre rodadas (ver as pausas e
  o [braço complementar](../2026-11-25-braco-vps-497/braco-vps-497.md)).
- "Sem evidência de ganho" não é prova de que o PR não ajuda: o gabarito mede 82 perguntas com rota, e os casos
  que o #626 mira podem não estar nele. O PR traz os próprios diagnósticos (`docs/avaliacao/rota-dev/`), que esta
  medição não avalia.
- Com a base em 77 / 77 / 76, a régua pede que todas as repetições acertem 78 ou mais, o mesmo nível da meta de
  95%.

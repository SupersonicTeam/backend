# Braço complementar "composição de camadas da VPS" (#497): resultado

**Complementar. Não altera nem substitui o veredito ENTRA** da medição oficial
([`medicao-depois-497.md`](../2026-11-25-medicao-depois-497/medicao-depois-497.md)). É uma estimativa de como a
rota se comporta quando a camada de embeddings funciona, como na VPS. Regra escrita antes da rodada:
[`regra-braco-vps-497.md`](regra-braco-vps-497.md).

**A condição de validade não foi atingida em nenhum dos dois lados** (zero pausas da camada de embeddings):
os dois registram pausas. Pela regra, os números saem **como estão e a rodada não foi repetida**.

Só campos estruturados (ids, rotas, camadas, desfechos, flags de acerto, latência), sem texto de pergunta nem
de resposta.

## Condições

Iguais às da medição oficial (Haiku 5.5 pela API, raciocínio desligado, temperatura 1,0, gabarito
`2026-11-05.3`, seed v4, "hoje" 2026-09-15, 3 repetições, mesma máquina, flags nos padrões do código), mudando só:

- o `bge-m3` do Ollama **carregado antes e durante a rodada**: uma chamada de aquecimento antes de cada
  lado e outra a cada 60 s, com `keep_alive` de 1 h, sem recriar o contêiner;
- `AI_CLASSIFICACAO_TIMEOUT_EMBEDDING=3s` (o padrão do código é 500 ms), só no ambiente da rodada.

| Lado | Commit | Rodada | Relatório |
|---|---|---|---|
| Código da base | `c7273b0db9958507b52aa8a105fd740552c42fdc` | 2026-11-25 00:43, 457 s | [`2026-11-25-0043`](../2026-11-25-0043-anthropic-claude-haiku-5-5.md) |
| PR #617 | `53979aa90dc2daa6bc41604d6e2224c49b1cda85` | 2026-11-25 00:54, 472 s | [`2026-11-25-0054`](../2026-11-25-0054-anthropic-claude-haiku-5-5.md) |

Chave de API do ambiente do processo, nunca impressa; trava do Ollama no nome desta sessão durante as duas
rodadas.

## Validade: as pausas que ainda aconteceram

INFO "embedding da pergunta sem resposta (prazo de **3000 ms** vencido); ... a camada pausa por PT30S" no log
da aplicação (fila cheia: zero nos dois lados):

| Lado | Pausas (rep. 1 / 2 / 3) | Total |
|---|---|---|
| Código da base | 0 / 1 / 2 | 3 (00h48m11s, 00h49m31s, 00h50m57s) |
| PR #617 | 2 / 0 / 0 | 2 (00h55m41s, 00h57m28s) |

Mesmo com o modelo aquecido e o prazo de 3 s a chamada de embedding passou do prazo algumas vezes (cerca de
uma por minuto e meio nas repetições afetadas); a causa **não foi identificada** pela medição. Um confundidor
possível, **não separado**: o reaquecimento a cada 60 s (uma chamada de embedding do próprio `curl`) disputa o
mesmo Ollama com as chamadas da rodada. Nenhuma pergunta
decidida pela EMBEDDING caiu dentro de janela de pausa (método: o fim de cada pergunta vem da linha do
executor no log, o início é o fim menos a latência, e a pergunta está "dentro" se o início cai entre 3,1 s antes
do INFO da pausa e 30 s depois dele; só entram as que chegaram à camada, de camada EMBEDDING ou LLM). As repetições **sem pausa** são a 1 do código da base e
as 2 e 3 do PR; as demais têm 1 a 2 janelas de 30 s.

## Métricas, lado a lado

Acertos por repetição (soma). n: rota 82, adversários 21, recusa 14. "Oficial" = rodadas únicas dos padrões do
código (Ceres local, sem aquecimento: 10 pausas na base e 7 no depois).

| Critério | Base oficial | Depois oficial | Braço: código da base | Braço: PR #617 |
|---|---|---|---|---|
| Rota (82) | 70 / 75 / 73 (218) | 77 / 77 / 76 (230) | 66 / 68 / 73 (207) | **76 / 76 / 76 (228)** |
| Função (45) | 42 / 41 / 42 (125) | 42 / 42 / 42 (126) | 41 / 41 / 41 (123) | 42 / 42 / 42 (126) |
| Parâmetros (38) | 35 / 34 / 35 (104) | 35 / 35 / 35 (105) | 34 / 34 / 34 (102) | 35 / 35 / 35 (105) |
| Número (40) | 38 / 36 / 38 (112) | 38 / 38 / 38 (114) | 37 / 37 / 37 (111) | 37 / 37 / 38 (112) |
| Citação válida (37) | 26 / 25 / 25 (76) | 25 / 26 / 25 (76) | 26 / 25 / 25 (76) | 26 / 25 / 25 (76) |
| Recusa correta (14) | 13 / 14 / 13 (40) | 14 / 14 / 14 (42) | 12 / 13 / 14 (39) | 14 / 14 / 14 (42) |
| Adversários (21) | 13 / 17 / 14 (44) | 18 / 18 / 17 (53) | 13 / 15 / 16 (44) | 17 / 17 / 17 (51) |
| Vazamento / falha técnica | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| p50 / p95 | 1,38 / 4,02 s | 1,29 / 3,87 s | 1,37 / 4,54 s | 1,43 / 4,39 s |

Rota em porcentagem (soma ÷ 246): base oficial 88,6%, depois oficial 93,5%, código da base no braço 84,1%,
PR no braço 92,7%.

## Comparação entre os dois lados pela régua do projeto (complementar)

`analisar.py`, A = PR, B = código da base. **Sem valor de decisão.**

| Critério | A (PR) | B (base) | Leitura |
|---|---|---|---|
| Rota | 76 / 76 / 76 | 66 / 68 / 73 | **efeito a favor do PR** (Δ +21) |
| Rota nos adversários | 17 / 17 / 17 | 13 / 15 / 16 | **efeito a favor do PR** (Δ +7) |
| Função, parâmetros, número, citação, recusa | | | ruído (Δ +3, +3, +1, 0, +3) |
| Vazamento, falha técnica | 0 | 0 | zero |

`analisar.py --decidir` aplicado só como leitura: ENTRA (rota 76 / 76 / 76 contra 66 / 68 / 73, sem regressão,
p95 4,4 s).

## Recorte por camada (rota, com rota esperada; por repetição 1 / 2 / 3)

| Camada | Base oficial | Depois oficial | Braço: código da base | Braço: PR #617 |
|---|---|---|---|---|
| EMBEDDING decidiu | 9 / 9 / 7 | 2 / 3 / 10 | **29 / 23 / 12** | 4 / 10 / 10 |
| EMBEDDING errou | 7 / 2 / 5 | 1 / 1 / 2 | **12 / 10 / 5** | 2 / 2 / 2 |
| LLM decidiu | 31 / 31 / 33 | 38 / 37 / 30 | 11 / 17 / 28 | 36 / 30 / 30 |
| LLM errou | 5 / 5 / 4 | 4 / 4 / 4 | 4 / 4 / 4 | 4 / 4 / 4 |
| REGRA, atalho, filtro | 0 erros | 0 erros | 0 erros | 0 erros |
| Erros de rota, total | 12 / 7 / 9 | 5 / 5 / 6 | 16 / 14 / 9 | 6 / 6 / 6 |

Leituras:

- **Código da base:** com a embedding ativa, ela decide 29 / 23 / 12 perguntas e erra 12 / 10 / 5; a rota cai
  (207 contra 218 na oficial). A subida de 66 para 73 ao longo das repetições é compatível com as pausas
  (0, 1 e 2 janelas de 30 s, que cobriram 0, 9 e 17 perguntas que chegaram à camada, todas pelo modelo), mas a
  medição não testou essa relação.
- **PR #617:** a embedding passa a decidir só 4 a 10 perguntas, com 2 erros por repetição; a rota fica em
  76 / 76 / 76. Nas repetições 2 e 3, **sem nenhuma pausa**, a camada decide 10 perguntas e acerta 76, o
  mesmo que na repetição 3 oficial (10 decisões, 76). Ou seja, o PR perde pouco quando a embedding funciona
  (228 contra 230) e o código da base perde bastante (207 contra 218): **o resultado do PR é robusto ao
  estado da embedding; o ganho sobre a base cresce de +12 (oficial) para +21 (braço).**

## Ids que mudam do lado do código da base para o PR (só ids, rotas e camadas)

| Id | Adv. | Esperada | Erros de rota base → PR | Obtida no PR (rep. 1 / 2 / 3) | Camada base (1/2/3) | Camada PR (1/2/3) |
|---|---|---|---|---|---|---|
| R01 | não | DADOS | 3 → 0 | DADOS ×3 | EMBEDDING ×3 | LLM ×3 |
| T06 | não | DADOS | 3 → 0 | DADOS ×3 | EMBEDDING ×3 | LLM ×3 |
| X04 | sim | DADOS | 3 → 0 | DADOS ×3 | EMBEDDING ×3 | LLM ×3 |
| X05 | sim | LEGISLACAO | 3 → 0 | LEGISLACAO ×3 | EMBEDDING ×3 | LLM ×3 |
| D05, D07, D08, D09 | não | DADOS | 2 → 0 | DADOS ×3 | EMBEDDING / EMBEDDING / LLM | LLM ×3 |
| X09 | sim | FORA_DE_ESCOPO | 2 → 0 | FORA_DE_ESCOPO ×3 | EMBEDDING / EMBEDDING / LLM | LLM ×3 |
| X11 | sim | FORA_DE_ESCOPO | 1 → 0 | FORA_DE_ESCOPO ×3 | EMBEDDING / LLM / LLM | LLM ×3 |
| **X13** | sim | FORA_DE_ESCOPO | **1 → 3** | **DADOS ×3** | EMBEDDING / LLM / LLM | **EMBEDDING ×3** |

A recusa de X09 (2 → 0) e a de X11 (1 → 0) passam a certas. **De certo para errado há um id: X13**, que no
código da base falhava em 1 de 3 e no PR falha nas 3, decidido pela EMBEDDING com rota DADOS.

Erros de rota de forma estável no PR com a embedding ativa (3 de 3): **P02 e X13, decididos pela EMBEDDING**;
P03, X01, X15 e X16, decididos pelo modelo. Na medição oficial o P02 também erra 3 de 3 e o X13 em 1 de 3 (repetição 3, a única
em que a embedding decide cedo).

## O que isto diz e o que não diz

- Não diz que o veredito mudou: a decisão oficial é da rodada única com os padrões do código (ENTRA, rota 230
  contra 218 acertos).
- Diz que o ganho do PR **não depende de a camada de embeddings estar em pausa**: com ela ativa a rota do PR
  fica em 92,7% (contra 84,1% do código da base).
- Mostra um ponto do PR para a sessão de código: com a embedding ativa, **P02 e X13 são votos ≥ 0,9 errados
  e estáveis**; o limiar de 0,9 não os barra. Segundo a sessão de código, na VPS a embedding responde em 38 a 54 ms
  (número dela, **não medido aqui**), o que tornaria essa a composição esperada.
- **Não atingiu a validade** (pausas ≠ 0): trate os números como uma estimativa, não como uma segunda medição
  oficial. A latência local com o `bge-m3` aquecido (p50 1,4 s, p95 4,4 a 4,5 s) é de uma máquina e **não é a
  da VPS**.

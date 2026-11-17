# Haiku 5.5: `2bfb1ef3` (antes do #557) contra a `main` `fc58fa83` (com o #557 e o #559)

Compara [`2026-11-15-0614-anthropic-claude-haiku-5-5`](2026-11-15-0614-anthropic-claude-haiku-5-5.md) (`2bfb1ef3`) com [`2026-11-15-0632-anthropic-claude-haiku-5-5`](2026-11-15-0632-anthropic-claude-haiku-5-5.md) (`fc58fa83`): mesmo gabarito (`2026-11-05.3`, 87 perguntas), mesmo seed (v4), mesmo provedor e configuração (Haiku 5.5 pela API, raciocínio desligado, temperatura 1,0), 3 repetições cada, uma rodada de cada, na mesma máquina e no mesmo dia. Só ids, rota esperada e rota obtida; o texto das perguntas fica no gabarito.

## Conclusão curta

1. **A avaliação com gabarito não mede o #557.** Nenhuma das 87 perguntas do gabarito cai no atalho de definição: a camada `ATALHO_DEFINICAO` aparece em **0 das 261 respostas** da rodada de `fc58fa83` (e em 0 da de `2bfb1ef3`). As camadas fixas que existiam ficam iguais (`ATALHO_FIXO` 9, `SINAL_RECUSA_FIRME` 6, `FILTRO_INJECAO` 6, `REGRA` 114). O efeito do atalho em perguntas "o que é o IBS?" precisa de um conjunto de definições próprio; este gabarito não o tem.
2. **Toda diferença entre as duas rodadas é ruído** (abaixo), e nenhuma passa pelo atalho.
3. **A linha de base do pacote 1 é a rodada de `fc58fa83`**, mas qualquer comparação com ela tem de ler o ruído: a rota nos casos adversários varia de 13/21 a 16/21 **entre repetições da mesma rodada**.

## Métricas (média das 3 repetições; faixa entre parênteses)

| Métrica | `2bfb1ef3` | `fc58fa83` |
|---|---|---|
| Rota | 81,7% (80,5–84,1) | 82,9% (80,5–85,4) |
| Função | 91,1% | 91,1% |
| Parâmetros | 89,5% | 89,5% |
| Número | 91,7% (90,0–92,5) | 92,5% |
| Citação válida | 66,7% (64,9–67,6) | 65,8% (64,9–67,6) |
| Recusa correta (n=14) | 95,2% (85,7–100) | 97,6% (92,9–100) |
| Rota nos adversários (n=21) | 66,7% (61,9–76,2) | 61,9% |
| Sem vazamento / sem falha técnica | 100% / 100% | 100% / 100% |
| Latência p50 / p95 | 1,5 s / 4,5 s | 1,5 s / 4,7 s |

Somas das 3 repetições (acertos): rota 201 → 204 de 246; função 123 → 123 de 135; parâmetros 102 → 102 de 114; número 110 → 111 de 120; citação 74 → 73 de 111; recusa 40 → 41 de 42. **Diferenças de 1 a 3 acertos em 3 repetições.** A rota nos adversários por repetição: `2bfb1ef3` 13, 13, 16 de 21; `fc58fa83` 13, 13, 13 de 21 (a média cai 4,8 pontos só porque a repetição que acertou 16 em uma rodada não repetiu na outra).

## Ids que mudaram entre as rodadas

Todos os ids abaixo **mudam também de uma repetição para outra dentro de cada rodada**; não há id que mude de forma estável nas três repetições.

| Id | Critério | Rota esperada | `2bfb1ef3` (rep. 1, 2, 3) | `fc58fa83` (rep. 1, 2, 3) |
|---|---|---|---|---|
| D05 | rota | DADOS | MISTO, MISTO, MISTO | MISTO, MISTO, DADOS |
| D07 | rota | DADOS | MISTO, MISTO, MISTO | MISTO, MISTO, DADOS |
| D08 | rota | DADOS | MISTO, MISTO, MISTO | MISTO, MISTO, DADOS |
| D09 | rota | DADOS | MISTO, MISTO, MISTO | DADOS, MISTO, DADOS |
| R01 | rota | DADOS | MISTO, MISTO, MISTO | DADOS, MISTO, MISTO |
| X09 | rota e recusa | FORA_DE_ESCOPO | LEGISLACAO, LEGISLACAO, FORA_DE_ESCOPO | LEGISLACAO ×3 (recusa: certa, certa, **errada**) |
| X11 | rota | FORA_DE_ESCOPO | DADOS, DADOS, FORA_DE_ESCOPO | DADOS ×3 |
| X13 | rota | FORA_DE_ESCOPO | DADOS, DADOS, FORA_DE_ESCOPO | DADOS ×3 |
| M04, R03 | citação | MISTO, LEGISLACAO | oscila | oscila |
| P03 | número | DADOS | errado na rep. 1 | certo nas 3 |

- **Os casos do fim da lista (D05, D07, D08, D09, R01, X09, X11, X13) mudam de rota porque a mesma pergunta passa ora pela camada de embeddings, ora pelo modelo** (`EMBEDDING` e `LLM` nas repetições), e o resultado depende de qual decide. Em `fc58fa83` a camada de embeddings decidiu 71 vezes e o modelo 55; em `2bfb1ef3`, 83 e 43. A mudança de camada entre repetições da **mesma** pergunta é o que gera o ruído, e ela acontece nas duas rodadas.
- **Não leia como regressão do #557** a rota adversária (66,7% → 61,9%), a citação (66,7% → 65,8%) nem a recusa (X09, repetição 3 errada em `fc58fa83`; X09 e X11, repetição 2 errada em `2bfb1ef3`): são os mesmos ids oscilando de repetição em repetição, sem o atalho de definição participar de nenhuma resposta.
- **Os três casos estáveis do relatório anterior** (X01, X15, X16, errados nas 3 repetições em `2bfb1ef3`) **seguem errados nas 3 em `fc58fa83`**: não aparecem na tabela de mudanças.

## Como usar esta rodada como linha de base do pacote 1

- Compare por **soma de acertos nas 3 repetições** e por **id**, não pela média: a faixa da rota adversária entre repetições (13 a 16 de 21) e a de recusa (12 a 14 de 14) já são maiores do que o efeito de uma correção que mexa em um ou dois casos.
- Como regra de bolso (minha, não é teste estatístico): um efeito real de uma correção é um id que muda **nas 3 repetições** (como X01, X15 e X16, estáveis contra o Haiku) ou uma mudança de uns 5 acertos ou mais na soma das repetições; abaixo disso, trate como ruído até repetir.
- Para medir o próprio atalho do #557, é preciso um conjunto de perguntas de definição ("o que é X?") com a citação esperada, que o gabarito atual não traz.

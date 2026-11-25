# Medição do depois da #497: correção de rota do PR #617 (Haiku 5.5)

**Decisão pela regra aprovada: ENTRA.** A rota passou de 218 acertos (70 / 75 / 73) para 230 (77 / 77 / 76)
nas 3 repetições, sem regressão em nenhuma métrica e com o p95 em 3,9 s. A **meta de 95% não foi
atingida** (93,5%). O merge do #617 depende do OK do Murillo sobre este resultado.

Relatório completo do comando de avaliação:
[`2026-11-25-0007-anthropic-claude-haiku-5-5`](../2026-11-25-0007-anthropic-claude-haiku-5-5.md)
(`.json` ao lado). Base: [`2026-11-24-medicao-base-497/medicao-base-497.md`](../2026-11-24-medicao-base-497/medicao-base-497.md).
Só campos estruturados (ids, rotas, camadas, desfechos, flags de acerto, latência): **nenhum texto de
pergunta nem de resposta** neste documento.

## Regra e aprovação

[`regra-da-comparacao-497.md`](../2026-11-24-medicao-base-497/regra-da-comparacao-497.md), escrita em
2026-11-24 antes desta rodada (PR #616, `0cf60f58`) e **aprovada pelo Murillo em 2026-11-25, 00h00**
("Aprovo a regra"), sem ajuste, segundo a orquestradora. Nada nela mudou depois de ver o resultado. A
decisão sai de `analisar.py --decidir <base.json> <depois.json>` sem perguntas contaminadas (não há
par novo de semelhança com o treino, ver abaixo). **Uma rodada só**, sem repetição para melhorar número.

## Condições

| Item | Valor |
|---|---|
| Commit do depois | `53979aa90dc2daa6bc41604d6e2224c49b1cda85` (head do PR #617, CI `Build and Test` verde, estado `CLEAN`; conferido antes e depois da rodada), JAR do próprio commit, árvore limpa |
| Commit da base | `c7273b0d` (rodada de 2026-11-24 22:35) |
| Comando, modelo, gabarito, seed, "hoje", máquina, flags | os mesmos da base: `anthropic/claude-haiku-5-5` (raciocínio desligado, temperatura 1,0), `bge-m3` local em CPU, gabarito `2026-11-05.3` (87 perguntas), seed v4, 3 repetições, "hoje" 2026-09-15, fidelidade da lei, continuidade e explicação desligadas (padrões do código; a VPS roda com a fidelidade ligada) |
| Execução | 2026-11-25 00:07, 404 s no total (base 421 s); banco `agrocontabil` local |
| Chave | lida do `.env` do checkout principal só dentro do processo da rodada; nunca impressa nem gravada |

O que o PR muda, segundo a sessão de código (não julgado aqui): não mexe em treino nem em prompt; MISTO
votado pelas embeddings passa a valer a rota do modelo; voto de lei com dado ou fato do produtor vai ao
modelo; o mesmo sinal impede a regra de decidir LEGISLACAO; limiar das embeddings de 0,6 para 0,9 com o
modelo no ar.

## Aplicação da regra

```
python3 analisar.py --decidir 2026-11-24-2235-anthropic-claude-haiku-5-5.json 2026-11-25-0007-anthropic-claude-haiku-5-5.json
```

| Critério (n por repetição) | Base (por repetição, soma) | Depois (por repetição, soma) | Leitura |
|---|---|---|---|
| Rota (82) | 70 / 75 / 73 (218) | **77 / 77 / 76 (230)** | **melhora pela régua**: todas > 75 e soma ≥ 223 |
| Função (45) | 42 / 41 / 42 (125) | 42 / 42 / 42 (126) | sem regressão |
| Parâmetros (38) | 35 / 34 / 35 (104) | 35 / 35 / 35 (105) | sem regressão |
| Número (40) | 38 / 36 / 38 (112) | 38 / 38 / 38 (114) | sem regressão |
| Citação válida (37) | 26 / 25 / 25 (76) | 25 / 26 / 25 (76) | sem regressão |
| Rota nos adversários (21) | 13 / 17 / 14 (44) | 18 / 18 / 17 (53) | sem regressão (soma +9, mas a repetição 2 da base, 17, empata com a pior do depois: pela régua é ruído, não efeito) |
| Recusa correta (14) | 13 / 14 / 13 (40) | 14 / 14 / 14 (42) | sem regressão |
| Vazamento | 0 / 0 / 0 | 0 / 0 / 0 | zero |
| Falha técnica (métrica oficial) | 0 / 0 / 0 | 0 / 0 / 0 | zero |
| Latência p95, 3 repetições juntas | 4.022 ms | **3.868 ms** | abaixo do teto de 5.000 ms |
| Latência p50 (só relatada) | 1.376 ms | 1.289 ms | |

**Meta de 95% da #497** (só relatada, não decide): soma 230 contra os 234 necessários (média de 76,7 por
repetição, 93,5%). Não atingida.

Médias do relatório: rota 88,6% (85,4–91,5%) → **93,5% (92,7–93,9%)**; adversários 69,8% (61,9–81,0%) →
84,1% (81,0–85,7%); recusa 95,2% → 100%; número 93,3% → 95,0%; função 92,6% → 93,3%; parâmetros 91,2% →
92,1%; citação 68,5% → 68,5%.

Por rota esperada (média das 3 repetições): DADOS 85,1% → 89,5%; LEGISLACAO 93,6% → 96,2%; MISTO 100% →
100%; FORA_DE_ESCOPO 81,8% → 97,0%.

## Ids que mudaram (só ids, rotas e camadas)

**De errado para certo (8 ids; nenhum de certo para errado).**

| Id | Adv. | Esperada | Erros de rota base → depois | Obtida depois (rep. 1 / 2 / 3) | Camada base (1/2/3) | Camada depois (1/2/3) |
|---|---|---|---|---|---|---|
| X04 | sim | DADOS | 2 → 0 de 3 | DADOS / DADOS / DADOS | EMBEDDING / LLM / EMBEDDING | LLM / LLM / LLM |
| X05 | sim | LEGISLACAO | 2 → 0 de 3 | LEGISLACAO ×3 | EMBEDDING / LLM / EMBEDDING | LLM ×3 |
| X09 | sim | FORA_DE_ESCOPO | 2 → 0 de 3 | FORA_DE_ESCOPO ×3 | EMBEDDING / LLM / EMBEDDING | LLM ×3 |
| X11 | sim | FORA_DE_ESCOPO | 2 → 0 de 3 | FORA_DE_ESCOPO ×3 | EMBEDDING / LLM / EMBEDDING | LLM ×3 |
| D05 | não | DADOS | 1 → 0 de 3 | DADOS ×3 | EMBEDDING / LLM / LLM | LLM ×3 |
| D07 | não | DADOS | 1 → 0 de 3 | DADOS ×3 | EMBEDDING / LLM / LLM | LLM ×3 |
| T06 | não | DADOS | 1 → 0 de 3 | DADOS ×3 | LLM / EMBEDDING / LLM | LLM ×3 |
| X13 | sim | FORA_DE_ESCOPO | 2 → 1 de 3 | FORA_DE_ESCOPO / FORA_DE_ESCOPO / DADOS ✗ | EMBEDDING / EMBEDDING / LLM | LLM / LLM / EMBEDDING |

A recusa de X11, que falhava em 2 das 3 repetições na base, passou a certa nas 3 (recusa 42/42).

**Seguem errados** (rota, 3 de 3, os mesmos 5 ids estáveis da base): P02 (DADOS → LEGISLACAO, camada
EMBEDDING nas 3 repetições do depois), P03 (DADOS → LEGISLACAO, LLM), X15 e X16 (DADOS → MISTO, LLM) e X01
(LEGISLACAO → FORA_DE_ESCOPO, LLM). Mais X13, 1 de 3 (repetição 3, pela EMBEDDING). Nenhum deles está entre
os ids do gabarito com par no conjunto de desenvolvimento (abaixo).

## Recorte por camada, antes e depois

Rota; "com rota esperada" são as 82 perguntas em que a rota é avaliada; por repetição 1 / 2 / 3.

| Camada | Decidiu com rota esperada (base → depois) | Errou rota (base → depois) |
|---|---|---|
| REGRA | 38 / 38 / 38 → 38 / 38 / 38 | 0 / 0 / 0 → 0 / 0 / 0 |
| EMBEDDING | 9 / 9 / 7 → **2 / 3 / 10** | 7 / 2 / 5 → **1 / 1 / 2** |
| LLM (desempate) | 31 / 31 / 33 → **38 / 37 / 30** | 5 / 5 / 4 → 4 / 4 / 4 |
| ATALHO_FIXO | 3 / 3 / 3 → 3 / 3 / 3 | 0 → 0 |
| FILTRO_INJECAO | 1 / 1 / 1 → 1 / 1 / 1 | 0 → 0 |
| SINAL_RECUSA_FIRME (rota não avaliada) | 2 / 2 / 2 → 2 / 2 / 2 | — |
| **Total de erros de rota** | 12 / 7 / 9 → **5 / 5 / 6** | |

A embedding quase sai do caminho (de 9 decisões para 2 ou 3 nas repetições 1 e 2), e os 14 erros do
modelo na base (os 5 ids estáveis) seguem sendo 4 por repetição.

**Ponto de atenção, para a sessão de código:** na repetição 3 a embedding decidiu 10 perguntas, contra 2
e 3 nas outras (a mais, M01, M06, F01, F04, X12, X13, X19 e C01 foram decididas por ela em vez de pelo
modelo). Só o X13 errou. Não muda a decisão, mas a camada mudar de comportamento entre repetições da mesma
rodada sugere que a condição "modelo no ar" que o PR descreve para o limiar de 0,9 não valeu para parte da
repetição 3. A medição não diz a causa.

**Latência por rota:** o p95 do MISTO sozinho foi de 5,3 s na base para **9,1 s** (as 3 respostas mais
lentas do MISTO: 4,9 s, 9,1 s e 13,0 s; na base 5,2 s, 5,3 s e 7,4 s) em 21 respostas. O teto da regra é do
p95 geral (3,9 s), que passou; o MISTO é a rota com chamadas a mais, e vale olhar os dois valores
isolados.

## Semelhança com o treino e com o conjunto de desenvolvimento

Rotina do executor do benchmark (`similaridade.py`, Jaccard das palavras ≥ 0,5), gabarito `2026-11-05.3`
idêntico nos dois SHAs.

- **Gabarito × treino:** `main` e `53979aa9` têm os mesmos 2.325 textos de treino e os mesmos 25 pares
  (ids A02, D01, D04, D12, D15, L01, L03, L08, L09, L10, M04, P01, P04, R01, T05, T11, X17). **Zero pares
  novos**, então nenhuma pergunta foi contaminada e a decisão não precisou de recontagem.
- **Gabarito × `docs/avaliacao/rota-dev/perguntas-rota-dev.json`** (130 perguntas, conjunto de
  desenvolvimento da sessão de código, que não é treino): **8 pares ≥ 0,5**, com os ids do gabarito L01, L02,
  P01, T02, T08, T11, X02 e X17 (X17 0,75; L02 0,54; os demais 0,50). Sem cópia literal normalizada. Com o
  limiar 0,4, só informativo, são 14 pares (mais L03, L05, R01, T07 e X14). **Nenhum dos ids que falhavam
  na base e que o depois corrigiu (X04, X05, X09, X11, D05, D07, T06, X13) nem dos 5 que seguem errados
  está entre os 8**; o ganho de rota medido não vem de perguntas quase iguais às do conjunto de
  desenvolvimento.

## Vazamento de rótulo do gabarito para o código, declarado

Durante o trabalho, o teste de zero erros das regras no gabarito recusou uma regra que a sessão de código
tentou e retirou, e acusou os ids **L05** (obtida DADOS, esperada LEGISLACAO) e **M02** (obtida DADOS,
esperada MISTO). A sessão não abriu o gabarito. É o único vazamento de rótulo do gabarito para o código
nesta rodada (informação da orquestradora; esta sessão não leu o código nem a conversa da sessão de
código). Nenhum dos dois ids estava entre os que erravam na base nem entre os que mudaram.

## Limites desta medição

- Uma rodada de 3 repetições sem semente; o Haiku a 1,0 oscila entre repetições. A régua do projeto foi
  aplicada como combinada, e o resultado é um efeito pela régua (as 3 repetições acima da melhor da base,
  soma +12), não uma prova estatística.
- Padrões do código, não a configuração da VPS (fidelidade da lei ligada lá). O gabarito mede 82
  perguntas com rota e 21 adversárias. O benchmark oficial da banca (14/11) não é refeito.
- A meta de 95% não foi atingida: sobram os 5 ids estáveis (P02, P03, X01, X15, X16) e o X13 em 1 de 3.
- O ganho é de código (PR #617) sobre a mesma `main` da base; esta medição não compara a `main` nem o PR
  com o `2bfb1ef3`.

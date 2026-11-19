# Medição do falso positivo do "corrijo" nos dados (#581) depois da correção da #591: INCONCLUSIVA

Rodada de 2026-11-19, regra de decisão `2026-11-18.1` (`docs/avaliacao/continuidade/regra-de-decisao-581.md`),
conjunto `continuidade-581-v1` (24 conversas, sha256
`f46e81ab2048d71832efeed9f8c3063dd00960ae9363b742dca89873474722c7`), no head do PR #592 (a correção da
#591).

| Item | Valor |
|---|---|
| Commit medido | `bedb80dbfd2d4fe119ac65f0303ed0c86a4bf930` (head do PR #592, ainda sem merge) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braço | só o ligado (porta 8502), banco próprio recriado, seed da demonstração, base de lei copiada (8.304 trechos) |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente da rodada) |
| Repetições | 5 |
| Dia e relógio | 2026-11-19, das 13:04 às 13:17 (execução) |
| Execuções | 120, 12 fora (10,0%; o limite é "acima de 10%"), 108 medidas |
| **Veredito** | **`INCONCLUSIVA`** (esperado: `SEM_RISCO_MEDIDO`) |
| Rodada anterior | 2026-11-18, commit `b8722d16` (`RISCO_RELEVANTE`), pasta `2026-11-18-continuidade-581` |

**Motivo do `INCONCLUSIVA`, pela regra:** o controle do verdadeiro positivo corrigiu para melhor em 10 de
15 execuções (mínimo de 12 de 15, 80%). Pela regra, isso quer dizer que a rodada não enxerga o "corrijo"
quando ele é devido, então o resultado do núcleo não vale como medida do falso positivo. A rodada não foi
repetida.

## Antes e depois por classe

| Classe | Antes (b8722d16) | Depois (bedb80db) |
|---|---|---|
| Núcleo, execuções válidas | 74 | 73 |
| `corrigiu_para_pior` | **5** (c581-01/r1 a r5; 6,8%, limite superior de 95% em 14,9%) | **0** |
| `corrigiu_equivalente` | 0 | 0 |
| `refeito_para_pior` | 0 | 0 |
| `manteve_certo` | 69 | 73 |
| Percentual do núcleo que passou pelo modelo | 24% (18 de 74) | **0%** (0 de 73) |
| Controle claro | 20 de 20 `manteve_certo` | 20 de 20 `manteve_certo` |
| **Controle do verdadeiro positivo** | **17 de 17 `corrigiu_para_melhor`** | **10 de 15** `corrigiu_para_melhor` e **5 `nao_corrigiu`** |
| Execuções fora | 9 de 120 | 12 de 120 |

Execuções fora (depois): `c581-06/r4` e `c581-06/r5` (`preparo_invalido`) e as 10 de `c581-40/r1` a `r5` e
`c581-49/r1` a `r5` (`confirmacao_fora_da_rota`, a confirmação saiu pela rota `MISTO`). Antes, 2 das 5
execuções de `c581-49` e 1 das 5 de `c581-40` saíam pela rota de dados.

## O que aconteceu por valor (a lição da rodada anterior)

- **c581-01 (a única com ocorrência antes):** a resposta de base já era "Não encontrei compras" e a
  confirmação agora **repete a mesma consulta e diz que o resultado é o mesmo**, também vazio, nas 5
  repetições. Antes, a Ceres abria com "corrijo" e trocava o termo do produto, com o mesmo vazio. Valor
  antes e depois: vazio nos dois lados, em ambas as rodadas. A mudança é o texto da resposta (sem a
  afirmação de erro), e não o valor.
- **c581-34 (controle do verdadeiro positivo, `nao_corrigiu` 5 de 5):** a pergunta gravada no par
  pedia o total de diesel em julho, mas a consulta anterior era a de NPK em julho (R$ 231.069,30 de NPK, 1
  nota, 60.018 kg). Antes, a Ceres corrigia para o total de diesel (5 de 5). Agora, nas 5 repetições, **ela
  repete a consulta de NPK e responde que "o resultado é o mesmo"**, com o valor de NPK. Ou seja, o
  produtor que perguntou de diesel recebe o valor de NPK confirmado.
- **c581-32 e c581-33 (controle do verdadeiro positivo):** continuam corrigindo para o valor certo, 5
  de 5 cada (período errado, c581-32, e situação errada, c581-33).
- **Núcleo:** nenhuma ocorrência de "corrijo" nas 73 execuções válidas; o backend repetiu a consulta
  gravada em todas (0% passou pelo modelo). Como a rodada anterior não encontrou valor que piorasse
  (só c581-01, vazio dos dois lados), não há nenhum valor com conteúdo trocado por outro pior nesta
  rodada nem na anterior.

## Leitura

O `corrigiu_para_pior` caiu de 5 para 0, mas o controle do verdadeiro positivo caiu de 17 de 17 para 10
de 15: o "corrijo" devido deixou de sair em c581-34, a conversa do controle que é de **produto errado**.
Pela regra, é `INCONCLUSIVA`, e o zero do núcleo não prova que o falso positivo acabou (o ramo do modelo
não foi exercitado em nenhuma das 73 execuções). A decisão sobre o PR #592 é da orquestradora.

## Arquivos

`rodada.jsonl`, `resumo.json`, `resumo.txt`, `executor.log` e `ligado-banco.txt`. Os logs da aplicação e
do build ficam fora (trazem o id do usuário no login).

A primeira tentativa do dia falhou no início (o `python` do WindowsApps recusou a execução e a senha das
contas ficou vazia) e não mediu nada; a rodada acima é a segunda tentativa, no mesmo dia e no mesmo commit.

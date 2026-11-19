# Medição do falso positivo do "corrijo" nos dados (#581), segunda iteração da correção da #591: INCONCLUSIVA

Rodada de 2026-11-19, regra de decisão `2026-11-18.1` (`docs/avaliacao/continuidade/regra-de-decisao-581.md`),
conjunto `continuidade-581-v1` (24 conversas, sha256
`f46e81ab2048d71832efeed9f8c3063dd00960ae9363b742dca89873474722c7`), no head do PR #592 depois da segunda
iteração da correção da #591.

**Leitura pré-registrada pela orquestradora, antes de qualquer resultado:** "Esta rodada julga a segunda
iteração da correção da #591 pelas métricas de dados: na #499, `M_dados_correcao`,
`M_dados_sem_falso_alarme`, `M_credito_zero`, `M_caminho_fixo`, `M_rota_funcao` e `M_controle`; na #581, o
veredito da regra `2026-11-18.1`. `M_lei_correcao` é comparada com as três rodadas anteriores (0/6 em
18/11, 3/6 no `bedb80db`, 2/6 na variância da `main`): a correção não toca o caminho de lei, e uma falha
nessa métrica não é atribuída a ela. O veredito formal da regra v3 continua sendo o que a regra diz. Os
dois conjuntos já foram usados em rodadas anteriores e a correção foi ajustada à vista de ids e
contagens: esta evidência é mais fraca que a da primeira rodada."

| Item | Valor |
|---|---|
| Commit medido | `35223c72233e8cb35906f00dd137cd9e2d11806a` (head do PR #592, ainda sem merge) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braço | só o ligado (`AI_CONTINUIDADE_HABILITADA=true`, porta 8502), banco próprio recriado, seed da demonstração, base de lei copiada (8.304 trechos) |
| **Chave nova** | **`AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO=true`, LIGADA.** Conferida no bloco de ambiente do processo Java (pid da porta 8502): `AI_CONTINUIDADE_HABILITADA=true` e `AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO=true`, antes de medir |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente) |
| Repetições | 5 |
| Dia e relógio | 2026-11-19, das 19:08 às 19:21 (execução) |
| Execuções | 120, **13 fora (10,8%; limite: acima de 10%)**, 107 medidas |
| **Veredito formal** | **`INCONCLUSIVA`**, só pelo limite de execuções fora |
| Rodadas anteriores | 2026-11-18 em `b8722d16` (`RISCO_RELEVANTE`) e 2026-11-19 em `bedb80db` (`INCONCLUSIVA`) |

Os dois scripts `rodar_medicao_*.sh` são os mesmos das rodadas anteriores (conferido por diff contra o commit
`7dd1cd64`); a chave foi exportada no ambiente que chama o script.

## Por que `INCONCLUSIVA`

13 de 120 execuções ficaram fora (acima de 10%): 10 por `confirmacao_fora_da_rota` (todas as 10 de
`c581-40/r1` a `r5` e `c581-49/r1` a `r5` saíram pela rota `MISTO`; em 18/11, 3 das 10 ainda saíam pela rota de
dados, e no `bedb80db` as 10 saíam por `MISTO`) e 3 por `preparo_invalido` (`c581-06/r2`, `r3`, `r5`). O controle do verdadeiro
positivo **cumpre** o mínimo (15 de 15, mínimo 12). A regra manda refazer a rodada inteira nesse caso; **não foi
refeita**, a decisão é da orquestradora.

## Por classe, as três rodadas

| Classe | 18/11 `b8722d16` | 19/11 `bedb80db` | 19/11 `35223c72` (esta) |
|---|---|---|---|
| Núcleo, execuções válidas | 74 | 73 | 72 |
| `corrigiu_para_pior` | **5** (c581-01/r1 a r5; 6,8%) | 0 | **0** |
| `corrigiu_equivalente` | 0 | 0 | 0 |
| `refeito_para_pior` | 0 | 0 | 0 |
| `manteve_certo` | 69 | 73 | 72 |
| Núcleo que passou pelo modelo (ramo exercitado) | 24% (18 de 74) | 0% (0 de 73) | **0%** (0 de 72) |
| Controle claro | 20 de 20 `manteve_certo` | 20 de 20 | 20 de 20 |
| Controle do verdadeiro positivo | 17 de 17 `corrigiu_para_melhor` | 10 de 15 (e 5 `nao_corrigiu`, `c581-34`) | **15 de 15 `corrigiu_para_melhor`** |
| Execuções fora | 9 de 120 | 12 de 120 | 13 de 120 |

Controle do verdadeiro positivo por conversa (esta rodada): `c581-32` 5 de 5, `c581-33` 5 de 5, `c581-34` 5 de
5 (as 5 repetições de `c581-49` ficaram fora, pela rota `MISTO`). No `bedb80db`, `c581-34` não corrigia em
nenhuma das 5.

## O que aconteceu por valor

- **c581-34 (controle do verdadeiro positivo, produto errado):** a pergunta gravada no par pedia o total de
  diesel em julho, mas a consulta anterior era a de NPK em julho (R$ 231.069,30). **Nas 5 repetições a Ceres
  voltou a corrigir:** abre com "corrijo", consulta diesel de 01/07/2026 a 31/07/2026 e entrega o valor de
  diesel (R$ 80.872,35). No `bedb80db` ela repetia a consulta de NPK e confirmava o valor de NPK.
- **c581-01 (a única com ocorrência em 18/11):** a resposta de base era "Não encontrei compras" (consulta
  de fertilizante NPK em julho). Nas 5 repetições, a confirmação repete a mesma consulta e diz que o
  resultado é o mesmo, também vazio; **sem "corrijo"**. O valor é vazio nos dois lados, como em 18/11 e no
  `bedb80db`.
- **c581-32 e c581-33:** continuam corrigindo para o valor certo, 5 de 5 cada.
- **Núcleo:** nenhum "corrijo" nas 72 execuções válidas, e nenhuma trocou um valor com conteúdo por outro.

## Julgamento de produto nos registros (inferência sem ler código)

Os registros da auditoria (`assistente_interacao`, tokens por chamada) mostram uma chamada pequena ao modelo
dentro da confirmação de dados que **não existia nas rodadas anteriores**: nas 5 confirmações de `c581-01`
(uma por repetição), com **368 tokens de entrada e 7 de saída** e tempo até a resposta de **0,85 a 0,95 s**
(a confirmação sem chamada ao modelo leva cerca de 0,06 s). É a única confirmação da camada `CONFIRMACAO` com tokens abaixo de 1.500
nesta rodada, então é a candidata a ser o julgamento de produto. Isso é leitura dos registros, **não
confirmada no código**.

- **Quantas confirmações:** 5 de 107 medidas na #581 (todas em `c581-01`), mais 0 em toda a #499 (ver a
  pasta `2026-11-19-continuidade-499-pos-591-v2`).
- **Custo por confirmação com a chamada:** +368 tokens de entrada, +7 de saída e cerca de +0,85 s.
- **Efeito no resultado:** a resposta de `c581-01` não mudou (repete a consulta, vazio nos dois lados).
- As confirmações em que o modelo refaz a consulta (`c581-34`, `c581-32`, `c581-33`) seguem com cerca de 6.000
  tokens de entrada e cerca de 1,1 s, como antes.

## Limites

- A regra deu `INCONCLUSIVA` só pelo limite de execuções fora; o núcleo, o controle claro e o controle do
  verdadeiro positivo estão dentro do que a regra pede, mas a regra não os decide sozinhos.
- O núcleo não exercita o ramo do modelo (0%): o zero de `corrigiu_para_pior` vale só para o que passou
  pela régua de texto, que repete a consulta gravada em todas as 72 execuções válidas.
- `c581-40` e `c581-49` saem pela rota `MISTO` nas 10 execuções (antes 7 de 10); a amostra delas continua
  pequena ou nula.
- Esta evidência é mais fraca que a da primeira rodada (leitura pré-registrada acima): o conjunto já foi usado e
  a correção foi ajustada à vista de ids e contagens.

## Arquivos

`rodada.jsonl`, `resumo.json`, `resumo.txt`, `executor.log` e `ligado-banco.txt`. Os logs da aplicação e do
build ficam fora (trazem o id do usuário no login).

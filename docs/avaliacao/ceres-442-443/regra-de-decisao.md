# Regra de decisão das correções da #442 e da #443

Escrita antes de qualquer rodada, junto com o conjunto congelado
[`../treino/perguntas-ceres-442-443.json`](../treino/perguntas-ceres-442-443.json) (sha256 no
`.sha256` ao lado). Vale para os dois PRs de correção; mudar a regra depois de ver um resultado
invalida a medição.

## O conjunto

40 perguntas, no jeito de falar do produtor rural pessoa física, sobre a conta do produtor
principal do seed da demonstração:

| Grupo | Perguntas | O que mede | Certa quando |
|---|---|---|---|
| `periodo` | 12 | #442: dados com período relativo ("mês passado", "último trimestre", "ano passado", "nos últimos seis meses", "mês que vem"), em 7 funções | alguma `fontesDados` tem a função esperada com o período certo, calculado a partir da data da rodada no fuso de Brasília, e os demais parâmetros esperados (produto, tipo) |
| `produto` | 10 | #443: tratamento de 10 produtos (nenhum é a soja) no IBS/CBS | o texto da resposta traz o percentual da tabela oficial (zero/100% ou 60%) **e** uma frase com o contador e confirmar/conferir/enquadrar/validar/verificar/consultar |
| `controle-dados` | 10 | dados com período explícito e funções sem período | como no `periodo`, com as datas ou o número escritos na pergunta |
| `controle-lei` | 8 | lei geral, sem produto | rota `LEGISLACAO` e citação da norma e de um dos artigos esperados |

Os períodos aceitos, os padrões das funções quando um parâmetro falta e as marcas de texto estão
no [`pontuar.py`](pontuar.py), coberto pelo [`test_pontuar.py`](test_pontuar.py)
(`python3 -m unittest` nesta pasta).

## A rodada

- Cada pergunta é feita **3 vezes**, em ordem fixa, pela conta do produtor principal, com o mesmo
  ambiente local (banco próprio, seed da demonstração, `qwen3:4b-instruct` e `bge-m3` aquecidos).
- **Falha técnica não conta como resposta:** HTTP diferente de 200, `modoFallback`, ou erro de
  indisponibilidade ou tempo esgotado. A execução é repetida e as duas ficam registradas no JSONL
  (a falha com `falha_tecnica: true`).
- **Por pergunta vale a maioria** das execuções válidas: certa com 2 ou 3 certas de 3.
- **Linha de base:** a `main` do dia, mesmos comandos, mesmo dia da rodada da correção (a data de
  referência dos períodos relativos tem de ser a mesma: o `comparar` recusa dias diferentes).

## A decisão

Cada correção é decidida sozinha, contra a linha de base da `main` do mesmo dia:

- **#442** (grupo `periodo`) e **#443** (grupo `produto`): a correção **entra** só se
  1. o número de perguntas **certas do grupo dela sobe** em relação à linha de base; e
  2. **nenhuma** pergunta de controle (`controle-dados` e `controle-lei`) que estava certa na linha
     de base fica errada ou sem medição na correção.
- Qualquer outro resultado: **não entra**. O PR sai com a medição e diz isso no título.
- O grupo da outra issue não decide, mas o resultado vai no PR (uma correção que derruba o grupo
  da outra é um achado a relatar).
- Mudança de código depois da medição que altere o comportamento medido exige medir de novo, no
  mesmo dia que uma linha de base nova.

Comando:

```bash
python3 pontuar.py comparar --base base.json --correcao correcao.json --issue 442
```

Sai `ENTRA` (código 0) ou `NAO_ENTRA` (código 1), com as perguntas de controle que pioraram e os
problemas de comparação (conjunto ou dia diferentes, pergunta sem execução válida).

## Uso único

O conjunto mede estas duas correções e não serve para ajustar a Ceres: nenhuma pergunta dele entra
em exemplo de classificação, prompt, descrição de função, template ou teste. Os testes das
correções usam perguntas inventadas, diferentes destas.

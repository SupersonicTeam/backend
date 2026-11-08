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

## Declaração do ensaio e das mudanças depois do congelamento (2026-11-07)

Escrita antes da linha de base, a pedido da sessão orquestradora. Horários em Brasília.

**1. O que o ensaio rodou.** Duas tentativas, as duas com o JAR da `main` (`4a0b3a8`), nenhuma
com as correções:

- 12:47: o seed da demonstração falhou na subida (caminho relativo do certificado de teste); a
  rodada não começou e **nenhuma resposta** foi produzida.
- 12:50 a 12:57: **p443-01** (grupo `periodo`) e **p443-33** (grupo `controle-lei`), uma
  repetição cada, só para validar o caminho (subida, seed, cópia da base de lei, login, executor e
  pontuação). O que li das respostas: na p443-01, rota `DADOS`, nenhuma função chamada e o texto
  "Não consegui identificar qual dado consultar..." (223 s); na p443-33, rota `LEGISLACAO` com
  citações da LC 214/2025, art. 252, e o início do texto sobre o IBS e a CBS na venda e no aluguel
  de imóvel (62 s). Pontuadas pelo script: p443-01 errada, p443-33 certa.

**2. A mudança do "confira" (`8f461ab`, 12:50:38) não veio do ensaio.** Ela é anterior à única
tentativa que produziu respostas (12:50:48 a 12:57:26), e nenhuma das duas respostas tem
ressalva de contador. Veio da leitura do código, depois do congelamento: a constante da ressalva no
`TemplateTratamentoProduto` diz "Confira com seu contador...", e o padrão "confer" do script não
pegava o imperativo "confira" de conferir. A mudança vale igual para a base e para as correções.

**3. As correções foram escritas depois do ensaio.** O código da #442 (`c77e9ec`, 13:00 a 13:08;
ajustes do review em `8b4684f`) e o da #443 (`46275b4`, 13:10 a 13:21; ajustes do review em
`16cb907`) são posteriores às respostas das 12:57. A resposta da p443-01 confirmou o defeito que
a #442 já descreve (pergunta de apuração com "mês passado" sem nenhuma função); o desenho da
correção (período resolvido pelo backend, mostrado ao modelo e gravado nas chamadas) veio da issue
e da leitura da `RotaDados` e do `PromptsCeres`. A p443-33 é controle de lei e não tem relação
com as correções. Nenhuma outra pergunta do conjunto foi feita ao modelo antes da linha de base.

**Consequência na regra.** Como o ensaio usou duas perguntas do conjunto, a regra é calculada
duas vezes, com todas as perguntas e sem **p443-01** e **p443-33**, e cada correção só entra se
passar nas duas:

```bash
python3 pontuar.py comparar --base base.json --correcao correcao.json --issue 442
python3 pontuar.py comparar --base base.json --correcao correcao.json --issue 442 \
    --sem-ids p443-01,p443-33
```

## Mudanças nos scripts depois da declaração (2026-11-08, antes da linha de base)

Do code review do PR 1, nenhuma muda a classificação de uma resposta:

- `comparar`: pergunta sem execução válida na **base** também é problema de comparação (antes só
  na correção). Contada como errada, ela inflaria a subida da correção.
- `executar.py`: recusa gravar num JSONL que já tem execuções; duas rodadas no mesmo arquivo
  somariam os votos das duas.

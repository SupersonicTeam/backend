# #452: rodada v2e (2026-11-10), medição oficial

Medição de como a Ceres entende o período relativo das perguntas de dados. Compara a `main` com a
correção, com o conjunto `ceres-452-v2` e pela regra `../../regra-de-decisao-v2.md`.

**Decisão do `pontuar.py comparar`: ENTRA.**

## Condições

| | |
|---|---|
| Base | `af1124b5ac6e570cf78329cd220a0211a0daab8e` (`origin/main` de 2026-11-10, 01h46) |
| Correção | `1e0134a11f0f66ffa8bd10eeae196ddb9c3bf87a`: a `ffea5a8` aplicada sobre a `af1124b` por merge local, sem conflito (commit não publicado) |
| Conjunto | `ceres-452-v2`, sha256 `ce688217d06f269f2c1e4fc465307c2db9dc5d51757713040dd3e42c6dc2bc19`, 40 perguntas |
| Modelo | `qwen3:4b-instruct`, digest `0edcdef34593`, Ollama local |
| Data de referência | 2026-11-10 nos dois braços |
| Rito | 3 repetições, ordem alternada, aquecimento nos dois braços, até 3 tentativas por falha técnica |
| Horário | começo às 01h50:30 e fim às 02h55:59 (-03:00); script saiu com 0 |

Blocos, em ordem de execução: base r1 01:54–02:03, correção r1 02:03–02:15, correção r2 02:15–02:25,
base r2 02:25–02:34, base r3 02:34–02:44, correção r3 02:44–02:55.

### Carga da máquina

- Antes do início, nenhum `java`, `mvn`, `python` ou `node` estava no ar. A memória livre era de 6,1 GiB
  de 31,7 GiB, e a CPU estava ociosa.
- Durante a rodada, a carga foi lida a cada 2 minutos (`carga.log`, 33 leituras). Nenhum `java` ou
  `mvn` de outra sessão apareceu.
- A CPU ficou entre 57% e 90% e a memória livre entre 3,3 e 4,1 GiB. Essa carga vem das duas aplicações
  da medição e do Ollama.

### Paridade dos dados

- A paridade do produtor da medição (`paridade-base.txt` e `paridade-correcao.txt`) é idêntica nos dois
  braços.
- Na paridade geral (`paridade-geral-*.txt`), a única diferença é a quantidade de apurações por mês: 9
  na base e 11 na correção.
- Os valores das apurações são iguais, e essa contagem fica fora do produtor da medição. A diferença
  vem da ordem dos runners de seed, assunto da #470.

## Resultado por grupo (perguntas certas / total)

| Grupo | Base | Correção |
|---|---|---|
| g1, mês seguinte | 2/6 | 5/6 |
| g2, mês corrente | 3/6 | 6/6 |
| g3, trimestre | 0/6 | 4/6 |
| g4, aberto | 6/10 | 8/10 |
| controle de dados | 8/8 | 8/8 |
| controle de lei | 2/2 | 2/2 |
| controle fora de escopo | 2/2 | 2/2 |

- **Subiram (12):** v2-g1-01, v2-g1-04, v2-g1-06, v2-g2-01, v2-g2-04, v2-g2-06, v2-g3-01, v2-g3-02,
  v2-g3-04, v2-g3-05, v2-g4-07 e v2-g4-08.
- **Caíram:** nenhuma. Nenhum controle piorou.

## Latência

Mediana em segundos, só das execuções válidas. A latência fica fora da decisão.

| Grupo | Base | Correção |
|---|---|---|
| mês seguinte | 8,6 | 8,7 |
| mês corrente | 10,1 | 9,9 |
| trimestre | 19,3 | 18,2 |
| aberto | 17,0 | 18,4 |
| controle de dados | 18,2 | 18,0 |
| controle de lei | 20,2 | 19,9 |
| controle fora de escopo | 1,8 | 1,8 |

## Falhas técnicas

- **Base:** nenhuma, com 120 execuções válidas de 120.
- **Correção:** 14 tentativas técnicas, em duas cascatas iguais.
  - A v2-g4-06 estourou o tempo na r1 (02h09) e na r3 (02h49), com 123,5 s cada.
  - O log da aplicação registra a falha na chamada ao modelo (`ResourceAccessException`) na etapa de
    dados da rota MISTO.
  - Depois de cada estouro, as tentativas seguintes caíram no disjuntor do modelo ainda aberto, com
    fallback em 0,1 a 0,2 s. A v2-g4-06 e a v2-g4-07 gastaram as três tentativas, e a v2-g4-08 perdeu a
    primeira e respondeu na segunda.
- Execuções sem resposta válida: v2-g4-06 r1 e r3, v2-g4-07 r1 e r3. Nenhuma pergunta ficou
  `sem_medicao`, porque as duas responderam na r2.
- A decisão não depende dessas perdas. A v2-g4-07 contou como certa por uma execução válida; contada
  como errada, o grupo aberto fica 7 a 6 e ainda sobe. A v2-g4-06 está errada nos dois braços.

## Ressalva: a v2-g4-06 na correção

A pergunta é "Do mês de julho pra trás, quanto foi retido de Funrural nas vendas?".

| Rodada | Braço | O que aconteceu |
|---|---|---|
| v2d (sob carga) | correção | estouro na r1 e na r2 (124,8 e 124,9 s) |
| v2e | correção | estouro na r1 e na r3 (123,5 s); a r2 levou 92,7 s, chamou `consultarRetencoes` três vezes (julho, junho e maio, um mês por chamada) e errou |
| v2d e v2e | base | 18 a 21 s (50 s sob carga), uma chamada só (`2026-07-01` a `2026-07-31`), errada porque julho sozinho não vale |

- Somando a v2d e a v2e, a correção estourou o tempo em 4 de 5 execuções dessa pergunta. Para o
  produtor, isso é cerca de 2 minutos de espera e uma resposta em modo fallback.
- A regra conta o estouro como falha técnica e deixa a latência fora da decisão, então o ENTRA não muda.
- Esse comportamento pesa no merge da correção e fica para o Murillo decidir.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `base.jsonl`, `correcao.jsonl` | todas as execuções, inclusive aquecimento e tentativas técnicas |
| `resumo-base.json`, `resumo-correcao.json` | saída do `pontuar.py pontuar` |
| `decisao.json` | saída do `pontuar.py comparar` |
| `executor.log` | saída do `executar.py` |
| `paridade-*.txt` | paridade do produtor e geral de cada braço |
| `carga.log` | memória livre, CPU e processos Java ou Maven de fora, a cada 2 minutos |

Os logs das aplicações ficaram fora, porque trazem o id do usuário no login.

Para reproduzir a pontuação, rode da raiz do repositório:

```bash
python docs/avaliacao/ceres-452/pontuar.py pontuar --perguntas docs/avaliacao/treino/perguntas-ceres-452-v2.json --execucoes docs/avaliacao/ceres-452/resultados/2026-11-10-v2e/base.jsonl --saida resumo-base.json
```

Rode o mesmo comando para `correcao.jsonl` e depois:

```bash
python docs/avaliacao/ceres-452/pontuar.py comparar --base resumo-base.json --correcao resumo-correcao.json
```

As saídas são iguais, byte a byte, aos `resumo-*.json` e ao `decisao.json` desta pasta (conferido em
2026-11-10).

## Rodadas anteriores do mesmo dia

A v2d (`../2026-11-10-v2d-inconclusiva/`) ficou inconclusiva por carga em paralelo das 00:17:45 às
01:41:11 e não foi pontuada. Pela regra, a v2e refez a medição inteira nos dois braços, no mesmo dia.

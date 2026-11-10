# #452: rodada v2f (2026-11-10)

Medição de como a Ceres entende o período relativo das perguntas de dados. Compara a `main` com a
rodada 3 da correção, com o conjunto `ceres-452-v2` e pela regra `../../regra-de-decisao-v2f.md`: a
regra v2 com a `v2-g4-06` fora da decisão, porque o comportamento dela na v2e foi descrito à sessão
da correção.

**Decisão do `pontuar.py comparar`: NAO_ENTRA.** O grupo aberto empatou em 7/9 nos dois braços, e
pela regra empate não é subir. Os grupos 1 a 3 sobem, nenhum controle piora e a rodada não é
inconclusiva.

## Condições

| | |
|---|---|
| Base | `f958ec16c7236b569477d5e3e027535bdd35c944` (`origin/main` de 2026-11-10, 09h45). Em relação à base da v2e (`af1124b`), não muda nada em `src/` nem no `pom.xml` |
| Correção | `f40d2e9243b7f539e16839e7efb89a1f7f039ec5`: a `671d4a3` (rodada 3 do #459) aplicada sobre a `f958ec1` por merge local, sem conflito. O commit não foi publicado; a árvore dele é `984b8a15354268c92dc983206b52d9a7cd424ec3` |
| Conjunto | `ceres-452-v2`, sha256 `ce688217d06f269f2c1e4fc465307c2db9dc5d51757713040dd3e42c6dc2bc19`, 40 perguntas |
| Fora da decisão | `v2-g4-06` (quinto argumento do `rodar_medicao_452.sh`, `pontuar --fora-da-decisao`) |
| Modelo | `qwen3:4b-instruct`, digest `0edcdef34593`, Ollama local em CPU |
| Data de referência | 2026-11-10 nos dois braços |
| Rito | 3 repetições, ordem alternada, aquecimento nos dois braços, até 3 tentativas por falha técnica |
| Horário | começo às 09h49:57 e fim às 10h52:42 (-03:00); script saiu com 0 |

Blocos, em ordem de execução: base r1 09:54–10:05, correção r1 10:05–10:14, correção r2 10:14–10:24,
base r2 10:24–10:33, base r3 10:33–10:42, correção r3 10:43–10:52.

A árvore da correção se confere sem mexer no checkout (git 2.38 ou mais novo). O comando abaixo
imprime `984b8a15354268c92dc983206b52d9a7cd424ec3`:

```bash
git merge-tree --write-tree f958ec16c7236b569477d5e3e027535bdd35c944 671d4a30f0ba7a14198d491a81206916d6e8e919
```

### Primeira tentativa (09h46), abortada antes de qualquer pergunta

- A rodada começou às 09h46:17 e parou às 09h48:51, com o script saindo com 18.
- O executor recusa um worktree com mudança fora de commit, porque aí o SHA não descreve o braço.
  Às 09h46:57, um teste do script de análise da sessão avaliadora importou o `pontuar.py` do worktree
  da base e criou ali um `__pycache__`.
- Nenhuma pergunta foi feita, e nada dela entra nesta pasta. O `__pycache__` foi apagado, e a rodada
  recomeçou às 09h49:57, com a mesma base e a mesma correção.

### Carga da máquina

- Antes do início, nenhum `java`, `mvn`, `python` ou `node` estava no ar, e havia 12,8 GiB livres de
  31,7 GiB.
- Durante a rodada, nenhum `java` ou `mvn` de outra sessão apareceu (`carga.log`, 64 leituras de
  09:50 a 10:54).
- Duas vigias gravaram no mesmo arquivo, com cerca de 30 s de defasagem: a da primeira tentativa não
  parou. Por isso o arquivo tem o dobro de leituras.
- Com as aplicações no ar, a memória livre ficou entre 1,4 e 2,4 GiB (na v2e, entre 3,3 e 4,1) e a
  CPU entre 57% e 97%. A carga vem das duas aplicações da medição e do Ollama.

### Paridade dos dados

- A paridade do produtor da medição é idêntica nos dois braços.
- Na geral, só a quantidade de apurações por mês difere: 11 na base e 9 na correção, o inverso da
  v2e. Os valores são iguais, e a contagem fica fora do produtor da medição (#470).

## Resultado por grupo (perguntas certas / total)

| Grupo | Base | Correção |
|---|---|---|
| g1, mês seguinte | 2/6 | 5/6 |
| g2, mês corrente | 3/6 | 6/6 |
| g3, trimestre | 0/6 | 4/6 |
| g4, aberto (sem a `v2-g4-06`) | 7/9 | 7/9 |
| controle de dados | 8/8 | 8/8 |
| controle de lei | 2/2 | 2/2 |
| controle fora de escopo | 2/2 | 2/2 |

- **Subiram (11):** v2-g1-01, v2-g1-04, v2-g1-06, v2-g2-01, v2-g2-04, v2-g2-06, v2-g3-01, v2-g3-02,
  v2-g3-04, v2-g3-05 e v2-g4-07.
- **Caiu:** v2-g4-08.
- **Grupos 1 a 3:** os números são os mesmos da v2e (2→5, 3→6 e 0→4).

### Grupo aberto, pergunta a pergunta

Resultado nas três repetições e segundos de cada uma (r1, r2 e r3).

| Pergunta | Base | Correção |
|---|---|---|
| v2-g4-01 | certa 3/3 (18,0; 17,6; 17,7) | certa 3/3 (17,5; 18,2; 17,9) |
| v2-g4-02 | certa 3/3 (16,8; 16,8; 17,0) | certa 3/3 (17,2; 17,3; 17,3) |
| v2-g4-03 | errada 0/3 (16,3; 16,5; 16,6) | errada 0/3 (17,1; 16,6; 17,0) |
| v2-g4-04 | certa 3/3 (13,2; 12,7; 12,8) | certa 3/3 (13,0; 12,7; 13,2) |
| v2-g4-05 | certa 3/3 (17,1; 16,4; 16,7) | certa 3/3 (17,1; 17,3; 17,1) |
| v2-g4-07 | errada 0/3 (16,6; 16,5; 16,5) | **certa 3/3** (17,1; 16,5; 17,1) |
| v2-g4-08 | certa 3/3 (33,3; 32,1; 32,6) | **errada 1/3** (32,5; 32,4; 33,5) |
| v2-g4-09 | certa 3/3 (29,0; 1,6; 1,8) | certa 3/3 (1,7; 1,6; 1,6) |
| v2-g4-10 | certa 3/3 (22,0; 1,4; 1,6) | certa 3/3 (1,7; 1,5; 1,6) |

O r1 lento da base na v2-g4-09 e na v2-g4-10 caiu no primeiro bloco da rodada.

### A v2-g4-08 trocou de braço entre a v2e e a v2f

A pergunta é "Gastei mais com diesel em maio ou em outubro?". As duas consultas de compras de diesel
saem nos dois braços, e o que muda é o fim de maio que o modelo escreve:

| Rodada | Base | Correção |
|---|---|---|
| v2e | `2026-05-30` nas 3 (errada) | `2026-05-31` nas 3 válidas (certa) |
| v2f | `2026-05-31` nas 3 (certa) | `2026-05-30` na r1 e na r3 (errada), `2026-05-31` na r2 |

- Outubro sai igual nas duas rodadas (`2026-10-01` a `2026-10-31`), e o valor também (R$ 82.785,06),
  porque a nota de maio cai antes do dia 30.
- O código da base é o mesmo nas duas rodadas, sem mudança em `src/`. A troca vem do modelo, que ora
  escreve 30, ora 31, como último dia de maio, nos dois braços.
- Essa pergunta sozinha leva o grupo aberto da subida (v2e) ao empate (v2f). A regra não muda depois
  do resultado, então a decisão registrada é NAO_ENTRA.

## Latência

Mediana em segundos, só das execuções válidas. A latência fica fora da decisão.

| Grupo | Base | Correção |
|---|---|---|
| mês seguinte | 8,9 | 8,9 |
| mês corrente | 9,9 | 10,2 |
| trimestre | 19,2 | 18,6 |
| aberto (sem a `v2-g4-06`) | 16,6 | 17,1 |
| controle de dados | 18,1 | 18,4 |
| controle de lei | 33,0 | 19,0 |
| controle fora de escopo | 1,8 | 1,8 |

A mediana do controle de lei sai de seis execuções e muda com uma só. Na base, a v2-c-10 levou
32,9 s no primeiro bloco, contra 8 a 10 s nas outras repetições, e a v2-c-09 levou 56,3 s, contra 33
a 37 s.

## Falhas técnicas

Nenhuma, nos dois braços:

- 120 execuções válidas de 120 em cada braço;
- nenhum estouro de 120 s (o máximo foi 56,3 s na base e 39,2 s na correção);
- nenhuma resposta em `modoFallback` e nenhum HTTP diferente de 200;
- nenhuma pergunta `sem_medicao`.

## A `v2-g4-06`, contaminada e fora da decisão

A pergunta é "Do mês de julho pra trás, quanto foi retido de Funrural nas vendas?".

| Braço | Resultado | Consultas por execução | Segundos (r1, r2, r3) |
|---|---|---|---|
| base | errada 0/3 | 1 (`2026-07-01` a `2026-07-31`, julho sozinho) | 18,5; 18,1; 18,3 |
| correção | certa 3/3 | 1 (`2024-08-01` a `2026-07-31`) | 18,4; 18,2; 18,4 |

- Nos dois braços, a auditoria da Ceres registra 1 chamada de função e 71 tokens de saída por
  execução (`auditoria-v2-g4-06.txt`).
- Na v2e, a correção anterior pedia 7 chamadas, gerava 371 tokens e estourou o tempo em 2 de 3
  execuções.
- A pergunta conta como contaminada, então o acerto dela não entra no grupo aberto.

## Junção de consultas repetidas

- **Na `v2-g4-06`, não precisou agir:** o modelo pediu uma consulta só em cada execução.
- **Nas outras perguntas:** nenhuma resposta, em nenhum dos braços, tem fonte de dados repetida.
- **Na auditoria:** as chamadas pedidas batem com as executadas, fora 9 recusas de
  `consultarApuracao` por parâmetro inválido (3 por repetição), que se repetem iguais na base.
- **Não há linha de log para contar isso.** O log da correção não registra a junção. As mensagens da
  Ceres nele são:
  - "periodo da pergunta aplicado em 1 chamada(s) de funcao" (24);
  - a negativa trocada pela frase do backend (15);
  - o parâmetro inválido (9);
  - a lei sem resposta nos trechos (6);
  - o prazo do embedding vencido (1).

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `base.jsonl`, `correcao.jsonl` | todas as execuções, inclusive aquecimento |
| `resumo-base.json`, `resumo-correcao.json` | saída do `pontuar.py pontuar --fora-da-decisao v2-g4-06` |
| `decisao.json` | saída do `pontuar.py comparar` |
| `executor.log` | saída do `executar.py` |
| `paridade-*.txt` | paridade do produtor e geral de cada braço |
| `carga.log` | memória livre, CPU e processos Java ou Maven de fora, a cada 2 minutos (duas vigias) |
| `auditoria-v2-g4-06.txt` | auditoria da Ceres das execuções da `v2-g4-06` nos dois braços e as linhas dos logs das aplicações nas janelas dela, sem dado pessoal |

Os logs inteiros das aplicações ficaram fora, porque trazem o id do usuário no login.

Para reproduzir a pontuação, rode da raiz do repositório, com a saída numa pasta temporária fora
dele. Sem o `--fora-da-decisao`, o resumo sai pela regra v2 e não vale para esta rodada.

```bash
SAIDA=$(mktemp -d)
P=docs/avaliacao/ceres-452/pontuar.py
R=docs/avaliacao/ceres-452/resultados/2026-11-10-v2f
for b in base correcao; do python3 $P pontuar --perguntas docs/avaliacao/treino/perguntas-ceres-452-v2.json --execucoes $R/$b.jsonl --saida $SAIDA/resumo-$b.json --fora-da-decisao v2-g4-06; done
python3 $P comparar --base $SAIDA/resumo-base.json --correcao $SAIDA/resumo-correcao.json > $SAIDA/decisao.json
for f in resumo-base.json resumo-correcao.json decisao.json; do diff --strip-trailing-cr $R/$f $SAIDA/$f && echo "$f igual"; done
```

Os JSONL, os resumos e a decisão foram gravados no Windows, com fim de linha CRLF. Em outro sistema
a saída refeita sai com LF, e por isso a comparação ignora o fim de linha.

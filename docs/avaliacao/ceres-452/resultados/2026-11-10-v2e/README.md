# #452: rodada v2e (2026-11-10), medição oficial

Medição de como a Ceres entende o período relativo das perguntas de dados. Compara a `main` com a
correção, com o conjunto `ceres-452-v2` e pela regra `../../regra-de-decisao-v2.md`.

**Decisão do `pontuar.py comparar`: ENTRA.**

## Condições

| | |
|---|---|
| Base | `af1124b5ac6e570cf78329cd220a0211a0daab8e` (`origin/main` de 2026-11-10, 01h46) |
| Correção | `1e0134a11f0f66ffa8bd10eeae196ddb9c3bf87a`: a `ffea5a8` aplicada sobre a `af1124b` por merge local, sem conflito. O commit não foi publicado; a árvore dele é `5841d171505779a4639c0cca3cacf53dfda94ce5` |
| Conjunto | `ceres-452-v2`, sha256 `ce688217d06f269f2c1e4fc465307c2db9dc5d51757713040dd3e42c6dc2bc19`, 40 perguntas |
| Modelo | `qwen3:4b-instruct`, digest `0edcdef34593`, Ollama local |
| Data de referência | 2026-11-10 nos dois braços |
| Rito | 3 repetições, ordem alternada, aquecimento nos dois braços, até 3 tentativas por falha técnica |
| Horário | começo às 01h50:30 e fim às 02h55:59 (-03:00); script saiu com 0 |

Blocos, em ordem de execução: base r1 01:54–02:03, correção r1 02:03–02:15, correção r2 02:15–02:25,
base r2 02:25–02:34, base r3 02:34–02:44, correção r3 02:44–02:55.

O commit da correção não está no GitHub, mas a árvore dele se confere sem mexer no checkout (git 2.38
ou mais novo). O comando abaixo imprime `5841d171505779a4639c0cca3cacf53dfda94ce5`. Um merge refeito
daria outro SHA de commit, porque autor e data mudam, mas a mesma árvore:

```bash
git merge-tree --write-tree af1124b5ac6e570cf78329cd220a0211a0daab8e ffea5a8b1ba51080b2152d74eab20353327b6a50
```

### Carga da máquina

- Antes do início, nenhum `java`, `mvn`, `python` ou `node` estava no ar. A memória livre era de 6,1 GiB
  de 31,7 GiB, e a CPU estava ociosa.
- Durante a rodada, a carga foi lida a cada 2 minutos (`carga.log`, 33 leituras de 01:51 a 02:57).
  Nenhum `java` ou `mvn` de outra sessão apareceu.
- Até o fim da rodada, a CPU ficou entre 57% e 90% e a memória livre entre 3,3 e 4,1 GiB. Essa carga vem
  das duas aplicações da medição e do Ollama. A última leitura (02:57:23, CPU 19%, 5,3 GiB livres) já é
  de depois do fim.

### Paridade dos dados

- A paridade do produtor da medição (`paridade-base.txt` e `paridade-correcao.txt`) é idêntica nos dois
  braços.
- Na paridade geral (`paridade-geral-*.txt`), a única diferença é a quantidade de apurações por mês: 9
  na base e 11 na correção.
- Os valores das apurações são iguais, e essa contagem fica fora do produtor da medição.
- A contagem não acompanha a correção: na v2d, os dois braços tinham 11. Ela varia entre subidas da
  aplicação, e o seed fora do produtor da medição fica com a #470 (ordem dos runners de seed). A causa
  exata dessa variação não foi apurada aqui.

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
  - O log da aplicação da correção registra às 02:11:11 e às 02:51:54 a falha na chamada ao modelo
    (`ResourceAccessException`) e o modo fallback na etapa de dados da rota MISTO; as linhas estão em
    `auditoria-v2-g4-06.txt`. No JSONL, as duas execuções aparecem com `modoFallback` e 123,5 s.
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
| v2e | correção | estouro na r1 e na r3 (123,5 s). A r2 levou 92,7 s e errou: pediu 7 chamadas de `consultarRetencoes`, uma por mês. Seis valeram (julho, junho, maio, abril, março e janeiro de 2026) e uma foi recusada por parâmetro inválido; fevereiro ficou de fora |
| v2d e v2e | base | 18 a 21 s (50 s sob carga), uma chamada só (`2026-07-01` a `2026-07-31`), errada porque julho sozinho não vale |

As 6 chamadas válidas da r2 estão no `correcao.jsonl` (linha 71, em `fontesDados`). As 7 pedidas
estão na auditoria da Ceres (`chamadas_funcao` = 7), e a recusa está no log da aplicação da
correção, às 02:21:52 ("Funcao consultarRetencoes chamada com parametro invalido"). As duas fontes
estão em `auditoria-v2-g4-06.txt`: o banco de cada braço é recriado na rodada seguinte, e o log
inteiro fica fora desta pasta.

- Somando a v2d e a v2e, a correção estourou o tempo em 4 de 5 execuções dessa pergunta. Para o
  produtor, isso é cerca de 2 minutos de espera e uma resposta em modo fallback.
- A regra conta o estouro como falha técnica e deixa a latência fora da decisão, então o ENTRA não muda.
- Esse comportamento pesa no merge da correção e fica para o Murillo decidir.

### Por que a correção demora nessa pergunta

Os números desta seção vêm de `auditoria-v2-g4-06.txt` e do JSONL.

- **O Ollama da medição roda em CPU.** O container `agrocontabil-ollama` não tem GPU (o
  `docker inspect` não traz `DeviceRequests`). Na r2 da correção, a Ceres gerou 371 tokens em
  92,6 s, cerca de 4 tokens por segundo, contando também a leitura do prompt.
- **O modelo enumera os meses na escolha de funções.** Na correção, ele pede uma chamada de função
  por mês, e todas saem de uma única chamada ao modelo, a de escolha de funções. Dos 371 tokens da
  r2, cerca de 350 são dessa chamada. Os outros 21 são da classificação: a auditoria só guarda o
  total, mas as execuções cortadas da mesma pergunta, em que só a classificação terminou,
  registram 21. A uns 4 tokens por segundo, os 350 tokens levam perto de 90 s, quase todo o tempo
  da r2.
- **Na base, a geração é curta.** A mesma pergunta gera 71 tokens de saída no total
  (classificação e uma chamada de função) e responde em 18 a 21 s.
- **Nas execuções cortadas, o tempo acaba antes do teto.** A geração ainda corria quando venceu o
  read-timeout de 120 s (`spring.http.client.read-timeout`): o log registra
  `ResourceAccessException` na etapa de dados, e não a resposta cortada pelo teto de 512 tokens da
  escolha de funções (`app.assistente.tetos.funcoes`). Na taxa da r2, são perto de 450 a 480
  tokens até o corte. O número exato não ficou registrado: a auditoria só guarda os 21 tokens da
  classificação, porque a chamada cortada não volta, e o log do Ollama desta rodada não foi
  guardado.
- **Depois da falha, o modelo fica 30 s fora.** O disjuntor do modelo abre por 30 s
  (`app.assistente.fallback.pausa-apos-falha`). As tentativas seguintes começam 4,5 a 5,5 s depois
  do estouro e por isso caem no fallback em 0,1 a 0,2 s.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `base.jsonl`, `correcao.jsonl` | todas as execuções, inclusive aquecimento e tentativas técnicas |
| `resumo-base.json`, `resumo-correcao.json` | saída do `pontuar.py pontuar` |
| `decisao.json` | saída do `pontuar.py comparar` |
| `executor.log` | saída do `executar.py` |
| `paridade-*.txt` | paridade do produtor e geral de cada braço |
| `carga.log` | memória livre, CPU e processos Java ou Maven de fora, a cada 2 minutos |
| `auditoria-v2-g4-06.txt` | auditoria da Ceres das execuções da `v2-g4-06` nos dois braços e as linhas do log da correção nas janelas dela, sem dado pessoal |

Os logs inteiros das aplicações ficaram fora, porque trazem o id do usuário no login. Só as linhas
das janelas da `v2-g4-06`, sem as de login, estão em `auditoria-v2-g4-06.txt`.

Para reproduzir a pontuação, rode da raiz do repositório, com a saída numa pasta temporária fora dele:

```bash
SAIDA=$(mktemp -d)
P=docs/avaliacao/ceres-452/pontuar.py
R=docs/avaliacao/ceres-452/resultados/2026-11-10-v2e
for b in base correcao; do python3 $P pontuar --perguntas docs/avaliacao/treino/perguntas-ceres-452-v2.json --execucoes $R/$b.jsonl --saida $SAIDA/resumo-$b.json; done
python3 $P comparar --base $SAIDA/resumo-base.json --correcao $SAIDA/resumo-correcao.json > $SAIDA/decisao.json
for f in resumo-base.json resumo-correcao.json decisao.json; do diff --strip-trailing-cr $R/$f $SAIDA/$f && echo "$f igual"; done
```

Os JSONL, os resumos e a decisão foram gravados no Windows, com fim de linha CRLF. No Windows, a saída
refeita é igual byte a byte à desta pasta (conferido em 2026-11-10). Em outro sistema, ela sai com LF, e
por isso a comparação ignora o fim de linha.

## Rodadas anteriores do mesmo dia

A v2d (`../2026-11-10-v2d-inconclusiva/`) ficou inconclusiva por carga em paralelo das 00:17:45 às
01:41:11 e não foi pontuada. Pela regra, a v2e refez a medição inteira nos dois braços, no mesmo dia.

# Medição da #452, 2026-11-09: veredito NAO_ENTRA

Regra aplicada exatamente como gravada em `../../regra-de-decisao.md`, sobre o conjunto
`ceres-452-v1` (sha256 `7f1b2bca30874412624c9850eeaa4ae44112707c82fcdebe4da49189b378fd74`).

## Braços

| | Base | Correção |
|---|---|---|
| Commit | `main` `8e086421cc080635a318318580d119ce0c7f6145` | `fix/452-periodo-relativo` (PR #459) `22cd4cd020b83271c913eb9bf931d2d12a984c7e` |
| Banco | `agroconia_452_base` | `agroconia_452_correcao` |
| Porta | 8452 | 8453 |

- A correção saiu de `da99dbb`. De `da99dbb` para `8e08642` só entraram docs, testes e CI (o
  PR #458), sem código da Ceres. Em `src/main`, a correção muda só `PeriodoDaPergunta` e
  `RotaDados`.
- Data de referência 2026-11-09; chamadas das 14h19min53s às 15h57min03s (Brasília), pelo campo
  `horario` do JSONL; modelo `qwen3:4b-instruct`
  (digest `0edcdef34593…`, o mesmo nos dois braços), Ollama e PostgreSQL dos containers originais,
  com a trava do Ollama local.
- Execução: 3 repetições, ordem alternada (base primeiro nas repetições 1 e 3), aquecimento do
  modelo e duas perguntas de aquecimento por braço.
- Bancos: um por braço no `agrocontabil-postgres`, criados do zero, com o seed da demonstração
  (profile `dev`). Os 8.304 trechos de `legislacao_trecho` foram copiados do banco `agrocontabil`
  depois do Liquibase. A paridade bateu antes da primeira pergunta (`paridade-base.txt` e
  `paridade-correcao.txt`: trechos, notas e guias por status e mês, com as somas), às 14h19min35s,
  depois do fim do seed nos dois braços (14h18min26s e 14h19min09s, pelo log das aplicações).
- A paridade da `apuracao_mensal` (que o `consultarApuracao` lê) não estava no roteiro da rodada e
  foi conferida **depois** dela, nos mesmos bancos, com as aplicações paradas:
  `paridade-apuracao-*.txt`, iguais (6 períodos). O roteiro passou a conferi-la antes da rodada, e
  a esperar a linha final do seed no log, não só o health (o seed roda depois de o Tomcat subir).
- Conta `carlos.rossi@example.com`. A senha foi gerada no processo e passada só por variável de
  ambiente, sem ir para arquivo nem para a saída. A cota semanal da Ceres ficou alta e igual nos
  dois braços (`AI_COTA_CREDITOS_PRODUTOR`), porque 59 × 3 perguntas passam da cota padrão.
- Roteiro da rodada: `../../rodar_medicao_452.sh`. Os caminhos da trava e do `.env` são os da
  máquina em que a medição rodou.

## Resultado (a regra)

Falhas técnicas: **0** nos dois braços (177 execuções pontuadas em cada um), sem pergunta sem
medição e sem problema que tornasse a medição inconclusiva.

| Grupo | Base | Correção | Total | Pela regra |
|---|---|---|---|---|
| 1 `mes_seguinte` | 2 | 9 | 10 | sobe |
| 2 `mes_corrente` | 7 | 7 | 8 | **empate: não sobe** |
| 3 `trimestre` | 0 | 7 | 8 | sobe |
| 4 `aberto` | 10 | 9 | 14 | **cai** |
| `controle_dados` | 12 | 11 | 12 | **c-05 piorou** |
| `controle_lei` | 3 | 3 | 4 | igual |
| `controle_fora` | 2 | 2 | 3 | igual |

- Subiram (de errada a certa): g1-01, g1-03, g1-06, g1-07, g1-08, g1-09, g1-10, g3-01, g3-02,
  g3-03, g3-04, g3-05, g3-07, g3-08.
- Caíram (de certa a errada): c-05 e g4-13.

**Veredito: NAO_ENTRA.** O grupo 2 empatou, o grupo 4 caiu e o controle c-05 deixou de estar
certo.

A latência mediana por grupo, com os dois braços aquecidos, ficou praticamente igual
(`decisao.json`). Ela está fora da decisão.

## Leitura da avaliadora (não faz parte do veredito)

O texto abaixo é interpretação das respostas gravadas. Não altera a regra nem o veredito.

- **c-05** ("Como ficou a apuração de agosto?"): base 2 de 3, correção 1 de 3. Quando erra, nos
  dois braços, a resposta é "Não consegui identificar qual dado consultar", ou seja, o modelo não
  escolheu nenhuma função. Quando acerta, os dois braços chamam `consultarApuracao` com
  `2026-08`. A queda parece variação do modelo na escolha da função, e não efeito do período
  resolvido. Pela regra, porém, é piora de controle.
- **g4-13** ("Se eu vender no fim do mês, o comprador só pode usar o crédito no mês seguinte?"):
  na base, 1 de 3 foi para a rota MISTO e 2 de 3 para LEGISLACAO (as duas de lei terminaram
  descartadas, o que a regra aceita nas perguntas de regra). Na correção, 3 de 3 foram para MISTO,
  com `consultarRetencoes` de 01/11 a 30/11. A parte de dados do MISTO consulta o mês corrente numa
  pergunta que é só de regra.
- Erradas nos dois braços, sem mudança:
  - g2-06 ("Neste mês ainda vence alguma guia de ICMS?"): `proximosVencimentos` com 30 dias, que
    vai até 09/12;
  - g1-04 ("Mês que vem tem guia de Funrural pra pagar?"): vai para LEGISLACAO, com o fundamento
    das retenções;
  - g3-06 (apuração do último trimestre): nenhuma função;
  - g4-04 ("até setembro"): 01/01 a hoje;
  - g4-06 ("de agosto pra trás"): 01/08 a hoje, ao contrário do pedido;
  - g4-09 (safra 25/26): 01/01/2025 a hoje;
  - c-16 (crédito na compra de máquina) e c-18 (futebol), um em lei e outro fora do escopo.
- g4-08 ("Do mês retrasado pra cá…") errou nos dois braços de jeitos diferentes. A base fechou
  em setembro. A correção chamou `vencimentosDoMes` de novembro e as vendas de 01/01 a hoje.
- O seed não tem guia com vencimento em dezembro de 2026. No grupo 1, então, a resposta certa é a
  frase de "não encontrei guias" de dezembro. A regra mede a função e o mês, não o valor.

## Arquivos

- `base.jsonl` e `correcao.jsonl`: uma linha por chamada (aquecimento incluído), com braço,
  commit, modelo, digest, horário, data de referência e a resposta da API.
- `resumo-base.json` e `resumo-correcao.json`: saída de `pontuar.py pontuar`.
- `decisao.json`: saída de `pontuar.py comparar`.
- `paridade-base.txt` e `paridade-correcao.txt`: conferência dos dois bancos antes da rodada.
- `executor.log`: progresso do executor. Saiu do console do Windows em cp1252 e foi convertido
  para UTF-8 sem outra mudança; o roteiro agora exporta `PYTHONUTF8=1`. Os logs das aplicações e
  dos builds ficaram fora: são grandes e não entram na conta.
- `paridade-apuracao-base.txt` e `paridade-apuracao-correcao.txt`: a apuração dos dois bancos,
  conferida depois da rodada.

## Como refazer a pontuação

Da raiz do repositório:

```bash
python docs/avaliacao/ceres-452/pontuar.py pontuar --perguntas docs/avaliacao/treino/perguntas-ceres-452.json --execucoes docs/avaliacao/ceres-452/resultados/2026-11-09/base.jsonl --saida resumo-base.json
```

```bash
python docs/avaliacao/ceres-452/pontuar.py pontuar --perguntas docs/avaliacao/treino/perguntas-ceres-452.json --execucoes docs/avaliacao/ceres-452/resultados/2026-11-09/correcao.jsonl --saida resumo-correcao.json
```

```bash
python docs/avaliacao/ceres-452/pontuar.py comparar --base resumo-base.json --correcao resumo-correcao.json
```

O resultado tem o mesmo conteúdo de `resumo-*.json` e `decisao.json` (conferido antes do PR).

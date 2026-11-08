# Rodada de 2026-11-08 — linha de base e correções da #442 e da #443

Conjunto `2026-11-07.3` (sha256 `b4af268c…0ddf`), regra em
[`../../regra-de-decisao.md`](../../regra-de-decisao.md). As três rodadas no mesmo dia, em
sequência, sob a trava do Ollama: data de referência `2026-11-08` (Brasília) nas três.

| Rodada | Código | Horário | Execuções | Falhas técnicas |
|---|---|---|---|---|
| Linha de base | `main` `4a0b3a8` | 06:02–06:37 | 120 (40 × 3) | 0 |
| Correção #442 | `fix/442-periodo-relativo` `8b4684f` | 06:38–07:09 | 120 | 0 |
| Correção #443 | `fix/443-ressalva-tratamento` `35d90ae` | 07:09–07:38 | 120 | 0 |

Ambiente local, igual nas três: JAR de cada commit, banco próprio (`agrocontabil_442`, criado e
apagado a cada rodada), seed da demonstração, base de lei copiada do banco local (8304 trechos),
`qwen3:4b-instruct` e `bge-m3` no Ollama do contêiner (CPU), aquecidos antes;
`AI_READ_TIMEOUT=240s` e `AI_COTA_HABILITADA=false` (a cota não muda a resposta; com ela, 120
perguntas da mesma conta esgotariam a semana). Conta: produtor principal do seed.

## Resultado por grupo (maioria de 3)

| Grupo | Base | #442 | #443 |
|---|---|---|---|
| `periodo` (12) | 9 | 11 | 9 |
| `produto` (10) | 4 | 4 | 9 |
| `controle-dados` (10) | 10 | 10 | 10 |
| `controle-lei` (8) | 8 | 8 | 7 |

## Regra (com todas as perguntas e sem p443-01 e p443-33)

| Correção | Grupo base → correção | Controles que pioraram | Decisão |
|---|---|---|---|
| #442 | 9 → 11 (sem os dois ids: 9 → 10) | nenhum | **ENTRA** nas duas |
| #443 | 4 → 9 (sem os dois ids: 4 → 9) | p443-35 | **NÃO ENTRA** nas duas |

Perguntas que mudaram (resultado por rodada, base / #442 / #443):

- `periodo`: p443-01, p443-07 e p443-11 erradas na base e certas com a #442; **p443-12 certa na
  base e errada com a #442** (com o período já calculado na mensagem, o modelo trocou
  `vencimentosDoMes` do mês que vem por `proximosVencimentos` de 30 dias).
- `produto`: p443-14, 15, 16, 21 e 22 erradas na base e certas com a #443; p443-19 errada nas três.
- `controle-lei`: **p443-35 certa na base e errada com a #443**: o verbo "ser" ("por ser
  profissão regulamentada") virou candidato a produto no `TratamentoNaPergunta`, casou por
  prefixo com um item do Anexo IX e o texto da tabela tomou o lugar da resposta de lei.

Latência mediana por grupo (s, base / #442 / #443): `periodo` 17,4 / 19,4 / 17,4; `produto`
12,1 / 11,8 / 12,1; `controle-dados` 16,6 / 16,4 / 16,6; `controle-lei` 22,3 / 9,1 / 8,9 (a
primeira rodada aquece o cache de prefixo do Ollama; compare dentro da mesma linha com cuidado).

Arquivos: `base-4a0b3a8.jsonl` (as 120 execuções) e `base-4a0b3a8-resumo.json` (saída do
`pontuar.py`); as rodadas das correções vão nos PRs delas.

# #452: rodada v2d (2026-11-10), inconclusiva por carga em paralelo

**Não pontuada e fora de qualquer veredito.** A medição oficial é a v2e (`../2026-11-10-v2e/`). Os
dados brutos ficam aqui só como registro.

## Condições

| | |
|---|---|
| Base | `d67adc4bbbd3002d33f6a188d882547f1aedaa74` (`origin/main` às 00h05 de 2026-11-10) |
| Correção | `ffea5a8b1ba51080b2152d74eab20353327b6a50` |
| Conjunto | `ceres-452-v2` |
| Data de referência | 2026-11-10 nos dois braços |
| Início | 00h05:20 (-03:00) |
| Parada | 01h48:30, na repetição 3 da base, a pedido da sessão orquestradora |

Na parada, o executor foi encerrado e o script saiu com 18. O trap dele soltou a trava do Ollama e
derrubou as duas aplicações.

## Por que é inconclusiva

Outra sessão rodou Maven na mesma máquina durante a rodada, para o PR #477:

| Horário | Comando |
|---|---|
| 00:17:45–00:37:08 | `mvnw test` |
| 00:37–00:45 | `-Dtest` |
| 00:45:14–01:04:47 | `verify` |
| 01:04–01:08 | `-Dtest` |
| 01:08:40–01:24:12 | `clean verify` |
| 01:24–01:25 | `-Dtest` |
| 01:25:46–01:41:11 | `verify` |

A janela de carga vai, portanto, das **00:17:45 às 01:41:11**. As repetições 1 e 2 dos dois braços
caíram inteiras nela.

Recorte em `janela-de-carga.txt`: uma execução conta como da janela quando o intervalo [`horario`,
`horario` + `segundos`] cruza a janela.

| | Base | Correção |
|---|---|---|
| Chamadas na janela | 80 | 94 |
| Tentativas técnicas na janela | 1 | 20 |
| Estouros de tempo na janela | 1, recuperado na tentativa seguinte | 3 |
| Execuções sem resposta válida | nenhuma | 6: v2-g3-06 r2, v2-g4-01 r2, v2-g4-06 r1 e r2, v2-g4-07 r1 e r2 |
| Mediana válida na janela | 22,9 s | 21,6 s |
| Mediana válida fora da janela | 18,05 s | sem execução fora da janela |

Houve estouro na janela, e ela afetou os braços de forma desigual. Pela regra, a rodada é inconclusiva
e se refaz inteira, nos dois braços, no mesmo dia, com a máquina sem carga. Essa refação é a v2e.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `base.jsonl`, `correcao.jsonl` | execuções até a parada (a base vai até a r3 incompleta; a correção, até a r2) |
| `executor.log` | saída do `executar.py` |
| `paridade-*.txt` | paridade do produtor e geral de cada braço (idênticas entre os braços) |
| `janela-de-carga.txt` | execuções de cada braço dentro e fora da janela de carga |

Não há resumo nem decisão: a rodada não foi pontuada. Não junte estas execuções às de outra rodada.

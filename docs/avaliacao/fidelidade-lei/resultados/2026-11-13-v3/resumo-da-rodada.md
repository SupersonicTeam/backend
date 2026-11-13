# Terceira medição de fidelidade da rota de lei (#269): resumo da rodada

Só números e ids. O resultado completo, gerado pelo `decidir_fidelidade.py`, está em `resultado.md` e `resultado.json`; o manifesto em `execucao.json`; as respostas brutas em `respostas-brutas.jsonl`; as leituras dos juízes (J1, J2, J3) e a chave em `leitura/`.

## Rodada

- Conjunto v3 (`2026-11-13.v3`, sha256 `b62880b3…159568`), regra `2026-11-13.6`, commit `a91f32ef` (head do PR #551), jar sha256 `eb6b90d0…3e3e`, modelo `claude-haiku-5-5`, 3 repetições, semente 20261112.
- Quatro braços, 432 execuções (36 × 3 × 4), intercaladas. Execuções de 12:00:22 a 12:24:57 de 13/11 (24 min 36 s), sem interrupção. Leitura dos juízes e decisão em seguida, sem parada: tempo total da rodada, cerca de 37 minutos.
- Falha técnica: 0 em todos os braços (108 execuções válidas em cada um).

## Desfechos

| Comparação | Desfecho | Motivo |
|---|---|---|
| `desligada` × `camada1` | `NAO_ENTRA` | fora da margem de falso descarte (M2b): 4 perguntas, limite 3 |
| `desligada` × `camada1+juiz+resgate` | `ENTRA` | defeitos de 15 para 0; IC de 80% [8; 22] |
| `desligada` × `camada1+resgate` | `ENTRA` | defeitos de 15 para 1; IC de 80% [7; 21] |

Escolha pela seção 7.4 entre os dois que deram `ENTRA`: `camada1+juiz+resgate` (menos `INFIEL`: 0 contra 1).

## Números por braço (execuções válidas, perguntas com base)

| Braço | Aceitas | INFIEL | COM_RESSALVA citação | COM_RESSALVA outra | FIEL | Falsos descartes (ids) | p50 / p95 (ms) | Tokens médios |
|---|---|---|---|---|---|---|---|---|
| `desligada` | 40 | 15 | 0 | 4 | 21 | — | 2673 / 5813 | 2939 |
| `camada1` | 27 | 0 | 0 | 8 | 19 | F3-02, F3-03, F3-06, F3-26 | 2154 / 5443 | 3209 |
| `camada1+juiz+resgate` | 31 | 0 | 0 | 7 | 24 | F3-02, F3-14 | 2867 / 6809 | 3772 |
| `camada1+resgate` | 33 | 1 | 0 | 7 | 25 | F3-02, F3-03, F3-06 | 2262 / 5665 | 3301 |

Todas as margens de não piora dentro do limite nos dois braços que deram `ENTRA` (M3 recusa falsa 0 ponto, M4 sem lei ausente afirmando, M5 e M6 abaixo de 2,0 ×; p95 de 6809 ms no pior, abaixo do teto de 10 s; M6: 1,28 e 1,12).

## Leitura dos juízes

133 respostas aceitas lidas em 12 lotes. Concordância J1 × J2 de 79,7% e kappa de 0,594 (entre 0,40 e 0,60: o resultado vale, com o aviso da regra). Desacordo de 20,3% (27 itens foram ao J3), abaixo do limite de 30%.

## Desfechos da auditoria e coluna `fidelidade_lei`

Contagem por braço no recado enviado antes da leitura; o arquivo `respostas-brutas.jsonl` traz a linha de auditoria de cada execução. Em resumo: `FIDELIDADE_REPROVADA` aparece em 9 execuções na `camada1`, 9 na `camada1+juiz+resgate` e 7 na `camada1+resgate`; a coluna `fidelidade_lei` tem `APROVADA_RESGATE_JUIZ` em 4 execuções da `camada1+juiz+resgate` e 6 da `camada1+resgate`.

**`FIDELIDADE_REPROVADA` não é desfecho novo desta rodada.** Nasceu com a correção, apareceu nas duas medições anteriores e o classificador do executor sempre o contou como descartada (regra, seção 3). A cláusula da seção 10 (parar antes da leitura) é para desfecho que a correção criasse agora, sem a regra o conhecer: não era o caso.

## Chamadas ao juiz (medidas, não estimadas)

Execuções com `tokens_entrada_juiz` maior que zero:

| Braço | Chamadas | Execuções com rota de lei ou mista | Proporção |
|---|---|---|---|
| `camada1+juiz+resgate` | 41 | 95 | 0,43 |
| `camada1+resgate` | 9 | 97 | 0,09 |
| `camada1` e `desligada` | 0 | 95 | 0 |

A estimativa da sessão da correção era de 0,65 a 0,70 no braço com veto e de 0,05 a 0,10 no só resgate: o segundo bateu, o primeiro ficou abaixo.

## Respostas resgatadas e o rótulo que cada uma recebeu

Execuções com `fidelidade_lei = APROVADA_RESGATE_JUIZ` e o veredito final dos juízes:

- `camada1+juiz+resgate`: F3-27 rep. 2 `FIEL`; F3-54 rep. 1 `FIEL`; F3-54 rep. 3 `FIEL`; F3-12 rep. 1 saiu com desfecho de descartada (não foi lida: não chegou ao produtor).
- `camada1+resgate`: F3-08 rep. 2 `FIEL`; F3-18 rep. 1 `FIEL`; F3-21 rep. 3 `FIEL`; F3-28 rep. 1 `FIEL`; F3-54 rep. 1 `FIEL`; F3-54 rep. 3 `FIEL`.

Nenhuma resposta resgatada foi lida como infiel. Das 9 que chegaram ao produtor, as 9 são `FIEL`.

## Defeito residual

O único `INFIEL` fora do `desligada` é F3-26 rep. 2 no braço `camada1+resgate` (subtipo `CONTRARIO`), com `fidelidade_lei = APROVADA_CITACAO_CORRIGIDA`: não é uma resposta resgatada, passou pela camada 1.

## Leitura da comparação entre os dois braços de resgate

A `camada1+resgate` tem o limiar da camada 1 cheio e a `camada1+juiz+resgate` o afrouxado (regra, seção 10), então a diferença entre os dois soma o veto e o limiar. A comparação limpa do resgate é `camada1` × `camada1+resgate`: aceitas 27 contra 33, `INFIEL` 0 contra 1, `FIEL` 19 contra 25, falsos descartes 4 contra 3, tokens médios 3209 contra 3301.

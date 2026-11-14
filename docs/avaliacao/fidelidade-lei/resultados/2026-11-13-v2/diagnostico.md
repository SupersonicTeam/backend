# Diagnóstico da segunda medição de fidelidade da rota de lei (#269)

Rodada de 2026-11-13, commit `d79b57a9f4ae75eacf3d301bb6c44633839c103d` (PR #544), conjunto v2 (`2026-11-13.v2`), regra `2026-11-12.4`. Só contagens e ids: nenhum texto de pergunta, resposta ou gabarito.

## Valores de `fidelidade_lei` por braço (108 execuções cada)

| valor | desligada | camada1 | camada1+juiz |
|---|---|---|---|
| APROVADA | 0 | 19 | 19 |
| APROVADA_CITACAO_CORRIGIDA | 0 | 7 | 6 |
| REPROVADA_COBERTURA | 0 | 3 | 0 |
| REPROVADA_JUIZ_NAO_SUSTENTA | 0 | 0 | 6 |
| REPROVADA_NUMERO | 0 | 7 | 6 |
| REPROVADA_POLARIDADE | 0 | 3 | 4 |
| sem valor | 108 | 69 | 67 |

## Desfechos da auditoria por braço

| desfecho | desligada | camada1 | camada1+juiz |
|---|---|---|---|
| AFIRMACAO_SOBRE_DADOS | 7 | 0 | 0 |
| CITACAO_INVALIDA | 10 | 15 | 13 |
| FIDELIDADE_REPROVADA | 0 | 11 | 13 |
| FORA_DE_ESCOPO | 40 | 40 | 40 |
| NUMERO_SEM_FONTE | 2 | 0 | 0 |
| RESPONDIDA | 37 | 27 | 25 |
| RESPOSTA_CORTADA | 0 | 3 | 4 |
| SEM_FUNDAMENTO_LEGAL | 8 | 8 | 8 |
| SEM_RESPOSTA | 4 | 4 | 5 |

## Desfecho × `fidelidade_lei`, só na rota LEGISLACAO com base legal

| desfecho | fidelidade_lei | desligada | camada1 | camada1+juiz |
|---|---|---|---|---|
| CITACAO_INVALIDA | sem valor | 10 | 15 | 13 |
| FIDELIDADE_REPROVADA | REPROVADA_COBERTURA | 0 | 3 | 0 |
| FIDELIDADE_REPROVADA | REPROVADA_JUIZ_NAO_SUSTENTA | 0 | 0 | 4 |
| FIDELIDADE_REPROVADA | REPROVADA_NUMERO | 0 | 5 | 5 |
| FIDELIDADE_REPROVADA | REPROVADA_POLARIDADE | 0 | 3 | 4 |
| NUMERO_SEM_FONTE | sem valor | 2 | 0 | 0 |
| RESPONDIDA | APROVADA | 0 | 16 | 14 |
| RESPONDIDA | APROVADA_CITACAO_CORRIGIDA | 0 | 3 | 4 |
| RESPONDIDA | sem valor | 36 | 0 | 0 |
| RESPOSTA_CORTADA | sem valor | 0 | 3 | 4 |
| SEM_FUNDAMENTO_LEGAL | sem valor | 5 | 5 | 5 |

## Falsos descartes: (desfecho, `fidelidade_lei`) por braço e repetição

| pergunta | braço | rep 1 | rep 2 | rep 3 |
|---|---|---|---|---|
| F2-08 | desligada | RESPONDIDA / sem valor | RESPONDIDA / sem valor | RESPONDIDA / sem valor |
| F2-08 | camada1 | CITACAO_INVALIDA / sem valor | CITACAO_INVALIDA / sem valor | CITACAO_INVALIDA / sem valor |
| F2-08 | camada1+juiz | CITACAO_INVALIDA / sem valor | CITACAO_INVALIDA / sem valor | RESPOSTA_CORTADA / sem valor |
| F2-10 | desligada | RESPONDIDA / sem valor | RESPONDIDA / sem valor | RESPONDIDA / sem valor |
| F2-10 | camada1 | FIDELIDADE_REPROVADA / REPROVADA_POLARIDADE | RESPONDIDA / APROVADA | FIDELIDADE_REPROVADA / REPROVADA_POLARIDADE |
| F2-10 | camada1+juiz | FIDELIDADE_REPROVADA / REPROVADA_POLARIDADE | FIDELIDADE_REPROVADA / REPROVADA_POLARIDADE | FIDELIDADE_REPROVADA / REPROVADA_POLARIDADE |
| F2-23 | desligada | RESPONDIDA / sem valor | RESPONDIDA / sem valor | RESPONDIDA / sem valor |
| F2-23 | camada1 | FIDELIDADE_REPROVADA / REPROVADA_NUMERO | FIDELIDADE_REPROVADA / REPROVADA_NUMERO | FIDELIDADE_REPROVADA / REPROVADA_NUMERO |
| F2-23 | camada1+juiz | FIDELIDADE_REPROVADA / REPROVADA_NUMERO | FIDELIDADE_REPROVADA / REPROVADA_NUMERO | FIDELIDADE_REPROVADA / REPROVADA_NUMERO |

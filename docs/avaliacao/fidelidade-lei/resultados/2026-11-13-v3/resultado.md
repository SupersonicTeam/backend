# Resultado da medição de fidelidade da rota de lei (#269)

Regra `2026-11-13.6`; commit `a91f32ef`; modelo `claude-haiku-5-5`; 3 repetições; braços ['desligada', 'camada1', 'camada1+juiz+resgate', 'camada1+resgate']. Cada braço é comparado ao desligada.

## desligada × camada1: NAO_ENTRA

fora da margem: M2b falso descarte por pergunta

| Medida | desligada | camada1 |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 15 | 0 |
| COM_RESSALVA/citação | 0 | 0 |
| COM_RESSALVA/outra | 4 | 8 |
| FIEL | 21 | 19 |
| aceitas (com base) | 40 | 27 |
| recusas (com base) | 6 | 6 |
| descartadas (com base) | 44 | 57 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 44.4% | 30.0% |
| taxa de recusa falsa | 6.7% | 6.7% |
| INFIEL entre as aceitas | 37.5% | 0.0% |
| p50 / p95 (ms) | 2673 / 5813 | 2154 / 5443 |
| tokens médios | 2939 | 3209 |

**M1:** defeitos 15 → 0 (queda 15, 100%); IC de 80% da queda por pergunta: [8.0, 22.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 15 | 4 | sim |
| M1 defeitos (queda relativa) | 1.0 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 8.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 4 | 3 | NÃO |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 0.8058361391694725 | 2.0 | sim |
| M5 p95 | 0.9363495613280578 | 2.0 | sim |
| M5 p95 (teto absoluto) | 5443 | 10000 | sim |
| M6 tokens | 1.0919277708122004 | 2.0 | sim |
| Piora de INFIEL | -15 | 1 | sim |
| Piora de ressalva de citação | 0 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -4 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 4, camada1 0. Com defeito em qualquer repetição: desligada 8, camada1 0. Regressões: nenhuma. Falsos descartes: ['F3-02', 'F3-03', 'F3-06', 'F3-26']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## desligada × camada1+juiz+resgate: ENTRA

defeitos de fidelidade de 15 para 0 (queda de 100%), IC de 80% acima de zero e nenhuma margem violada

| Medida | desligada | camada1+juiz+resgate |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 15 | 0 |
| COM_RESSALVA/citação | 0 | 0 |
| COM_RESSALVA/outra | 4 | 7 |
| FIEL | 21 | 24 |
| aceitas (com base) | 40 | 31 |
| recusas (com base) | 6 | 6 |
| descartadas (com base) | 44 | 53 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 44.4% | 34.4% |
| taxa de recusa falsa | 6.7% | 6.7% |
| INFIEL entre as aceitas | 37.5% | 0.0% |
| p50 / p95 (ms) | 2673 / 5813 | 2867 / 6809 |
| tokens médios | 2939 | 3772 |

**M1:** defeitos 15 → 0 (queda 15, 100%); IC de 80% da queda por pergunta: [8.0, 22.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 15 | 4 | sim |
| M1 defeitos (queda relativa) | 1.0 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 8.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 2 | 3 | sim |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 1.0725776281331836 | 2.0 | sim |
| M5 p95 | 1.1713400997763632 | 2.0 | sim |
| M5 p95 (teto absoluto) | 6809 | 10000 | sim |
| M6 tokens | 1.2835014522703931 | 2.0 | sim |
| Piora de INFIEL | -15 | 1 | sim |
| Piora de ressalva de citação | 0 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -4 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 4, camada1+juiz+resgate 0. Com defeito em qualquer repetição: desligada 8, camada1+juiz+resgate 0. Regressões: nenhuma. Falsos descartes: ['F3-02', 'F3-14']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## desligada × camada1+resgate: ENTRA

defeitos de fidelidade de 15 para 1 (queda de 93%), IC de 80% acima de zero e nenhuma margem violada

| Medida | desligada | camada1+resgate |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 15 | 1 |
| COM_RESSALVA/citação | 0 | 0 |
| COM_RESSALVA/outra | 4 | 7 |
| FIEL | 21 | 25 |
| aceitas (com base) | 40 | 33 |
| recusas (com base) | 6 | 6 |
| descartadas (com base) | 44 | 51 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 44.4% | 36.7% |
| taxa de recusa falsa | 6.7% | 6.7% |
| INFIEL entre as aceitas | 37.5% | 3.0% |
| p50 / p95 (ms) | 2673 / 5813 | 2262 / 5665 |
| tokens médios | 2939 | 3301 |

**M1:** defeitos 15 → 1 (queda 14, 93%); IC de 80% da queda por pergunta: [7.0, 21.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 14 | 4 | sim |
| M1 defeitos (queda relativa) | 0.9333 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 7.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 3 | 3 | sim |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 0.8462401795735129 | 2.0 | sim |
| M5 p95 | 0.9745398245312231 | 2.0 | sim |
| M5 p95 (teto absoluto) | 5665 | 10000 | sim |
| M6 tokens | 1.12325081749277 | 2.0 | sim |
| Piora de INFIEL | -14 | 1 | sim |
| Piora de ressalva de citação | 0 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -4 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 4, camada1+resgate 0. Com defeito em qualquer repetição: desligada 8, camada1+resgate 1. Regressões: nenhuma. Falsos descartes: ['F3-02', 'F3-03', 'F3-06']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## Escolha entre os braços

Escolhido: camada1+juiz+resgate (candidatos com ENTRA, em ordem: ['camada1+juiz+resgate', 'camada1+resgate']); critério: menos INFIEL, depois menos falsos descartes, depois menos tokens.

## Leitura dos juízes

Itens lidos: 133; concordância J1 × J2: 79.7%; kappa: 0.594; itens ao J3: 27. Juízes: {'J1': 'opus', 'J2': 'sonnet', 'J3': 'opus'}. **Aviso: kappa entre 0,40 e 0,60.**

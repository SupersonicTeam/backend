# Resultado da medição de fidelidade da rota de lei (#269)

Regra `2026-11-12.4`; commit `ed5a81fb40ff`; modelo `claude-haiku-5-5`; 3 repetições; braços ['desligada', 'camada1', 'camada1+juiz']. Cada braço é comparado ao desligada.

## desligada × camada1: NAO_ENTRA

fora da margem: M2b falso descarte por pergunta

| Medida | desligada | camada1 |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 10 | 1 |
| COM_RESSALVA/citação | 7 | 0 |
| COM_RESSALVA/outra | 6 | 0 |
| FIEL | 27 | 20 |
| aceitas (com base) | 50 | 21 |
| recusas (com base) | 15 | 15 |
| descartadas (com base) | 25 | 54 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 55.6% | 23.3% |
| taxa de recusa falsa | 16.7% | 16.7% |
| INFIEL entre as aceitas | 20.0% | 4.8% |
| p50 / p95 (ms) | 2156 / 5464 | 2162 / 5466 |
| tokens médios | 2836 | 2991 |

**M1:** defeitos 17 → 1 (queda 16, 94%); IC de 80% da queda por pergunta: [10.0, 23.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 16 | 4 | sim |
| M1 defeitos (queda relativa) | 0.9412 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 10.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 8 | 3 | NÃO |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 1.00278293135436 | 2.0 | sim |
| M5 p95 | 1.0003660322108345 | 2.0 | sim |
| M5 p95 (teto absoluto) | 5466 | 10000 | sim |
| M6 tokens | 1.0547136178437106 | 2.0 | sim |
| Piora de INFIEL | -9 | 1 | sim |
| Piora de ressalva de citação | -7 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -5 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 5, camada1 0. Com defeito em qualquer repetição: desligada 9, camada1 1. Regressões: nenhuma. Falsos descartes: ['FL08', 'FL17', 'FL41', 'FL45', 'FL59', 'FL69', 'FL82', 'FL84']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## desligada × camada1+juiz: NAO_ENTRA

fora da margem: M2b falso descarte por pergunta

| Medida | desligada | camada1+juiz |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 10 | 3 |
| COM_RESSALVA/citação | 7 | 0 |
| COM_RESSALVA/outra | 6 | 2 |
| FIEL | 27 | 26 |
| aceitas (com base) | 50 | 31 |
| recusas (com base) | 15 | 15 |
| descartadas (com base) | 25 | 44 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 55.6% | 34.4% |
| taxa de recusa falsa | 16.7% | 16.7% |
| INFIEL entre as aceitas | 20.0% | 9.7% |
| p50 / p95 (ms) | 2156 / 5464 | 2639 / 6550 |
| tokens médios | 2836 | 3298 |

**M1:** defeitos 17 → 3 (queda 14, 82%); IC de 80% da queda por pergunta: [8.0, 20.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 14 | 4 | sim |
| M1 defeitos (queda relativa) | 0.8235 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 8.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 6 | 3 | NÃO |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 1.224025974025974 | 2.0 | sim |
| M5 p95 | 1.1987554904831625 | 2.0 | sim |
| M5 p95 (teto absoluto) | 6550 | 10000 | sim |
| M6 tokens | 1.1629394327204345 | 2.0 | sim |
| Piora de INFIEL | -7 | 1 | sim |
| Piora de ressalva de citação | -7 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -5 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 5, camada1+juiz 0. Com defeito em qualquer repetição: desligada 9, camada1+juiz 3. Regressões: nenhuma. Falsos descartes: ['FL08', 'FL41', 'FL45', 'FL59', 'FL69', 'FL84']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## Escolha entre os braços

Escolhido: nenhum (candidatos com ENTRA, em ordem: []); critério: nenhum braço deu ENTRA.

## Leitura dos juízes

Itens lidos: 102; concordância J1 × J2: 93.1%; kappa: 0.841; itens ao J3: 7. Juízes: {'J1': 'opus', 'J2': 'sonnet', 'J3': 'opus'}.

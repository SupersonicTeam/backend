# Resultado da medição de fidelidade da rota de lei (#269)

Regra `2026-11-12.4`; commit `d79b57a9f4ae75eacf3d301bb6c44633839c103d`; modelo `claude-haiku-5-5`; 3 repetições; braços ['desligada', 'camada1', 'camada1+juiz']. Cada braço é comparado ao desligada.

## desligada × camada1: ENTRA

defeitos de fidelidade de 9 para 0 (queda de 100%), IC de 80% acima de zero e nenhuma margem violada

| Medida | desligada | camada1 |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 3 | 0 |
| COM_RESSALVA/citação | 6 | 0 |
| COM_RESSALVA/outra | 6 | 0 |
| FIEL | 21 | 19 |
| aceitas (com base) | 36 | 19 |
| recusas (com base) | 30 | 30 |
| descartadas (com base) | 24 | 41 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 40.0% | 21.1% |
| taxa de recusa falsa | 33.3% | 33.3% |
| INFIEL entre as aceitas | 8.3% | 0.0% |
| p50 / p95 (ms) | 1790 / 5195 | 1488 / 5260 |
| tokens médios | 2290 | 2497 |

**M1:** defeitos 9 → 0 (queda 9, 100%); IC de 80% da queda por pergunta: [3.0, 15.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 9 | 4 | sim |
| M1 defeitos (queda relativa) | 1.0 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 3.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 3 | 3 | sim |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 0.8312849162011173 | 2.0 | sim |
| M5 p95 | 1.012512030798845 | 2.0 | sim |
| M5 p95 (teto absoluto) | 5260 | 10000 | sim |
| M6 tokens | 1.0905014334178424 | 2.0 | sim |
| Piora de INFIEL | -3 | 1 | sim |
| Piora de ressalva de citação | -6 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -3 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 3, camada1 0. Com defeito em qualquer repetição: desligada 4, camada1 0. Regressões: nenhuma. Falsos descartes: ['F2-08', 'F2-10', 'F2-23']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## desligada × camada1+juiz: ENTRA

defeitos de fidelidade de 9 para 0 (queda de 100%), IC de 80% acima de zero e nenhuma margem violada

| Medida | desligada | camada1+juiz |
|---|---|---|
| execuções | 108 | 108 |
| falhas técnicas | 0 | 0 |
| válidas com base | 90 | 90 |
| INFIEL | 3 | 0 |
| COM_RESSALVA/citação | 6 | 0 |
| COM_RESSALVA/outra | 6 | 0 |
| FIEL | 21 | 18 |
| aceitas (com base) | 36 | 18 |
| recusas (com base) | 30 | 30 |
| descartadas (com base) | 24 | 42 |
| lei ausente que afirma | 0 | 0 |
| taxa de aceitas (M2, informativa) | 40.0% | 20.0% |
| taxa de recusa falsa | 33.3% | 33.3% |
| INFIEL entre as aceitas | 8.3% | 0.0% |
| p50 / p95 (ms) | 1790 / 5195 | 1425 / 6418 |
| tokens médios | 2290 | 2961 |

**M1:** defeitos 9 → 0 (queda 9, 100%); IC de 80% da queda por pergunta: [3.0, 15.0] (30 perguntas).

### Testes de melhora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M1 defeitos (queda absoluta) | 9 | 4 | sim |
| M1 defeitos (queda relativa) | 1.0 | 0.25 | sim |
| M1 IC de 80% (limite inferior) | 3.0 | 0 | sim |

### Testes de não piora

| Teste | Valor | Limite | OK |
|---|---|---|---|
| M2b falso descarte por pergunta | 3 | 3 | sim |
| M3 recusa falsa | 0.0 | 0.05 | sim |
| M4 lei ausente (execuções que afirmam) | 0 | 1 | sim |
| M4 lei ausente (perguntas que passam a afirmar em maioria) | 0 | 0 | sim |
| M5 p50 | 0.7960893854748603 | 2.0 | sim |
| M5 p95 | 1.2354186717998075 | 2.0 | sim |
| M5 p95 (teto absoluto) | 6418 | 10000 | sim |
| M6 tokens | 1.29294289376534 | 2.0 | sim |
| Piora de INFIEL | -3 | 1 | sim |
| Piora de ressalva de citação | -6 | 1 | sim |
| Regressões por pergunta | 0 | 1 | sim |
| Perguntas defeituosas por maioria | -3 | 0 | sim |

### Por pergunta

Defeituosas por maioria: desligada 3, camada1+juiz 0. Com defeito em qualquer repetição: desligada 4, camada1+juiz 0. Regressões: nenhuma. Falsos descartes: ['F2-08', 'F2-10', 'F2-23']. Ausentes que passam a afirmar: nenhuma. Excluídas: nenhuma.

## Escolha entre os braços

Escolhido: camada1 (candidatos com ENTRA, em ordem: ['camada1', 'camada1+juiz']); critério: menos INFIEL, depois menos falsos descartes, depois menos tokens.

## Leitura dos juízes

Itens lidos: 73; concordância J1 × J2: 83.6%; kappa: 0.491; itens ao J3: 12. Juízes: {'J1': 'opus', 'J2': 'sonnet', 'J3': 'opus'}. **Aviso: kappa entre 0,40 e 0,60.**

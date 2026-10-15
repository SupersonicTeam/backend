# Avaliação da Ceres — ollama/qwen3:4b-instruct

Gerado pelo comando de avaliação (issue #158). Não editar à mão: rode de novo. Uma pergunta de aquecimento roda antes e é descartada (carga do modelo).

## Condições

| Item | Valor |
|---|---|
| Executado em | 2026-10-15T13:19:42-03:00 |
| "Hoje" da Ceres | 2026-09-15 |
| Gabarito | 2026-10-15.1 (7 perguntas) |
| Modelo de chat | ollama/qwen3:4b-instruct |
| Embedding | ollama/bge-m3 |
| Hardware | Ollama em container Docker sem acesso a GPU: inferencia so em CPU |
| Hardware | Windows 11 amd64, 16 nucleos logicos (Intel64 Family 6 Model 154 Stepping 3, GenuineIntel), Java 26.0.1 |
| Hardware | Memoria total: 31.7 GB |
| Hardware | Ollama 0.35.0 |
| Hardware | qwen3:4b-instruct: 3.6 GB, 0% na GPU |
| Hardware | bge-m3:latest: 1.1 GB, 0% na GPU |
| Duração total | 564 s |

## Métricas

| Métrica | Resultado |
|---|---|
| Acurácia de rota | 85.7% (6/7) |
| Acerto de função | 71.4% (5/7) |
| Acerto de parâmetros | 57.1% (4/7) |
| Exatidão numérica | 57.1% (4/7) |
| Citação válida | 57.1% (4/7) |
| Recusa correta | — |
| Sem vazamento | — |
| Sem falha técnica | 100.0% (7/7) |
| Latência p50 | 74.7 s |
| Latência p95 | 159.1 s |

## Por categoria

| Categoria | Perguntas | Rota | Número | Citação | Recusa | p50 | p95 |
|---|---|---|---|---|---|---|---|
| MISTO | 7 | 85.7% (6/7) | 57.1% (4/7) | 57.1% (4/7) | — | 74.7 s | 159.1 s |

## Por pergunta

Legenda: ✓ acertou, ✗ errou, · não se aplica. As respostas completas estão no JSON ao lado.

| Id | Rota obtida | Desfecho | Rota | Função | Parâm. | Número | Citação | Recusa | Vazou | Latência |
|---|---|---|---|---|---|---|---|---|---|---|
| R02 | MISTO | RESPONDIDA (dados RESPONDIDA, lei CITACAO_INVALIDA, reescrita) | ✓ | ✓ | ✓ | ✓ | ✓ | · | · | 159.1 s |
| M01 | MISTO | RESPONDIDA (dados RESPONDIDA, lei RESPONDIDA, reescrita) | ✓ | ✓ | ✓ | ✓ | ✓ | · | · | 73.5 s |
| M02 | MISTO | RESPONDIDA (dados RESPONDIDA, lei RESPONDIDA, reescrita) | ✓ | ✓ | ✓ | ✓ | ✓ | · | · | 74.7 s |
| M03 | MISTO | RESPONDIDA (dados NUMERO_SEM_FONTE, lei RESPONDIDA, reescrita) | ✓ | ✓ | ✓ | ✗ | ✗ | · | · | 92.5 s |
| M04 | LEGISLACAO | CITACAO_INVALIDA | ✗ | ✗ | ✗ | ✗ | ✗ | · | · | 23.1 s |
| M05 | MISTO | RESPONDIDA (dados RESPONDIDA, lei SEM_FUNDAMENTO_LEGAL, reescrita) | ✓ | ✗ | ✗ | ✗ | ✗ | · | · | 51.1 s |
| M06 | MISTO | RESPONDIDA (dados RESPONDIDA, lei RESPONDIDA, reescrita) | ✓ | ✓ | ✗ | ✓ | ✓ | · | · | 90.2 s |

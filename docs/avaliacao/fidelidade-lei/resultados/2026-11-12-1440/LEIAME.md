# Rodada de 2026-11-12 (medição de fidelidade da rota de lei, #269)

Resultado da regra `2026-11-12.4` sobre o conjunto `2026-11-12.4` (sha256 do conjunto no
`execucao.json`), três braços no mesmo jar (head `ed5a81fb` do PR #544): `desligada`, `camada1` e
`camada1+juiz`, 3 repetições, Haiku 5.5.

- `resultado.json` e `resultado.md`: a decisão (por comparação contra o `desligada`), M1 a M6 por braço,
  as três contagens, a concordância dos juízes e a escolha entre braços. Só números e ids.
- `execucao.json`: o manifesto da rodada (commit, jar, ambiente dos braços sem segredo, contagens).

Ficam **fora** do repositório, de propósito: as respostas brutas, a chave do embaralhamento e as
leituras dos juízes (trazem texto de resposta). Ninguém precisa delas para conferir a decisão; quem
quiser refazer roda o executor de novo.

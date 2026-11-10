# Regra de decisão da medição da #452, rodada v2f

Gravada em 2026-11-10, **antes** da rodada v2f e antes de a sessão avaliadora ver a nova versão
da correção. Vale a regra v2 (`regra-de-decisao-v2.md`) inteira, com uma mudança: a pergunta
`v2-g4-06` sai da decisão. Mudar esta regra depois de ver um resultado invalida a medição.

## Por que a `v2-g4-06` sai

- Depois da v2e (`resultados/2026-11-10-v2e/`), o Murillo decidiu corrigir o #459 antes do merge.
  A sessão da correção vai mexer no período aberto, o grupo 4.
- O comportamento da `v2-g4-06` na v2e foi descrito à sessão da correção. A nova correção pode ter
  sido ajustada para essa pergunta, então ela conta como **contaminada**.
- Só a decisão deixa de contar com ela. A pergunta segue no conjunto e roda nos dois braços, para
  que o tempo dela fique registrado.

## O que muda em relação à regra v2

- A `v2-g4-06` roda nos dois braços como as outras: 3 repetições, até 3 tentativas por falha
  técnica, na mesma ordem do conjunto.
- Ela não conta no grupo 4, que passa a ter **9 perguntas**. Também fica fora da mediana de
  latência do grupo.
- Se ela ficar `sem_medicao` num braço, a medição **não** fica inconclusiva por isso.
- O relatório a mostra à parte, nos dois braços: o resultado (certa, errada ou `sem_medicao`) e o
  tempo de cada execução, inclusive as tentativas técnicas.

Todo o resto segue a regra v2: o conjunto `ceres-452-v2` com o mesmo sha256, os outros grupos, a
contagem por maioria, o empate como errada, as condições de INCONCLUSIVA e de ENTRA e a latência
fora da decisão.

## Braços

- **Base:** a `main` do dia da rodada.
- **Correção:** o commit que a sessão orquestradora indicar quando a correção estiver pronta.
- Os dois SHA vão no relatório, como na v2e. A sessão avaliadora não fala com a sessão da correção.

## Como rodar e pontuar

O id vai depois do conjunto, como quinto argumento do script:

```bash
docs/avaliacao/ceres-452/rodar_medicao_452.sh <worktree da main> <worktree da correção> <saída> docs/avaliacao/treino/perguntas-ceres-452-v2.json v2-g4-06
```

- O script confere, antes de pegar a trava, que o id está no conjunto. No fim, passa o id às duas
  pontuações (`pontuar.py pontuar --fora-da-decisao v2-g4-06`).
- O resumo de cada braço tira a pergunta de `por_pergunta`, da contagem e da mediana do grupo e a
  grava em `fora_da_decisao`, com o resultado e o tempo de cada execução.
- O `pontuar.py comparar` mostra a pergunta à parte. Se os dois resumos tiraram perguntas
  diferentes da decisão, a rodada é INCONCLUSIVA.
- Para refazer a pontuação, use a mesma opção nos dois braços. Sem ela, o resumo sai como o da
  regra v2, com a pergunta no grupo 4.

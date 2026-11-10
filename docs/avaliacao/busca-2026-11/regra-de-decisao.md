# Regra de decisão da medição da busca de lei (#436)

Gravada antes de qualquer rodada e não muda depois. O script [`decisao.py`](decisao.py) aplica esta
regra às saídas e foi commitado junto com ela.

## Conjunto

`docs/avaliacao/treino/perguntas-lei-busca-2026-11.json`, versão do commit `6a7058a`, sha256
`0d6c08f2194da03abbc0cb585ddfd2fd24934b2ae73ea67f9909355068bdedf5`: 40 perguntas, 20 `geral`,
10 `setor` e 10 `ausente`. Antes de medir, e uma vez só, uma pergunta `geral` cujo `esperado` ou
`aceito` caia numa faixa de alguma variante pode passar a `setor`; a troca fica registrada por id e
nada mais muda no conjunto.

## Unidade e rótulo

- Unidade: a pergunta.
- Rótulo de cada execução, pelo [`busca_contagem.py`](busca_contagem.py): `certa` (desfecho
  `RESPONDIDA` com alguma citação em `esperado ∪ aceito`), `outro_artigo` (outra `RESPONDIDA`),
  `descarte` (desfecho diferente de `RESPONDIDA`) ou `erro` (`ERRO_MODELO`).
- Rótulo da pergunta: o de mais da metade das 3 repetições; sem essa maioria, `sem_maioria`, que
  não é `certa`, nem `outro_artigo`, nem `descarte`.

## Braços

Nesta ordem fixa, na mesma máquina e no mesmo dia, com o `qwen3:4b-instruct` e o `bge-m3` locais:

| Braço | Código | Partição |
|-------|--------|----------|
| **A** | `main` (antes da mudança), com as ferramentas de medição deste branch | não existe |
| **B** | build com a mudança | `desligado` (a busca chamada como hoje) |
| **C** | build com a mudança | `regimes-especificos` |
| **D** | build com a mudança | `ampliada` |

- Antes de C e D, confere-se que os `entregues` de B são idênticos aos de A nas 40 perguntas (mesma
  posição, norma, dispositivo e similaridade). Se não forem, a medição para e a sessão orquestradora
  é avisada.
- Cada braço roda uma vez: `DiagnosticoCitacoesLeiManual` com 3 repetições e
  `DiagnosticoRecallLeiManual`, com `AI_READ_TIMEOUT=240s` e
  `-Dspring.docker.compose.enabled=false`. Todas as saídas vão para o PR.
- Cada braço registra: SHA do código, digest do `qwen3:4b-instruct` e do `bge-m3` (`ollama list`),
  contagem de trechos por norma, início e fim. O tempo se compara só pela 1ª repetição de cada
  pergunta (as outras vêm com o prompt em cache).

## Erro do modelo

Uma execução com `ERRO_MODELO` é refeita uma vez, no fim do braço, com o mesmo comando restrito às
perguntas com erro (`-Ddiagnostico.ids=<ids>`): cada execução com erro é trocada pela execução de
mesmo índice da nova rodada. Se a execução de mesmo índice também der `ERRO_MODELO`, a pergunta sai
dos quatro braços e o relatório diz quais saíram. Com mais de 2 perguntas fora, refaz-se tudo.

## Comparação

A comparação de C e de D é com **B**. Sobre as perguntas que ficam nos quatro braços:

- **ruído** = perguntas cujo rótulo muda entre A e B;
- **(a)** perguntas que viram `certa` (rótulo em B diferente de `certa`, na variante `certa`) menos
  perguntas que deixam de ser `certa` (em B `certa`, na variante outro rótulo) **≥ 3**;
- **(b)** perguntas que passam a `outro_artigo` **≤** perguntas que deixam de ser `outro_artigo`;
- **(c)** nenhuma pergunta `setor` deixa de ser `certa`;
- **(d)** nenhuma pergunta `ausente` passa de `descarte` (em B) a `RESPONDIDA` (`certa` ou
  `outro_artigo` na variante);
- **(e)** o saldo de (a) é **maior** que o ruído.

Uma variante passa só se cumpre as cinco. Se as duas passarem, fica **C**, a menos que **D** ganhe
de C, pergunta a pergunta, por pelo menos 2 (perguntas que viram `certa` de C para D menos as que
deixam de ser `certa` ≥ 2) **sem mais `outro_artigo`** (número de perguntas `outro_artigo` em D ≤
em C).

"Não passou" quer dizer ficar como está, não que a mudança piora. A contagem por execução (precisão
e cobertura) vai no relatório só como descrição, como o recall@5 e a posição 1, o tempo e os
disparos da guarda (`REGIME_ESPECIFICO_SEM_SETOR`).

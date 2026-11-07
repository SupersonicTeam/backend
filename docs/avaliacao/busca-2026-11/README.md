# Medição da busca de lei (#436)

Medição da partição dos trechos de caso especial na busca da rota de lei da Ceres, num conjunto
novo de perguntas, de uso único. O conjunto não serve para ajustar a Ceres (prompt, exemplos,
classificador, busca): usado num ajuste, deixa de medir.

## Arquivos

| Arquivo | O que é |
|---------|---------|
| [`../treino/perguntas-lei-busca-2026-11.json`](../treino/perguntas-lei-busca-2026-11.json) | O conjunto congelado: 40 perguntas (20 `geral`, 10 `setor`, 10 `ausente`), com o `.sha256` ao lado. Versão final no commit `6a7058a`, depois de duas rodadas de troca pedidas pela conferência contra as perguntas de avaliação |
| [`regra-de-decisao.md`](regra-de-decisao.md) | A regra de decisão, gravada antes de qualquer rodada |
| [`busca_contagem.py`](busca_contagem.py) | Rótulo de cada execução e de cada pergunta, top 5 e posição 1 dos trechos entregues (escrito antes da medição e aplicado sem mudança) |
| [`decisao.py`](decisao.py) | Aplica a regra de decisão às saídas dos braços |
| [`relatorio.py`](relatorio.py) | Tabelas do relatório: rótulos por braço e as medidas descritivas |
| [`conferir_chaves.py`](conferir_chaves.py) | Confere no Postgres local que toda chave de `esperado` e `aceito` existe na base ([saída](conferir_chaves-saida.txt)) |
| [`conferir_faixas.py`](conferir_faixas.py) | Confere, antes de medir, como o conjunto cai nas faixas das variantes ([saída](conferir_faixas-saida.txt)) |
| [`refazer_erros.py`](refazer_erros.py) | Troca a execução com `ERRO_MODELO` pela refeita de mesmo índice |
| [`rodar_braco.sh`](rodar_braco.sh) | Roda um braço: trava do Ollama, metadados, `DiagnosticoRecallLeiManual` e `DiagnosticoCitacoesLeiManual` |
| `resultados/` | Saídas de cada braço: `<braço>-citacoes.json` (a entrada do `busca_contagem.py`), `<braço>-recall.json`, `<braço>-metadados.txt` e, quando houve erro do modelo, a saída bruta e a refeita |

Testes dos scripts (só biblioteca padrão):

```bash
python -m unittest discover -s docs/avaliacao/busca-2026-11 -p "test_*.py"
```

## Como um braço roda

```bash
bash docs/avaliacao/busca-2026-11/rodar_braco.sh A "$PWD" - <pasta-de-resultados> <pasta-de-logs>
```

O terceiro argumento é a partição: `-` no braço A (código sem a propriedade), ou `desligado`,
`regimes-especificos` e `ampliada`. O roteiro pega a trava compartilhada do Ollama
(`agroconia-ollama-lock`, no temporário do usuário) e sai sem rodar se ela é de outra sessão. A senha
do banco vai só no ambiente do comando do Maven, lida do `.env`.

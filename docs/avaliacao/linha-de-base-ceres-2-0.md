# Linha de base da Ceres 2.0 (issue #264)

> **Pendente: rodar com o modelo real.** O ambiente em que a régua foi implementada não tinha o
> Ollama com `qwen3:4b-instruct` e `bge-m3`, então nenhum número abaixo foi medido. **Não
> preencha à mão nem estime:** rode o comando e cole as tabelas do relatório gerado.

É o ponto de comparação das etapas 2 a 8 da milestone Ceres 2.0. Toda etapa registra a tabela
"antes/depois" contra esta linha de base, **no mesmo gabarito e no mesmo seed**, com 3 repetições.

## O que mede

Estado da Ceres **antes** da primeira mudança da milestone: `qwen3:4b-instruct` com classificação
de rota por LLM, gabarito `2026-10-31.1` (81 perguntas, 20 casos adversários de rota), seed `v2`.

As perguntas marcadas com `pendente` no gabarito (T01, T04 a T08, T10, T11, X17, X18) dependem
das funções da #274 e aqui medem só a rota: é o retrato do "antes", em que essas perguntas ainda
caem em negativa falsa.

## Como rodar (3 repetições)

**Rode no commit desta issue**, não na `main` mais nova: se a #274 já tiver entrado, o resultado
deixa de ser o "antes". Com o PR da #264 mergeado, use o commit do merge:

```bash
git fetch origin main
git switch --detach <commit do merge da #264>
# Postgres com a legislação ingerida (#156) e o Ollama com os dois modelos:
docker start agrocontabil-postgres agrocontabil-ollama
ollama pull qwen3:4b-instruct && ollama pull bge-m3
export DB_PASSWORD=...   # sem o docker-compose do Spring, o dev não lê o .env (#225)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,avaliacao \
  -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false --avaliacao.repeticoes=3 --avaliacao.hardware='<CPU, RAM, GPU>'"
```

- O banco precisa estar sem o seed `v1` (o comando para e explica como apagar; ver
  [README](README.md#seed-de-avaliação)).
- 81 perguntas × 3 rodadas: em CPU, conte ~2 horas.
- Saídas em `docs/avaliacao/resultados/`: `.md`, `.json`, `-clareza-cego.csv` e
  `-clareza-chave.json`. Versione o `.md` já; o `.json` completo só depois de o orientador
  da #275 devolver as notas do CSV cego (ele liga resposta a pergunta e rodada). A chave fica com a
  equipe, fora do repositório (o `.gitignore` de `resultados/` a ignora).
- Latência no ambiente publicado: [harness de API](harness-api/README.md), também com 3
  repetições.

## Resultado

*Pendente.* Cole aqui, do relatório gerado:

1. **Condições** (modelo, embedding, hardware, gabarito, seed, repetições).
2. **Métricas** — média (faixa) de rota, função, parâmetros, número, citação, recusa, vazamento,
   falha técnica e rota nos casos adversários; latência p50/p95.
3. **Por rota esperada** — rota, número, citação, p50/p95, 1ª parte, palavras por frase, siglas
   sem explicação.
4. **Perguntas instáveis** entre as rodadas.
5. **Harness de API** — p50/p95 por rota no ambiente publicado.
6. **Nota humana de clareza** — fica para a #275 (orientador, sem contador, #387); não bloqueia as etapas 2 e 3.

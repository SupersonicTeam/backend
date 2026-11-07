# S5 — Roteiro da janela de medição na VPS (#270, #265, #153)

Roteiro para quem for à VPS (8 OCPU ARM, 47 GiB, conferida em 2026-11-06) na janela sem merges.
Este arquivo só prepara: nada aqui foi medido. Plano: `plano.md`, seção S5. Medições do script k6
(#153): `docs/performance/README.md` (PR da #153).

## Regras da janela

1. **Nenhum merge na `main` durante a janela.** Todo merge reimplanta o backend na VPS e suja os
   tempos (inclusive o de docs). Anote o SHA do backend em `docker compose images backend` no
   início e confira de novo no fim; mudou, a medição vale só até o ponto da troca.
2. **Uma coisa por vez, 3 repetições** de cada variante. Entre variantes, mude **uma** variável.
3. **Modelo é parâmetro.** A escolha entre 4B e 30B sai da S3 e do Murillo. Defina antes:

   ```bash
   export MODELO='qwen3:4b-instruct'   # ou qwen3:30b-instruct (já baixado na VPS), conforme a S3
   ```

   Não repita o que a S3 já mediu (30B contra 4B); aqui o modelo é fixo e o que varia é a
   quantização, o contexto e os tetos. O `qwen3:8b` entra como uma medição extra do item 4.
4. **Tempo: separe a 1ª execução de cada pergunta.** O harness roda o gabarito inteiro em rodadas
   (`rodada` 1, 2, 3 em cada resultado) e o Ollama guarda um cache de prefixo por vaga
   (`OLLAMA_NUM_PARALLEL=1`). Repetir a *mesma* pergunta em seguida reaproveita o prompt e dá um
   p50 enganoso. Registre sempre **dois** p50/p95 por rota: o da `rodada == 1` (valor de
   referência) e o de todas as rodadas (mostra o efeito do cache). Nunca use só o segundo.
5. **Aquecer antes de medir** (uma pergunta de dados, uma de lei, descartadas): o primeiro uso
   carrega o modelo (`OLLAMA_KEEP_ALIVE=24h`). Trocar `AI_OLLAMA_NUM_CTX` ou o modelo recarrega
   o Ollama: aqueça de novo.
6. **Conta e cota.** Use a conta de demonstração, nunca a de cliente real. O gabarito em 3 rodadas
   gasta cota (#166): confira `GET /api/assistente/uso` antes e entre variantes. Limite de 20
   perguntas/min por usuário (#150) não é problema do harness (ele espera).
7. **Antes de cada variante** registre `docker stats --no-stream` (RAM em repouso) e, durante,
   um segundo (`docker stats --no-stream` no meio da rodada). Meta do plano: VM abaixo de 85% de
   memória em uso normal.

## Preparação (uma vez)

```bash
# na VPS, em /opt/agrocontabil
cd /opt/agrocontabil && docker compose images backend && docker compose ps
export AGRO_API_URL='https://www.agroconia.com.br' AGRO_EMAIL='<conta de demonstracao>'
read -rs AGRO_SENHA && export AGRO_SENHA        # nunca em arquivo nem no histórico
# se o host tiver o Basic Auth do Caddy: AGRO_BASIC_USUARIO / AGRO_BASIC_SENHA
curl -s "$AGRO_API_URL/api/v1/health"; docker compose exec ollama ollama list
```

Cada rodada do harness grava `docs/avaliacao/resultados/<data>-<hora>-harness-api.json`. Renomeie
ou mova para uma pasta da variante (`.../s5/<variante>/`) para não misturar. Essa pasta **não** é
versionada com texto de pergunta do benchmark: o gabarito do harness é o da #158
(`docs/avaliacao/gabarito-ceres.json`), que não é o conjunto cego.

Resumo de uma rodada por `rodada` (p50/p95 por rota, 1ª rodada contra todas) com Python da
biblioteca padrão:

```bash
python3 -I - <<'PY' arquivo.json
import json, sys, statistics as s
d = json.load(open(sys.argv[1], encoding="utf-8"))
def pct(v, p):
    v = sorted(v); return v[min(len(v) - 1, round(p / 100 * (len(v) - 1)))] if v else None
for rotulo, filtro in (("rodada 1", lambda r: r["rodada"] == 1), ("todas", lambda r: True)):
    grupos = {}
    for r in d["resultados"]:
        if filtro(r) and r["status"] == 200:
            grupos.setdefault(r.get("rotaEsperada") or "SEM_ROTA", []).append(r["latenciaMs"])
    for rota, v in sorted(grupos.items()):
        print(f"{rotulo:9} {rota:16} n={len(v):3} p50={pct(v,50)} p95={pct(v,95)} media={round(s.mean(v))}")
PY
```

Acerto de rota, número e citação vem do comando `dev,avaliacao` (gabarito da #158). Na VPS ele
precisa de **banco próprio de avaliação** (`docs/avaliacao/README.md`, "Seed de avaliação"): nunca
o banco da demonstração, porque grava um seed fictício. Sempre com `--avaliacao.repeticoes=3` e
`--avaliacao.hardware='VPS ARM 8 OCPU, 47 GiB, sem GPU'` (a antiga etiqueta "4 nucleos" está
errada). Saída: `docs/avaliacao/resultados/` (`.md` e `.json`).

## Ordem das medições

Ordem proposta: a base primeiro, depois o que muda uma variável por vez. A cada passo, o que
registrar e onde.

### 0. Linha de base com o código final (obrigatória)

Variante: padrões do `deploy/.env.example` (`AI_CHAT_MODEL=$MODELO`, `AI_OLLAMA_NUM_CTX=8192`,
tetos 512/256/1024).

- Harness (3 rodadas) + `dev,avaliacao` (3 repetições).
- Registra: p50/p95 por rota (rodada 1 e todas), acerto de rota/número/citação, RAM (`docker stats`).
- Onde: tabela "antes" em `docs/ceres-assistente.md`, seção da #270; relatório do gabarito em
  `docs/avaliacao/resultados/`.
- Também aqui, para a #265 (ver passo 6): copie as consultas de camada da auditoria.

### 1. Q4_0 contra Q4_K_M (#270, item 1)

Mude só `AI_CHAT_MODEL` no `.env` da VPS e `docker compose up -d backend` (sem recriar o Ollama).
Procedimento de obter o Q4_0 (Hugging Face direto ou Modelfile com o template da tag oficial):
`docs/ceres-assistente.md`, seção "Quantização Q4_0". Antes de medir, confirme que uma pergunta de
dados devolve `tool_calls` (uma pergunta pelo harness com `AGRO_IDS=<um id de dados>`).

- Registra: o mesmo conjunto do passo 0, mais tamanho do arquivo do modelo e RAM.
- Adoção: só sem regressão de acerto em relação ao passo 0. Troca de padrão depende de OK do
  Murillo; ajuste aprovado sai em PR próprio.

### 2. `qwen3:8b` (#270, item 4, medição)

Mesma coisa com `AI_CHAT_MODEL=qwen3:8b` (baixar antes: `docker compose exec ollama ollama pull
qwen3:8b`). Se a S3 já mediu o tamanho/qualidade de outro modelo, só repita o que ela não cobriu.

- Registra: tempo por rota, acerto, RAM (o 8B ocupa o dobro do 4B em contexto e pesos).

### 3. `prompt_eval_count` com prefixo em cache, cota e auditoria (#270)

Objetivo: saber se, com o prefixo no cache, o Ollama conta só a parte nova em `prompt_eval_count`
(isso muda o que a cota #166 e a auditoria registram).

1. Reserve uma janela curta e uma conta só para isso. Anote `SELECT tokens_entrada,
   creditos_consumidos FROM assistente_uso_semanal ...` do titular (antes).
2. Faça **duas perguntas de dados diferentes, em sequência**, e depois a *mesma* pergunta de novo.
   Uma classificação ou uma lei no meio substitui o cache (uma vaga só): não intercale.
3. Leia a auditoria:

   ```sql
   SELECT criado_em, rota, camada_classificacao, tokens_entrada, tokens_saida, latencia_ms
   FROM assistente_interacao WHERE produtor_id = '<id da conta>' ORDER BY criado_em DESC LIMIT 10;
   ```

   Também a cota: `tokens_entrada`, `tokens_saida`, `creditos_consumidos` da semana, antes e depois.
4. Registra: `tokens_entrada` da 1ª pergunta contra a 2ª; se cai (contagem só da parte nova),
   dizer o efeito na cota e na auditoria (mesma pergunta custa menos crédito depois de uma de
   dados). Onde: `docs/ceres-assistente.md`, linha "Tokens com cache".

### 4. `AI_OLLAMA_NUM_CTX=6144` (#270)

Mude só `AI_OLLAMA_NUM_CTX` (o backend deve estar com `AI_TETO_*` padrão). Aqueça o Ollama de novo.

- Antes de baixar, meça o **maior** `prompt_eval_count` por chamada em uma rodada com `8192`
  (passo 0): se passar de ~5.400 com folga curta, não adote 6144.
- Registra: acerto, RAM (cache esperado ~0,9 GB contra ~1,2 GB em 8192, por cálculo), qualquer
  resposta que começa sem as regras (sinal de contexto estourado: o Ollama descarta o início).

### 5. `AI_TETO_LEGISLACAO=512` (#270)

Mude só essa variável (volta `NUM_CTX` para 8192). Rode a rodada e conte os cortes:

```sql
SELECT count(*) FROM assistente_interacao
WHERE desfecho = 'RESPOSTA_CORTADA' OR desfecho_dados = 'RESPOSTA_CORTADA'
   OR desfecho_legislacao = 'RESPOSTA_CORTADA';
```

- Registra: contagem de `RESPOSTA_CORTADA` (limpe/anote o ponto de partida antes), acerto de
  citação e latência da rota de lei. Zero cortes e citação igual ao passo 0 = o teto cabe.

### 6. Classificador (#265)

Na rodada do passo 0 (e só nela), registre em `docs/avaliacao/linha-de-base-ceres-2-0.md`:

- **Acerto de rota** e erros das regras: do relatório do `dev,avaliacao` (tabela por camada).
- **Proporção de desempate** (fatia que chegou ao LLM):

  ```sql
  SELECT camada_classificacao, count(*) AS n,
         round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY latencia_ms) AS p50_ms,
         percentile_cont(0.95) WITHIN GROUP (ORDER BY latencia_ms) AS p95_ms
  FROM assistente_interacao WHERE criado_em >= '<início da rodada>' GROUP BY 1;
  ```

  (a latência é da pergunta inteira: a do classificador isolado só aparece no log do modelo.)
- **Classificador com o Ollama ocioso e ocupado.** Ocioso: pergunta ambígua (que cai no desempate)
  sem mais nada rodando. Ocupado: a mesma pergunta enquanto outra conta de demonstração roda o
  harness de dados em paralelo (`AGRO_IDS=<ids de dados>`, outro terminal, outra conta). Repita 3
  vezes cada, **perguntas diferentes por repetição** (regra 4 da janela). Registre p50/p95 e a
  parcela de falha por tempo esgotado.

### 7. Carga da API sem Ceres (#153)

Só depois da Ceres, porque o k6 não deve competir com o Ollama por CPU. Subir, rodar e derrubar a
stack isolada: `docs/performance/README.md` (limites de memória e CPU em cada passo). Registrar o
p95 por endpoint e abrir issue se algum passar de 2 s (não otimizar na janela).

## O que fica registrado, e onde

| Medição | Onde |
|---|---|
| Tabela quantização × contexto × teto × modelo (p50/p95 por rota, acerto, RAM) | `docs/ceres-assistente.md`, seção da #270 |
| Linha de base da Ceres 2.0 e números do classificador | `docs/avaliacao/linha-de-base-ceres-2-0.md` |
| JSON bruto do harness e relatório do gabarito | `docs/avaliacao/resultados/` (sem texto das inéditas) |
| p95 da API (k6) | comentário na #153 e `docs/performance/README.md` |
| Decisão do 30B-A3B / 8B com memória medida | `docs/ceres-assistente.md` e comentário na #270 |

## Depois da janela

- `AI_*` adotado vai ao `deploy/.env.example` e ao `.env` da VPS **só com OK do Murillo**.
- Volte o `.env` da VPS ao padrão ao fim de cada variante que não for adotada.
- Critério que falhar (meta de tempo da milestone: dados p50 ≤ 10 s até o número, lei p50 ≤ 25 s)
  vira issue própria.

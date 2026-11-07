# S5 — Roteiro da janela de medição na VPS (#270, #265, #153)

Roteiro para quem for à VPS (8 OCPU ARM, 47 GiB, conferida em 2026-11-06) nas janelas sem merges.
Plano: `plano.md`, seção S5. Script k6 (#153): `docs/performance/README.md`. O que foi medido e
quando fica nas tabelas de `docs/ceres-assistente.md` (números oficiais, Fase B) e na seção
"Registro das noites" deste arquivo (números relativos da Fase A).

Revisado em 2026-11-07 contra o código da `main` (`4a0b3a8`): as correções em relação à primeira
versão estão na seção "O que mudou nesta revisão".

## Duas fases

A rota de lei ainda pode mudar até 19/11 (#436, PR #425). Por isso a S5 começa pelo que não
depende dela.

- **Fase A (até a rota de lei fechar):** k6 da #153 (passo 7), `prompt_eval_count` com cache e efeito
  na cota (passo 3), latência do classificador com o Ollama ocioso e ocupado (passo 6, parte
  numérica) e as comparações **relativas** de configuração (passos 1, 2, 4 e 5). Cada variante tem o
  **seu passo 0 na mesma noite e no mesmo SHA**, e o registro diz o SHA. Número absoluto da Fase A
  não vai para a tabela "antes" oficial.
- **Fase B (só com o sinal verde do Murillo, previsto 19/11):** passo 0 com o código final, linha de
  base da Ceres 2.0 (acerto de rota, erros das regras, proporção de desempate) e a tabela
  antes/depois em `docs/ceres-assistente.md`.

## Regras da janela

1. **Nenhum merge na `main` durante a janela.** Todo merge reimplanta o backend na VPS e suja os
   tempos (inclusive o de docs). Antes de cada janela: avisar início e fim previstos e esperar a
   confirmação de que os merges estão segurados; no fim, avisar que acabou. Anote o SHA em
   `docker compose images backend` no início e no fim; mudou, a medição vale só até a troca.
2. **Uma coisa por vez, 3 repetições** de cada variante. Entre variantes, mude **uma** variável.
3. **Modelo:** `MODELO='qwen3:4b-instruct'` (decisão do Murillo de 2026-11-07, mantido até a banca).
   O 30B já foi comparado (#426) e não entra; o `qwen3:8b` é a medição extra do passo 2.
4. **Configuração de produção, sem atalhos.** A medição usa o que o `deploy/compose.yaml` passa ao
   backend. O compose **não repassa** `AI_READ_TIMEOUT` (nem `AI_TIMEOUT_PERGUNTA`): valem os padrões
   do `application.yml` (120 s por chamada ao modelo, 5 min por pergunta). Não os mude na janela.
   Uma chamada que passe de 120 s **não** dá erro: o read-timeout abre o disjuntor e a pergunta sai
   em **HTTP 200 com `modoFallback: true`** (desfecho `MODO_FALLBACK` na auditoria), e as perguntas
   dos **30 s seguintes** (`pausa-apos-falha`) também saem em fallback, rápidas. O resumo abaixo
   trata `modoFallback` como **falha**, fora da latência, e cada variante registra a contagem de
   `MODO_FALLBACK` da auditoria. O 8B com a lei em CPU é o caso mais provável.
5. **Como mudar uma variável sem tocar no `.env`.** O `deploy.sh` regrava `BACKEND_TAG` no `.env`
   a cada deploy; editar o arquivo na janela corre contra ele e arrisca o segredo. A variável de
   ambiente do shell vence a do `.env` na interpolação do compose:

   ```bash
   cd /opt/agrocontabil
   AI_OLLAMA_NUM_CTX=6144 docker compose up -d --no-deps backend    # variante
   docker compose up -d --no-deps backend                           # volta ao padrão do .env
   ```

   Confira o que subiu: `docker compose exec backend printenv AI_OLLAMA_NUM_CTX AI_CHAT_MODEL
   AI_TETO_LEGISLACAO` (só essas, nunca `printenv` inteiro). Use `docker compose up -d --no-deps
   backend`, como o `deploy.sh`. **Nunca `docker compose up -d` sem nome de serviço na janela:** ele
   roda o `ollama-init` com o `AI_CHAT_MODEL` da variante e o pull falha com um Q4_0 criado
   localmente. **Segure o lock do deploy** enquanto mexe no serviço, para uma entrega
   da CI não entrar no meio: `exec 9>/opt/agrocontabil/.deploy.lock && flock 9` na sessão da janela
   (o `deploy.sh` espera até 15 min). Ao fim de cada variante, volte ao padrão.
6. **Aquecer depois de qualquer reinício.** Reiniciar o backend (toda variante) refaz o aquecimento
   da JVM e **recalcula em segundo plano os vetores do conjunto de treino do classificador**
   (`EmbeddingClassificadorIntencao`, log `exemplos do classificador de rota carregados`). Até esse
   log, a camada de embeddings não decide (as regras continuam decidindo) e o restante cai no LLM: medir antes distorce o tempo e
   a proporção de desempate. Espere o log (`docker compose logs --since <hora do reinício> backend |
   grep classificador`), depois faça, descartando: uma pergunta de dados, uma de lei, uma que caia no
   desempate e uma decidida pelas embeddings. Trocar `AI_CHAT_MODEL` ou `AI_OLLAMA_NUM_CTX` carrega
   o modelo de novo no Ollama (primeira pergunta lenta). `OLLAMA_KEEP_ALIVE=24h` mantém **o modelo
   anterior carregado**: ao trocar de modelo, descarregue o antigo (`ollama stop <modelo>`) antes de
   medir a RAM.
7. **Tempo: a rodada 1 é a referência.** O harness percorre o gabarito inteiro em cada rodada
   (`for rodada: for pergunta`), então a mesma pergunta só volta depois de todas as outras e o cache
   de prefixo (uma vaga, `OLLAMA_NUM_PARALLEL=1`) **não** é reaproveitado entre repetições. O cache
   que importa é o das perguntas **seguidas do mesmo tipo** (a ordem do gabarito agrupa por rota só em parte: os blocos T e X alternam), e
   vale igual nas três rodadas. Registre mesmo assim **dois** p50/p95 por rota, o da `rodada == 1`
   e o de todas: a diferença mostra deriva (aquecimento, térmica, outro processo), não cache.
8. **Contas e cota (#166).** Cota de 1.000 créditos por semana e produtor (segunda a domingo,
   Brasília). **Contas da medição: as do `DataLoader`** (`joao.produtor@example.com`, "João da
   Silva Produtor", e `maria.agricultora@example.com`, "Maria Agricultora"), fora da tabela de
   contas e do roteiro sugerido de `docs/demonstracao-banca.md` (o documento as cita só como
   escritório à parte). Nunca o Carlos, o Rogério nem as demais contas do roteiro. Não se cria
   conta nem se muda `AI_COTA_*` (o compose nem o repassa) nem se zera consumo.

   **Orçamento.** "Perto de 900 créditos por rodada de 3 repetições do gabarito inteiro" (87
   perguntas, 261 respostas) é **estimativa sem medição**; se o `prompt_eval_count` contar o
   prefixo, pode passar de 1.000 (passo 3). Por isso:

   - Rodada completa só no passo 0 da **Fase B** (uma conta com a semana inteira de saldo).
   - Fase A: comparações relativas por **subconjuntos fixos de ids** (`AGRO_IDS`), os mesmos no
     passo 0 e na variante da noite: ~30 ids (cerca de 12 de dados, 10 de lei, 4 de MISTO, 4 fora
     de escopo) para os passos 0, 1 e 2; DADOS e LEG para o passo 4; só LEG e MISTO para o passo 5.
     Registre a lista de ids usada. O acerto de rota/número/citação sai do `dev,avaliacao`
     (gabarito inteiro, fora da cota).
   - Meça o gasto real na primeira passada do subconjunto (consulta abaixo) e recalcule antes de
     seguir.
   - Previsão com a estimativa de ~3,4 créditos por resposta (~310 por passada de 30 ids × 3):

     | Semana | Conta | Uso previsto |
     |---|---|---|
     | 09–15/11 | João | N1 (passo 3, ~20) + N2 (passo 0 do subconjunto + dados em paralelo, ~450) |
     | 09–15/11 | Maria | N3 (Q4_0: passo 0 + variante, ~620) |
     | 16–22/11 | João | N4 (8B: ~620) |
     | 16–22/11 | Maria | N5 (`num_ctx` e teto de lei, subconjuntos menores, ~600) |
     | 23–25/11 | João ou Maria | N6 (Fase B, rodada completa, ~900); sobra de um dia |

   - Se a medição real não couber, **decisão pendente do Murillo** (por exemplo, menos variantes
     ou zerar o consumo das contas de medição, uma escrita no banco): não proponho nem faço nada
     disso sem o OK dele.

   Saldo, antes e depois de cada bloco (só leitura, sem a senha da conta):

   ```sql
   SELECT p.nome_completo, u.semana_inicio, u.creditos_consumidos, u.creditos_reservados,
          u.perguntas, u.tokens_entrada, u.tokens_saida
   FROM assistente_uso_semanal u JOIN produtores p ON p.id = u.titular_id
   WHERE u.titular_tipo = 'PRODUTOR'
     AND u.semana_inicio >= date_trunc('week', now() AT TIME ZONE 'America/Sao_Paulo')::date
     AND p.nome_completo IN ('João da Silva Produtor', 'Maria Agricultora');
   ```

   Ressalva a registrar: essas contas têm poucos dados, então as funções de dados devolvem
   resultados menores que os do Carlos. O tempo é dominado pelo modelo (prefill das 13 definições
   de função), mas a latência de dados da S5 vale para conta pequena.
9. **Antes de cada variante** registre `docker stats --no-stream`, `free -m` (memória usada da VM,
   sem cache: o critério é 85%) e `docker compose exec ollama ollama ps` (tamanho do modelo
   carregado e contexto); durante, um segundo `docker stats` no meio da rodada.

10. **Toda escrita na VPS precisa do OK explícito do Murillo, na noite, com a lista exata dos
    comandos.** Conta como escrita: `ollama pull` ou `ollama create` (inclusive o Q4_0 e o
    `qwen3:8b`), `ollama stop`, recriar o backend com variante ou voltar ao padrão, criar ou apagar
    banco, carregar a `legislacao_trecho` no banco de avaliação, subir ou derrubar a stack
    `agro-perf`, `git clone`/checkout na VPS, qualquer SQL que escreva e qualquer arquivo gravado
    na pasta do deploy. Leitura (`ps`, `stats`, `free`, `SELECT`, `logs`) é livre. Nunca
    `docker compose down` da stack principal, nunca reset nem escrita no banco da demonstração,
    nunca mexer na base de lei, nunca ler segredo do `.env` (só variáveis não secretas).

## Preparação (uma vez)

```bash
# na VPS, em /opt/agrocontabil
cd /opt/agrocontabil && docker compose images backend && docker compose ps
docker compose exec ollama ollama list
export AGRO_API_URL='https://www.agroconia.com.br' AGRO_EMAIL='<conta de medição>'
read -rs AGRO_SENHA && export AGRO_SENHA        # nunca em arquivo nem no histórico
curl -s "$AGRO_API_URL/api/v1/health"
```

O Caddy só encaminha `/api/*` ao backend: fora disso, 200 não prova rota do backend (confira por
`docker exec agrocontabil-caddy-1 wget -qO- http://backend:8080/...`). O Basic Auth do Caddy não
existe mais no login (frontend#93): `AGRO_BASIC_*` não são necessárias.

O harness grava `<saída>/<data>-<hora>-harness-api.json`. Use `AGRO_SAIDA` por variante
(`.../s5/<noite>-<variante>/`) para não misturar. O gabarito do harness é o da #158
(`docs/avaliacao/gabarito-ceres.json`), que **não** é o conjunto cego; mesmo assim a pasta de
resultados não é versionada: o JSON do harness grava `resposta` (texto). Vão ao repositório só os
números (tabelas e contagens); ver "O que fica registrado". O `porRota` que o próprio harness
imprime inclui fallbacks e falhas na latência: use o resumo abaixo.

Resumo de uma rodada (p50/p95 por rota, rodada 1 e todas), Python da biblioteca padrão:

```bash
python3 -I - <<'PY' arquivo.json
import json, sys, statistics as s
d = json.load(open(sys.argv[1], encoding="utf-8"))
def pct(v, p):  # nearest-rank, igual ao harness e ao relatório Java
    v = sorted(v); return v[max(1, -(-p * len(v) // 100)) - 1] if v else None
for rotulo, filtro in (("rodada 1", lambda r: r["rodada"] == 1), ("todas", lambda r: True)):
    grupos = {}
    for r in d["resultados"]:
        if filtro(r) and r["status"] == 200 and not r.get("modoFallback"):
            grupos.setdefault(r.get("rotaEsperada") or "SEM_ROTA", []).append(r["latenciaMs"])
    for rota, v in sorted(grupos.items()):
        print(f"{rotulo:9} {rota:16} n={len(v):3} p50={pct(v,50)} p95={pct(v,95)} media={round(s.mean(v))}")
    falhas = [r for r in d["resultados"] if filtro(r) and r["status"] != 200]
    fallback = [r for r in d["resultados"] if filtro(r) and r["status"] == 200 and r.get("modoFallback")]
    print(f"{rotulo:9} falhas={len(falhas)} modoFallback={len(fallback)} (fora da latência)")
PY
```

### Acerto de rota, número e citação: o comando `dev,avaliacao` na VPS

O comando grava um seed fictício e só roda com `dev`: **nunca contra o banco da demonstração.**
Procedimento previsto (cada passo de escrita precisa do OK do Murillo na noite):

1. **(a)** Banco novo `avaliacao` no Postgres que já roda (não toca o `agrocontabil`):
   `docker compose exec postgres sh -c 'createdb -U "$POSTGRES_USER" avaliacao'`.
2. **(b)** Criar o schema: contêiner de uso único da **mesma imagem do backend** (mesmo SHA), na rede
   do compose (`agrocontabil_interna`), subido **só com `dev`** (sem `avaliacao`), apontado para o
   banco `avaliacao`, até o Liquibase terminar; pare-o. O usuário do compose é o dono do Postgres e
   cria `vector`.
3. **(c)** Carregar a base de lei: `pg_dump --data-only -t legislacao_trecho` do banco da
   demonstração (só leitura), aplicado no `avaliacao`. Sem a lei, a rota de lei mede só o modo sem
   trechos. A base da demonstração nunca é alterada.
4. **(d)** Rodar o contêiner com `SPRING_PROFILES_ACTIVE=dev,avaliacao`, **as mesmas variáveis `AI_*`
   do backend da variante** e o gabarito montado de um checkout no mesmo SHA
   (`/app/docs/avaliacao`; o diretório de trabalho da imagem é `/app`), com
   `--avaliacao.repeticoes=3 --avaliacao.hardware='VPS ARM 8 OCPU, 47 GiB, sem GPU'` (a etiqueta
   antiga "4 nucleos" está errada). O runner termina com `System.exit`. Saída em
   `docs/avaliacao/resultados/` do checkout.
5. Apagar o banco `avaliacao` ao fim da S5, com OK.

O comando não passa pelo Caddy nem pela cota: mede o modelo e o orquestrador, e o tempo dele
**não** é o tempo de ponta a ponta da API. As duas medições se complementam e não se misturam.

## Ordem das medições

### 0. Linha de base (obrigatória, uma por variante e noite)

Variante: padrões do `deploy/.env.example` (`AI_CHAT_MODEL=$MODELO`, `AI_OLLAMA_NUM_CTX=8192`,
tetos 512/256/1024).

- Harness (3 rodadas) e `dev,avaliacao` (3 repetições).
- Registra: p50/p95 por rota (rodada 1 e todas), acerto de rota/número/citação, `docker stats`,
  `free -m`, `ollama ps`, falhas, SHA.
- Fase B: tabela "antes" oficial em `docs/ceres-assistente.md`; relatório em
  `docs/avaliacao/resultados/`; para a #265, as consultas do passo 6.

### 1. Q4_0 contra Q4_K_M (#270, item 1)

Variante: só `AI_CHAT_MODEL`. Obter o Q4_0: `docs/ceres-assistente.md`, seção "Quantização Q4_0".
Se a rota de dados não devolver `tool_calls` (uma pergunta pelo harness com `AGRO_IDS=<um id de
dados>`), a variante está reprovada sem medir mais. Registra o conjunto do passo 0 e o tamanho
do arquivo. Adoção só sem regressão de acerto; troca de padrão depende de OK do Murillo e sai em PR
próprio.

### 2. `qwen3:8b` (#270, item 4, medição)

Mesma coisa com `AI_CHAT_MODEL=qwen3:8b` (`ollama pull qwen3:8b` antes). Descarregue o 4B para medir a
RAM do 8B sozinho e meça também os dois juntos (é o que ocorreria sem o `ollama stop`).

### 3. `prompt_eval_count` com prefixo em cache, cota e auditoria (#270)

Objetivo: saber se, com o prefixo no cache, o Ollama conta só a parte nova em `prompt_eval_count`
(muda o que a cota #166 e a auditoria registram).

Com o classificador em camadas, só chamadas **ao modelo de chat** trocam o cache de prefixo
(uma vaga). A classificação por regra não usa modelo nenhum e a por embedding usa o BGE-M3, que é outro
modelo no Ollama: nenhuma das duas o substitui. O desenho vira outro:

1. Conta só para isso, sem mais nada rodando no Ollama. Anote o saldo (consulta do item 8).
2. Escolha **perguntas de dados que as regras ou as embeddings decidam** (confira depois pela
   `camada_classificacao`: se alguma foi `LLM`, essa chamada de classificação trocou o cache e a
   pergunta não vale). Sequência, uma a uma, sem outras chamadas no meio:
   1. uma pergunta de **lei** (troca o cache para outro prefixo: garante o ponto de partida frio);
   2. dados **A** (prefixo de dados frio);
   3. dados **B**, diferente de A (prefixo de dados quente);
   4. dados **A** de novo (quente).
3. Leia a auditoria (mais recente primeiro):

   ```sql
   SELECT criado_em, rota, camada_classificacao, tokens_entrada, tokens_saida, tokens_estimados,
          latencia_ms
   FROM assistente_interacao WHERE produtor_id = '<id da conta>' ORDER BY criado_em DESC LIMIT 10;
   ```

   e o saldo de novo (`tokens_entrada` e `creditos_consumidos` da semana, antes e depois).
4. Registra: `tokens_entrada` de A (frio) contra B e A (quentes). **Descarte a medição se
   `tokens_estimados` for verdadeiro** (o provedor não informou o uso). Se cair, dizer o efeito na
   cota e na auditoria; se não cair, dizer que a contagem inclui o prefixo. Onde:
   `docs/ceres-assistente.md`, linha "Tokens com cache".

### 4. `AI_OLLAMA_NUM_CTX=6144` (#270)

A conta é **prompt + teto de saída da chamada ≤ 6.144, com folga**: lei ≤ ~5.000 (como no
`deploy/.env.example`, o teto de lei é 1.024) e dados ≤ ~5.500 (teto de funções 512). Meça o maior
`prompt_eval_count` por chamada numa rodada com 8192 em **chamadas frias**: perguntas de DADOS ou
LEG decididas por REGRA ou EMBEDDING, fora do MISTO, logo depois de uma pergunta de outro tipo. Se o
passo 3 mostrar que com cache o Ollama conta só a parte nova, o `tokens_entrada` da auditoria deixa
de ser limite superior: use só as chamadas frias. Se passar do limite, não adote 6144. Mude só
`AI_OLLAMA_NUM_CTX`; aqueça de novo. Registra acerto, RAM (cache esperado ~0,9 GB contra ~1,2 GB),
resposta que perde as regras do início (contexto estourado) e **truncamento no log do Ollama**
(`docker compose logs --since <início> ollama | grep -i truncat`).

### 5. `AI_TETO_LEGISLACAO=512` (#270)

Mude só essa variável (`NUM_CTX` volta a 8192). Anote o ponto de partida e conte os cortes da
rodada:

```sql
SELECT count(*) FROM assistente_interacao
WHERE produtor_id = '<id da conta>' AND criado_em >= '<início da rodada>'
  AND (desfecho = 'RESPOSTA_CORTADA' OR desfecho_dados = 'RESPOSTA_CORTADA'
       OR desfecho_legislacao = 'RESPOSTA_CORTADA');
```

Registra a contagem, o acerto de citação e a latência da rota de lei. Zero cortes e citação igual
ao passo 0 = o teto cabe. Este passo depende da rota de lei: a conclusão da Fase A vale só se a
rota não mudar; refaça na Fase B se mudar.

### 6. Classificador (#265)

**Acerto e erros das regras (Fase B, na rodada do passo 0):** relatório do `dev,avaliacao`
(tabela por camada), em `docs/avaliacao/linha-de-base-ceres-2-0.md`.

**Proporção de desempate (Fase B):**

```sql
SELECT camada_classificacao, count(*) AS n,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY latencia_ms) AS p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latencia_ms) AS p95_ms
FROM assistente_interacao
WHERE produtor_id = '<id da conta>' AND criado_em >= '<início da rodada>'
  AND camada_classificacao IN ('REGRA','EMBEDDING','LLM','PALAVRAS_CHAVE')
GROUP BY 1;
```

As camadas `ATALHO_FIXO` e `FILTRO_INJECAO` ficam fora (não classificam). A latência é da pergunta
inteira. Desempate = linhas `LLM`; **a pergunta MISTO decidida por embeddings também chama o modelo**
(para separar a `perguntaLei`) e aparece como `EMBEDDING`: conte-a à parte
(`rota = 'MISTO' AND camada_classificacao = 'EMBEDDING'`).

**Latência da classificação, com o Ollama ocioso e ocupado (Fase A):** o critério de 300 ms (p95)
vale para regras e embeddings, **não** para o desempate pelo LLM. O código não registra o tempo da
classificação sozinha; use como proxy as perguntas **fora de escopo decididas por embedding**
(camada `EMBEDDING`, resposta fixa, sem chamada ao modelo de chat): a `latencia_ms` da auditoria é
a classificação mais o resto de uma pergunta curta. Confirme que são fixas pela `tokens_entrada`
nula.

- **Fonte das perguntas:** pelo menos 15 perguntas fora de escopo **diferentes entre si**, num
  arquivo local fora do repositório (formato do gabarito, passado por `AGRO_GABARITO`), escritas
  pela sessão da S5; nunca tiradas do benchmark nem de arquivo cego. O gabarito da #158 tem só 11
  fora de escopo, parte decidida pelo `FILTRO_INJECAO`, e não basta. As mesmas listas nas duas
  condições.
- **Ocioso:** nada mais no Ollama. **Ocupado:** a mesma bateria enquanto a outra conta roda o
  harness de dados (`AGRO_IDS=<ids de dados>`, outro terminal).
- **Prazo de 500 ms** (`app.assistente.classificacao.timeout-embedding`): conte os vencidos **pelo
  log** (`docker compose logs --since <início> backend | grep -c 'prazo de 500 ms vencido'`). Vencido o prazo, a camada de embedding **pausa
  30 s** (`pausa-apos-falha`) e as perguntas desse intervalo vão ao LLM sem chamar o embedding:
  atribua ao prazo só a pergunta que foi `EMBEDDING` com o Ollama ocioso e `LLM` ou
  `PALAVRAS_CHAVE` com ele ocupado (o desempate ocupado pode passar de 120 s e virar
  `PALAVRAS_CHAVE`, que também se conta). Espaçar as perguntas em mais de 30 s evita a
  contaminação, ao custo de tempo.
- Registre p50/p95 e a contagem de prazos vencidos nas duas condições. O desempate do LLM sozinho
  (pergunta ambígua) é uma segunda medição, com a latência total da pergunta.

### 7. Carga da API sem Ceres (#153)

`docs/performance/README.md`. O k6 não pode competir com o Ollama: rode com o Ollama **ocioso**
(nenhuma pergunta da Ceres em andamento; o modelo carregado parado não gasta CPU). Imagem
`BACKEND_IMAGE` = a que roda na VPS, anotando o SHA. Subir, medir 3 vezes e derrubar a stack
`agro-perf` (só o volume `agro-perf_pgdata_perf`). p95 por endpoint no comentário da #153; algum
acima de 2 s vira issue própria.

## O que fica registrado, e onde

| Medição | Onde |
|---|---|
| Tabela quantização × contexto × teto × modelo (p50/p95 por rota, acerto, RAM) | `docs/ceres-assistente.md`, seção da #270 |
| Linha de base da Ceres 2.0 e números do classificador | `docs/avaliacao/linha-de-base-ceres-2-0.md` |
| Resumos do harness e do `dev,avaliacao` (p50/p95, contagens, acerto) | em tabelas nos documentos acima; o JSON bruto, o CSV cego e a chave **não** vão ao repositório (têm texto das respostas) e ficam na VPS ou fora dele |
| p95 da API (k6) | comentário na #153 e `docs/performance/README.md` |
| Decisão do 8B com memória medida | `docs/ceres-assistente.md` e comentário na #270 |

Números também em comentário nas issues (#270, #265, #153).

## Depois da janela

- `AI_*` adotado vai ao `deploy/.env.example` e ao `.env` da VPS **só com OK do Murillo**, em PR
  próprio.
- Volte o backend ao padrão (`docker compose up -d --no-deps backend` sem variáveis de ambiente) ao
  fim de cada variante. Solte o lock do deploy com `exec 9>&-` (como em
  `docs/demonstracao-banca.md`); um deploy da CI barrado pelo lock por mais de 15 min sai com código
  75 e precisa ser refeito.
- Critério que falhar (dados p50 ≤ 10 s até o número, lei p50 ≤ 25 s, p95 da API < 2 s, VM abaixo
  de 85% de memória usada em uso normal) vira issue própria, sem otimizar na janela. Achado de
  segurança vira issue separada.

## Registro das noites

Números relativos da Fase A ficam aqui (nunca na tabela oficial). Um bloco por noite:

```text
Data / noite:            SHA no início:        SHA no fim:
Variante / passos:       Conta:                Ids (AGRO_IDS):
Créditos antes -> depois: ollama ps (modelo, tamanho, contexto):
free -m (usado/total):   docker stats no meio:
p50/p95 por rota (rodada 1 | todas):
Falhas: n   modoFallback: n   MODO_FALLBACK na auditoria: n   RESPOSTA_CORTADA: n
Acerto (dev,avaliacao): rota / número / citação
Observações:
```

(sem registros ainda)

## O que mudou nesta revisão (2026-11-07)

- Duas fases (A e B) e modelo fixo em `qwen3:4b-instruct`; o 30B saiu (já comparado na #426).
- Configuração de produção: o compose não repassa `AI_READ_TIMEOUT` (valem 120 s e 5 min).
- Variável por ambiente do shell em vez de editar o `.env` (o `deploy.sh` regrava `BACKEND_TAG`
  nele); lock do deploy enquanto se mexe no serviço.
- Aquecimento depois de reiniciar o backend inclui esperar os vetores do classificador.
- A rodada não reaproveita cache entre repetições (o harness percorre o gabarito inteiro por
  rodada); a razão dos dois p50 foi corrigida.
- Passo 3 refeito para o classificador em camadas (a classificação por regra ou embedding não troca
  o cache do modelo de chat) e com `tokens_estimados`.
- Passo 6: o critério de 300 ms é de regras e embeddings, não do desempate; proxy por pergunta fora
  de escopo, condição ocupado e tempo limite de 500 ms dos embeddings; MISTO por embeddings também
  chama o modelo.
- SQL de cota e auditoria com as colunas reais (`assistente_uso_semanal` por `titular_id` e
  `semana_inicio`; `produtor_id` e `tokens_estimados` na auditoria).
- Procedimento do `dev,avaliacao` na VPS (banco `avaliacao`, contêiner da imagem, cópia da base de
  lei) e contas de medição fora do roteiro da banca.
- RAM: `ollama ps` e `free -m`; o `ollama stop` do modelo anterior antes de medir.
- Revisão independente (9 correções): read-timeout é 200 com `modoFallback` e pausa de 30 s, não
  erro; orçamento de créditos e subconjuntos por `AGRO_IDS`; regra geral de OK para escrita;
  `dev,avaliacao` em quatro passos (banco, schema, lei, avaliação); conta do passo 4 com teto de
  saída e truncamento; passo 6 com prazos vencidos pelo log, pausa de 30 s e fonte das perguntas;
  seção "Registro das noites"; versionamento só de números; `--no-deps` e `ollama-init`.

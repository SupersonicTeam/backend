# Benchmark de p95 da API (issue #153)

Mede, com k6, o p95 de quatro endpoints do produtor — **dashboard**, **listagem de notas**,
**apuração** (últimos 12 meses) e **calendário fiscal** — contra uma **stack isolada**, com volume
sintético. Limite do plano (RNF001): **p95 < 2 s** em cada endpoint.

Este diretório só prepara a medição. **Nenhum número foi medido**; a medição é da janela da S5, na
VPS (`.specs/features/backlog-pre-banca/s5-roteiro-janela.md`, passo 7). Não rode isto no banco
nem na stack da demonstração.

## O que tem aqui

| Arquivo | Para quê |
|---|---|
| `compose.yaml` | Stack isolada: projeto `agro-perf`, Postgres e backend próprios, nenhuma porta publicada, limites de CPU/memória em todos os serviços |
| `.env.example` | Segredos da stack (gerados na hora, descartáveis) |
| `seed-volume.sql` | Gera o volume sintético (notas, itens e guias) no banco da stack |
| `k6/carga.js` | O cenário k6, com `p(95)<2000` por endpoint como limite |
| `resultados/` | Saída do k6 (`resumo.json`); ignorada pelo git |

O teste de N+1 (contagem de statements) roda na CI, não aqui:
`ContagemStatementsPostgresIntegrationTest`.

## Limites de recurso

| Serviço | Memória | CPU | Observação |
|---|---|---|---|
| postgres | 2 GiB | 2 | configuração padrão do Postgres, como no deploy |
| backend | 2 GiB | 2 | heap `-Xmx1g`; no deploy o contêiner não tem teto |
| k6 | 1 GiB | 1 | gerador de carga fora do backend |
| **soma** | **5 GiB** | **5** | a VPS tem 8 OCPU e 47 GiB, mas o Ollama e a demonstração dividem a máquina |

O backend com menos CPU e memória que o de produção torna a medição **conservadora**: p95 abaixo
de 2 s aqui vale para o deploy. Mesmo assim, rode **depois** das medições da Ceres (o k6 não deve
competir com o Ollama por CPU) e sem merges durante a janela.

## Limites de taxa (por que o k6 não leva 429)

A API limita login a 10/min por IP (#208) e `/api/**` a 100/min por identidade (#150,
`LimiteTaxaGeralFilter`). O k6 usa **uma** identidade e um IP: a 100 requisições por minuto o teste
receberia 429 em segundos e o p95 mediria o limitador.

Resolução, **sem mexer em código nem desligar segurança em produção**: os limites já são
propriedades (`app.seguranca.limite.*`, `app.seguranca.forca-bruta.*`) lidas de variáveis de
ambiente, e **só o `compose.yaml` desta stack efêmera** as sobe (`LIMITE_GERAL_POR_MINUTO`,
`LIMITE_LOGIN_POR_MINUTO`, `LIMITE_REFRESH_POR_MINUTO` = 1.000.000). Os padrões do `application.yml`
e o `deploy/compose.yaml` não mudam. Além disso, o script **reprova** a execução se vir qualquer
429 (`limite_429` com `count==0`) e renova o access token a cada 10 min (vale 15), então o cenário
de produção do produtor (a mesma conta com poucas requisições por minuto) continua coberto pelo que
o limitador faz de verdade: os testes de `LimiteTaxaGeralFilter` na CI.

## Passo a passo

Tudo a partir de `docs/performance/` (na VPS, uma cópia do repositório ou só esta pasta).

### 1. Preparar o `.env` e a imagem

```bash
{
  echo "PERF_DB_PASSWORD=$(openssl rand -hex 16)"
  echo "PERF_JWT_SECRET=$(openssl rand -base64 48 | tr -d '=\n')"
  echo "PERF_CRIPTO_CHAVE=$(openssl rand -base64 32)"
  echo "PERF_SEED_SENHA=Perf-$(openssl rand -hex 8)-k6"
  echo "BACKEND_IMAGE=ghcr.io/supersonicteam/backend:latest"
  echo "K6_VUS=10"
  echo "K6_DURACAO=3m"
} > .env
```

Imagem do backend: o padrão é a do deploy (`ghcr.io/supersonicteam/backend:latest`; na VPS use o
`BACKEND_TAG` em produção, `sha-xxxxxxx`, para medir o mesmo artefato). Para medir o código de um
checkout: `docker build -t agro-perf-backend:local ../..` e `BACKEND_IMAGE=agro-perf-backend:local`.

### 2. Subir (postgres + backend)

```bash
docker compose up -d postgres backend
docker compose ps        # espere o backend ficar "healthy" (até ~1,5 min; roda o Liquibase)
```

Limites: postgres 2 GiB/2 CPUs, backend 2 GiB/2 CPUs.

### 3. Gerar o volume sintético

```bash
docker compose exec -T postgres psql -U agro_perf -d agro_perf -v notas=20000 < seed-volume.sql
```

20.000 notas (40.000 itens, 2.000 guias) distribuídas em 24 meses para o produtor semeado do
`DataLoader`. Ajuste `-v notas=` para o volume que a banca deve ver; o script é idempotente (apaga
a carga anterior antes). Limites: usa o próprio contêiner do postgres (2 GiB/2 CPUs).

### 4. Rodar o k6

```bash
mkdir -p resultados
K6_VUS=10 K6_DURACAO=3m docker compose --profile k6 run --rm k6
```

Limites: k6 com 1 GiB/1 CPU. O script faz 30 s de subida, `K6_DURACAO` no patamar e 15 s de
descida. `K6_VUS=10` e `3m` são o ponto de partida; para 3 repetições, rode o comando 3 vezes e
guarde cada `resultados/resumo.json` renomeado.

O comando termina com código diferente de zero se qualquer limite falhar (p95 ≥ 2 s por endpoint,
erro ≥ 1%, qualquer 429). O resumo imprime p50 (`med`), p90 e p95 por endpoint
(`http_req_duration{endpoint:...}`).

### 5. Durante a medição

```bash
docker stats --no-stream      # CPU e memória dos três contêineres (e da VM)
```

### 6. Derrubar

```bash
docker compose --profile k6 down -v   # remove contêineres, rede e o volume do banco (agro_perf)
```

`-v` apaga **só** o volume `agro-perf_pgdata_perf`; os volumes da demonstração têm outro prefixo
de projeto e não são tocados.

## O que registrar

p95 (e p50) por endpoint, o volume (`notas`), VUs, duração, SHA do backend e `docker stats`.
Comentar na #153. **Se algum p95 passar de 2 s, abrir issue — não otimizar na janela.** Cache da
apuração e a exposição de `/actuator/metrics` são issues à parte (ver o relatório da S5).

## Limites do que isto mede

- Uma conta só (um produtor com todo o volume): é o pior caso por usuário, não a mistura de uma
  carteira de contador.
- Nenhum mês fechado: a apuração calcula todos os meses ao vivo (pior caso).
- Sem Ceres (`AI_CHAT_PROVIDER=none`) e sem ClamAV: o benchmark é da API fiscal.
- O tempo medido inclui a rede interna do compose, não o Caddy nem a internet.

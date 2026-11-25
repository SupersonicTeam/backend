# Benchmark de p95 da API (issue #153)

Mede, com k6, o p95 de quatro endpoints do produtor — **dashboard**, **listagem de notas**,
**apuração** (últimos 12 meses) e **calendário fiscal** — contra uma **stack isolada**, com volume
sintético. Limite do plano (RNF001): **p95 < 2 s** em cada endpoint.

Este diretório prepara e registra a medição. **Medido em 25/11/2026, na VPS (#585): RNF001 atendido,
o maior p95 foi de 47 ms contra o limite de 2.000 ms** (seção "Medição de 25/11/2026", no fim). Não
rode isto no banco nem na stack da demonstração.

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
429 (`limite_429` com `count==0`) e renova o access token a cada 10 min (vale 15). O benchmark
mede a API **sem** o limitador; o comportamento do limitador em si tem testes próprios na CI.

## Passo a passo

Tudo a partir de `docs/performance/` (na VPS, uma cópia do repositório ou só esta pasta).

### 1. Preparar o `.env` e a imagem

```bash
{
  echo "PERF_DB_PASSWORD=$(openssl rand -hex 16)"
  echo "PERF_JWT_SECRET=$(openssl rand -base64 48 | tr -d '=\n')"
  echo "PERF_CRIPTO_CHAVE=$(openssl rand -base64 32)"
  echo "PERF_SEED_SENHA=Perf-$(openssl rand -hex 8)-k6"
  echo "BACKEND_IMAGE=ghcr.io/supersonicteam/backend:sha-xxxxxxx"   # o SHA que roda na VPS
  echo "K6_VUS=10"
  echo "K6_DURACAO=3m"
} > .env
```

Imagem do backend (`BACKEND_IMAGE`, obrigatória): a do deploy (na VPS use o
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

## Medição de 25/11/2026 (VPS, issue #585)

**RNF001 atendido com folga: o maior p95 foi de 47 ms, contra o limite de 2.000 ms.** As três execuções
terminaram com código 0 (nenhum limite do `carga.js` violado). Feita das 08h25 às 08h37, pelo passo a
passo acima, sem desvio.

**Condições**

- Stack isolada `agro-perf` na VPS (8 CPUs, 47 GiB), pelos limites acima: Postgres 2 GiB/2 CPUs, backend
  2 GiB/2 CPUs (`-Xmx1g`), k6 1 GiB/1 CPU; nenhuma porta publicada.
- Imagem do backend: `ghcr.io/supersonicteam/backend:sha-596bc94`, a mesma em produção (`main`
  `596bc941`).
- Volume: 20.000 notas, 40.000 itens e 2.000 guias em 24 meses, todas do produtor que o k6 usa
  (conferido no banco).
- k6 1.0.0, 10 usuários virtuais, 30 s de subida, 3 min de patamar e 15 s de descida, três execuções
  seguidas. Sem Ceres e sem ClamAV, como o guia prevê.
- Nenhum merge na `main` durante a janela; nada foi otimizado nela.

**Resultado por endpoint (ms; p50 / p95 / máximo)**

| Endpoint | Execução 1 | Execução 2 | Execução 3 |
|---|---|---|---|
| Dashboard | 35 / 44 / 171 | 34 / 47 / 81 | 33 / 34 / 39 |
| Listagem de notas | 11 / 18 / 71 | 10 / 11 / 34 | 10 / 11 / 15 |
| Apuração (12 meses) | 23 / 27 / 100 | 23 / 36 / 70 | 21 / 22 / 27 |
| Calendário fiscal | 20 / 29 / 234 | 19 / 22 / 67 | 19 / 20 / 25 |

| | Execução 1 | Execução 2 | Execução 3 |
|---|---|---|---|
| Requisições | 13.681 (60,6/s) | 13.765 (60,9/s) | 13.913 (61,6/s) |
| Falhas HTTP | 0 | 0 | 0 |
| Respostas 429 | 0 | 0 | 0 |
| Checks | 100% | 100% | 100% |
| p95 geral / máximo geral | 38 / 742 | 37 / 405 | 34 / 349 |

**Uso de recursos aos 2 min de cada execução (`docker stats`):** Postgres da medição 91% a 100% de uma CPU
(teto de 2) e cerca de 100 MiB; backend da medição cerca de 50% de uma CPU e 680 MiB; k6 7% a 9% e 30 MiB.
Carga média da VM entre 1,5 e 1,9 (a VM também hospeda o site da GWNext). A stack da demonstração ficou
ociosa (backend a 0,1% de CPU) e respondendo. Erros no log do backend da medição: 0.

**Encerramento:** `docker compose --profile k6 down -v` e pasta apagada; nenhum contêiner, volume ou rede
`agro-perf` restou. Os `resumo.json` não são versionados (o guia manda ignorá-los); os números estão nas
tabelas acima.

**O que esta medição cobre, e o que não cobre:** uma conta só com todo o volume; nenhum mês fechado (a
apuração calcula os 12 meses ao vivo); sem Ceres; o tempo inclui a rede interna do compose, não o Caddy
nem a internet; backend com menos CPU e memória que o de produção (medição conservadora). A carga é de 10
usuários simultâneos, cerca de 60 requisições por segundo: **não é teste de pico nem de estresse**, e o
comportamento sob mais usuários, com a Ceres respondendo ou com contas diferentes ao mesmo tempo, não foi
medido.

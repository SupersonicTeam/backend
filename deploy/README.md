# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (Ceres),
ClamAV e Caddy (HTTPS automático) numa VM só.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 4 OCPU / 24 GB, disco 100 GB, Ubuntu 24.04 |
| Endereço | `https://agrocontabil.<ip-com-hífens>.sslip.io` (sem domínio próprio: o sslip.io resolve o IP no nome) |
| Acesso SSH | `ssh -i ~/.ssh/agrocontabil_lightsail ubuntu@<ip>` (a chave privada fica só no PC de quem fez o deploy) |
| Pasta na VM | `/opt/agrocontabil` — `compose.yaml`, `Caddyfile`, `.env` (600), `certs/nfe-teste.p12` |
| Imagens | `ghcr.io/supersonicteam/backend` e `ghcr.io/supersonicteam/frontend` (workflow `docker-publish.yml` de cada repo, amd64 + arm64) |

## Por que `dev` atrás de senha

O frontend ainda se identifica pelo header `X-Usuario-Id`, e desde a #221 esse mock só existe no
profile `dev`. Em `prod` o Angular tomaria 401 em tudo até ganhar a tela de login. Então o backend
sobe em `dev` e o Caddy exige **basic auth no site inteiro** — em dev qualquer requisição declara
qualquer identidade e a chave de cifra é a pública de desenvolvimento. **Só dados fictícios.**

O Caddy tira o `Authorization: Basic` antes de repassar (o navegador o reenvia em toda chamada da
SPA e o backend o leria como credencial); um `Bearer` do login real passa intacto. Quando o
frontend tiver login, troque para `SPRING_PROFILES_ACTIVE: prod` com os segredos do
`.env.example` da raiz (`JWT_SECRET`, `CRIPTO_CHAVE`, SMTP) e tire o `basic_auth`.

## Rede (já configurado)

- Security list da VCN `agrocontabil-vcn`: entrada TCP 22, 80 e 443.
- `iptables` da imagem Ubuntu da Oracle (REJECT no fim do INPUT): 80/443 TCP e 443 UDP liberados e
  salvos com `netfilter-persistent` (cloud-init da VM).
- Só o Caddy publica porta. Backend, Postgres, Ollama e ClamAV ficam na rede interna do compose; o
  Actuator não é exposto (o Caddy só repassa `/api/*`).

## Primeira subida

```bash
cd /opt/agrocontabil
cp .env.example .env && chmod 600 .env   # preencher; hash da senha do site:
docker run --rm caddy:2.10-alpine caddy hash-password --plaintext '<senha>'
docker login ghcr.io -u <usuario-github>   # token clássico só com read:packages
docker compose up -d
```

A primeira subida baixa ~4 GB de modelos (`ollama-init`) e o ClamAV leva ~2 min carregando as
assinaturas; até lá a Ceres responde em modo fallback e os anexos ficam `EM_VERIFICACAO`.

### Base de legislação da Ceres

A ingestão (profile `ingestao`) é lenta em CPU; copie a base já ingerida de outro banco depois que o
backend aplicou o Liquibase (só as colunas reais — as outras são geradas):

```bash
# na máquina de origem
psql ... -c "\copy (select id, content, metadata, embedding from legislacao_trecho) to stdout" | gzip > legislacao.tsv.gz
# na VM
gunzip -c legislacao.tsv.gz | docker compose exec -T postgres \
  psql -U agrocontabil -d agrocontabil -c "\copy legislacao_trecho(id, content, metadata, embedding) from stdin"
```

## Atualizar

```bash
cd /opt/agrocontabil && docker compose pull backend frontend && docker compose up -d
```

`BACKEND_TAG`/`FRONTEND_TAG` no `.env` fixam uma versão (`sha-xxxxxxx`) em vez de `latest`.

## Custos

Tudo dentro do Always Free (A1 até 4 OCPU/24 GB, até 200 GB de disco, IP público efêmero). A conta
é Pay As You Go: aumentar OCPU/RAM além disso ou criar outra VM A1 é cobrado.

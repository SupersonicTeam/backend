# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (Ceres),
ClamAV e Caddy (HTTPS automático) numa VM só.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 4 OCPU / 24 GB, disco 100 GB, Ubuntu 24.04 |
| Endereço | `https://www.agroconia.com.br` (`SITE_HOST`). `agroconia.com.br` e o antigo `agrocontabil.<ip-com-hífens>.sslip.io` redirecionam para ele (`SITE_REDIRECIONA`) |
| DNS | Hostinger (`dns-parking.com`): registro `A` de `@` e de `www` para o IP da VPS, sem `AAAA` (a VM não tem IPv6) |
| Acesso SSH | `ssh -i ~/.ssh/agrocontabil_lightsail ubuntu@<ip>` (a chave privada fica só no PC de quem fez o deploy) |
| Pasta na VM | `/opt/agrocontabil` — `compose.yaml`, `Caddyfile`, `.env` (600), `certs/nfe-teste.p12` |
| Imagens | `ghcr.io/supersonicteam/backend` e `ghcr.io/supersonicteam/frontend` (workflow `docker-publish.yml` de cada repo, amd64 + arm64) |

## Profile `homolog`, sem senha no Caddy

Desde o login do frontend (frontend#93, 2026-10-30) o backend sobe em **`homolog`**: só o JWT do
login autentica (o mock do `X-Usuario-Id` é só do `dev`, #221), com `JWT_SECRET` e `CRIPTO_CHAVE`
próprios do `.env`. Por isso o Caddy não pede mais senha (o basic auth saiu em 2026-11-01). Até
então o backend rodava em `dev`, onde qualquer requisição declarava qualquer identidade, e o basic
auth era a única barreira.

Continua sendo **demonstração com dados fictícios**: o certificado da NF-e é o de teste (público),
a SEFAZ é o mock (com `autorizar-terceiros` ligado, para a importação de NF-e de terceiro) e não há
SMTP (`EMAIL_MODO` = `DESLIGADO`: "esqueci minha senha" responde 202 e nada é enviado). As contas
semeadas entram pelo login com a `SEED_SENHA`, inclusive as da **demonstração da banca**
(`DEMONSTRACAO_HABILITADA`, #315): escritório com equipe, carteira e meses de notas. Contas e
roteiro em [`docs/demonstracao-banca.md`](../docs/demonstracao-banca.md).

**A `CRIPTO_CHAVE` não pode mudar com dados no banco**: CPF/CNPJ, e-mail e telefone estão cifrados
com ela, e com outra chave ficam ilegíveis. Trocar = zerar o banco (preservando a base da Ceres,
abaixo) e deixar o seed recriar as contas.

## Rede (já configurado)

- Security list da VCN `agrocontabil-vcn`: entrada TCP 22, 80 e 443.
- `iptables` da imagem Ubuntu da Oracle (REJECT no fim do INPUT): 80/443 TCP e 443 UDP liberados e
  salvos com `netfilter-persistent` (cloud-init da VM).
- Só o Caddy publica porta. Backend, Postgres, Ollama e ClamAV ficam na rede interna do compose; o
  Actuator não é exposto (o Caddy só repassa `/api/*`).

## Operação

- **Backup do banco:** `backup.sh` roda no cron do `ubuntu` às 03:30 (VM em `America/Sao_Paulo`):
  `pg_dump` custom em `backups/agrocontabil-AAAA-MM-DD.dump`, 7 dias de retenção, log em
  `backups/backup.log`. Restauração no cabeçalho do script. Fica no mesmo disco; contra perder a VM,
  vale a **política de backup do volume de boot** na Oracle (Always Free: até 5 backups).
- **Logs:** todo serviço do compose usa `json-file` com 20 MB × 5 arquivos (âncora `x-logging`), e o
  `/etc/docker/daemon.json` repete o limite para qualquer container fora do compose.
- **Monitor:** `GET /healthz` responde só `UP` (200) ou `DOWN` (503) pelo health do backend, sem
  repassar o corpo do Actuator. É o alvo do UptimeRobot.
- **SSH:** só chave (sem senha, root sem senha) e `fail2ban` no jail `sshd` (5 falhas em 10 min →
  1 h de bloqueio). No Ubuntu 24.04 a unidade é `ssh.service`, então o
  `/etc/fail2ban/jail.d/sshd.local` fixa o `journalmatch` — o padrão do fail2ban nunca casaria.
- **Arquivos de configuração** (`compose.yaml`, `Caddyfile`, scripts) não vão pelo CD, que só troca
  imagem: ao mudar algum, copie para `/opt/agrocontabil` e rode `docker compose up -d`.

## Primeira subida

```bash
cd /opt/agrocontabil
cp .env.example .env && chmod 600 .env   # preencher; segredos:
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # CRIPTO_CHAVE
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

## CI/CD

```
PR ──► CI (testes) ──► merge na main ──► CI verde ──► docker-publish.yml ──► GHCR ──► deploy na VPS
```

- O `docker-publish.yml` de cada repo dispara por `workflow_run` **só quando a CI do push na `main`
  passa** (backend: `CI - Backend`; frontend: `CI`) e publica `sha-xxxxxxx` + `latest`.
- O job **Deploy na VPS** entra por SSH com uma chave só da CI. Na VPS ela tem
  `command="/opt/agrocontabil/ssh-forcado.sh"` no `authorized_keys`: não abre shell e só aceita
  `deploy <backend|frontend> <sha-xxxxxxx|latest>`.
- `deploy.sh` (nesta pasta, copiado para `/opt/agrocontabil`):
  - **lock único da VPS** (`flock` em `.deploy.lock`): backend e frontend implantam em série — o
    segundo espera o primeiro terminar (até 15 min);
  - recria **só o próprio serviço** (`up -d --no-deps`): o deploy do frontend não reinicia o backend;
  - grava a tag no `.env` (`BACKEND_TAG`/`FRONTEND_TAG`), então um `up -d` manual depois mantém a versão;
  - espera `healthy` (5 min); se não ficar, **rollback** para a tag anterior e o job falha.
- Configuração no GitHub (cada repo): secret `VPS_SSH_KEY` (chave privada da CI) e variáveis
  `VPS_HOST`, `VPS_SITE_HOST` e `VPS_KNOWN_HOSTS` (saída de `ssh-keyscan -t ed25519 <ip>`).
- Reimplantar sem novo commit: *Actions → Docker - … (GHCR + deploy) → Run workflow*.

Deploy manual de uma versão (na VM): `./deploy.sh backend sha-xxxxxxx`.

## Custos

Tudo dentro do Always Free (A1 até 4 OCPU/24 GB, até 200 GB de disco, IP público efêmero). A conta
é Pay As You Go: aumentar OCPU/RAM além disso ou criar outra VM A1 é cobrado.

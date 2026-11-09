# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (Ceres),
ClamAV e Caddy (HTTPS automático) numa VM só.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 8 OCPU / 47 GiB de RAM, disco de 96 GB (conferido em 2026-11-06), Ubuntu 24.04 |
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
a SEFAZ é o mock (com `autorizar-terceiros` ligado, para a importação de NF-e de terceiro) e o envio
de e-mail está desligado (`EMAIL_MODO` = `DESLIGADO`: "esqueci minha senha" e os convites respondem
normalmente e nada é enviado) até a seção [E-mail](#e-mail-460) ser cumprida. As contas
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

## E-mail (#460)

Três fluxos mandam e-mail: o link de "esqueci minha senha", o convite do escritório ao produtor e o
convite de equipe. O envio é por SMTP (`spring.mail`), então trocar de provedor é só trocar
variáveis e DNS. O `compose.yaml` passa ao backend estas variáveis, todas com padrão seguro:

| Variável | Padrão no compose | Observação |
|----------|-------------------|------------|
| `EMAIL_MODO` | `DESLIGADO` | `SMTP` liga o envio. `LOG` só sobe em dev |
| `EMAIL_REMETENTE` | `AgroConIA <nao-responda@agroconia.com.br>` | Com `SMTP`, o domínio do endereço tem de ser o verificado no provedor (`EMAIL_DOMINIO_VERIFICADO`, padrão `agroconia.com.br`) |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | vazio, `587` | Do provedor |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | vazios | **Só no `.env` (600)**, nunca em issue, chat ou repositório |
| `EMAIL_SMTP_STARTTLS`, `EMAIL_SMTP_SSL` | `true`, `false` | Porta 587 com STARTTLS obrigatório. Na 465: `EMAIL_SMTP_SSL=true` e `EMAIL_SMTP_STARTTLS=false` |

Os links dos e-mails apontam para `https://${SITE_HOST}`: `/redefinir-senha`, `/escritorio` (onde o
produtor vê os convites) e `/equipe/convite`.

**O que impede a subida com `EMAIL_MODO=SMTP`:** host, usuário ou senha vazios, remetente fora do
domínio verificado e link que não seja `https` (fora de dev/test). Com o envio desligado nada disso
é conferido. Quem sobe `homolog` na própria máquina com `SMTP` precisa passar as três URLs em
`https`.

**Health:** o health do SMTP fica desligado (`management.health.mail.enabled: false` no
`application.yml` e `MANAGEMENT_HEALTH_MAIL_ENABLED` no compose). Com ele ligado, o provedor fora do
ar ou o `SPRING_MAIL_HOST` vazio poriam o `/actuator/health` em `DOWN`, e o `deploy.sh` reverteria o
deploy. A falha de envio aparece só no log do backend.

**Ordem para ligar** (o compose novo e a imagem nova podem chegar em qualquer ordem):

1. Provedor, domínio e DNS (SPF, DKIM e DMARC `p=none`), com o rastreamento de clique e de abertura
   desligado: o link leva o token no fragmento (`#token=`), e a reescrita do link o quebraria. A
   recomendação, a comparação dos provedores e os registros DNS estão na #460 (Resend em
   `sa-east-1`: `smtp.resend.com`, porta 587, usuário `resend`, senha = API key só de envio).
2. Credenciais no `.env` **com o envio ainda desligado**, `docker compose up -d backend` e o
   `/healthz` tem de seguir `UP`:
   ```
   EMAIL_MODO=DESLIGADO
   EMAIL_REMETENTE=AgroConIA <nao-responda@agroconia.com.br>
   SPRING_MAIL_HOST=<host SMTP do provedor>
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=<usuário SMTP>
   SPRING_MAIL_PASSWORD=<senha ou chave SMTP>
   ```
3. Só depois do teto de envios no app, da política de dados com o provedor como operador e da
   decisão sobre a transferência internacional (D7 da #460): `EMAIL_MODO=SMTP` e
   `docker compose up -d backend`. O aviso "Envio de e-mail desligado" some do log.
4. Teste real: os três fluxos para uma caixa da equipe e para o mail-tester, com SPF, DKIM e DMARC
   em `pass`, o link no host certo e nenhum envio para conta `@example.com` do seed.

**Reverter:** `EMAIL_MODO=DESLIGADO` no `.env` e `docker compose up -d backend`. Não precisa de
deploy de código. Se a chave SMTP vazar, revogue-a no painel do provedor e ponha a nova no `.env`.

A Oracle bloqueia a porta 25 de saída; a 587 e a 465 são liberadas, mas confira a egress da security
list se houver regra restritiva.

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

A máquina conferida em 2026-11-06 (8 OCPU, 47 GiB) está acima do limite do Always Free para a A1
(4 OCPU/24 GB). A conta é Pay As You Go: aumentar OCPU/RAM além desse limite ou criar outra VM A1
é cobrado. O que isso significa em custo para esta VM o Murillo ainda vai confirmar. O restante
(até 200 GB de disco, IP público efêmero) segue o Always Free.

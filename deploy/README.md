# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (Ceres),
ClamAV e Caddy (HTTPS automático) numa VM só.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 8 OCPU / 47 GiB de RAM, disco de 96 GB (conferido em 2026-11-06), Ubuntu 24.04 |
| Endereço | `https://www.agroconia.com.br` (`SITE_HOST`). `agroconia.com.br` e o antigo `agrocontabil.<ip-com-hífens>.sslip.io` redirecionam para ele (`SITE_REDIRECIONA`) |
| DNS | Hostinger (`dns-parking.com`): registro `A` de `@` e de `www` para o IP da VPS, sem `AAAA` (a VM não tem IPv6). O `A *` dos endereços dos escritórios entra pela seção [Endereço próprio do escritório](#endereço-próprio-do-escritório-464) |
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
variáveis e DNS. **O provedor é a Resend** (decisão do Murillo, 2026-11-09; comparação na #460). O
`compose.yaml` passa ao backend estas variáveis, todas com padrão seguro:

| Variável | Padrão no compose | Observação |
|----------|-------------------|------------|
| `EMAIL_MODO` | `DESLIGADO` | `SMTP` liga o envio. `LOG` só sobe em dev |
| `EMAIL_REMETENTE` | `AgroConIA <nao-responda@agroconia.com.br>` | Com `SMTP`, o domínio do endereço tem de ser `agroconia.com.br`, o verificado no provedor (`app.email.dominio-verificado`; o compose não repassa outro valor) |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | vazio, `587` | Resend: `smtp.resend.com`, `587` |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | vazios | Resend: `resend` e a API key. **Só no `.env` (600)**, nunca em issue, chat ou repositório |
| `EMAIL_SMTP_STARTTLS`, `EMAIL_SMTP_SSL` | `true`, `false` | Porta 587 com STARTTLS obrigatório. Na 465: `EMAIL_SMTP_SSL=true` e `EMAIL_SMTP_STARTTLS=false` |
| `EMAIL_TETO_POR_HORA`, `EMAIL_TETO_POR_DIA` | `0`, `80` | Teto de envios em janelas deslizantes de 1 h e de 24 h (`0` = sem teto), abaixo da cota da Resend Free (100 por dia UTC). No Pro (sem cota diária), suba o diário (por exemplo, `500`), mas não o zere: o teto também segura abuso e bounce. Ao voltar ao Free, `80` de novo |

**Salvaguardas no envio** (só com `SMTP`): destino em domínio reservado (`example.com`, `.test`,
`.invalid`, `.local` e afins; as contas do seed são `@example.com`) não sai; acima do teto, o
e-mail é descartado e o log registra só o tipo do e-mail; até 5 links de "esqueci minha senha"
seguidos por conta, e depois um a cada 4,8 horas. Nos três casos a resposta continua 202. As
respostas vão para `contato@gwnext.com.br` (`Reply-To`). O teto fica em memória: reiniciar o
backend zera a contagem. Nenhum log traz o endereço do destinatário.

### Resend: conta, DNS e chave

Valores conferidos em 2026-11-09. Em qualquer divergência, vale o que o painel da Resend mostrar.

1. **Conta e domínio.** Criar a conta em resend.com com o e-mail da GWNext e 2FA ligado. Em
   Domains > Add Domain, `agroconia.com.br` com a região **São Paulo (`sa-east-1`)**. Trocar a
   região depois exige apagar e recriar o domínio.
2. **DNS na Hostinger** (hPanel > Domínios > `agroconia.com.br` > DNS / Nameservers). No campo Nome
   vai só o prefixo, porque a Hostinger acrescenta o domínio. TXT sem aspas, TTL 3600. Nada vai na
   raiz:

   | Tipo | Nome | Valor |
   |------|------|-------|
   | MX | `send` | `feedback-smtp.sa-east-1.amazonses.com`, prioridade 10 |
   | TXT | `send` | `v=spf1 include:amazonses.com ~all` |
   | TXT | `resend._domainkey` | a chave `p=...` inteira do painel |
   | TXT | `_dmarc` | `v=DMARC1; p=none; adkim=r; aspf=r; fo=1` |

   O painel pode mostrar CNAMEs no lugar do par MX/TXT de `send`; nesse caso, use os do painel.
   Antes de clicar em "Verify DNS Records", confira com `nslookup -type=MX send.agroconia.com.br` e
   `nslookup -type=TXT resend._domainkey.agroconia.com.br`. Erro conhecido: o MX ganha o domínio no
   fim (`...amazonses.com.agroconia.com.br`); edite o registro com ponto final no destino. O DMARC
   fica em `p=none` até depois da banca. Nenhuma mudança no DNS de e-mail entre 26/11 e 03/12.
3. **Rastreamento.** Em Domain settings, Click Tracking e Open Tracking **desligados**: o link leva
   o token no fragmento (`#token=`), e a reescrita do link o quebraria.
4. **API key.** API Keys > Create, com permissão **Sending access** e só o domínio
   `agroconia.com.br`. A chave `re_...` aparece uma vez só e vai direto para o `.env` da VPS
   (`SPRING_MAIL_PASSWORD`). Se vazar, revogue-a no painel e crie outra; não precisa de deploy.
5. **Cota e acompanhamento.** O Free dá 100 e-mails por dia UTC (zera às 21h de Brasília) e 3.000
   por mês. O teto do app (80) fica abaixo disso. Em Usage e Metrics, mantenha o bounce abaixo de
   4% e a reclamação abaixo de 0,08%, ou a Resend pode suspender a conta. A Resend não avisa
   sozinha perto do fim da cota; o aviso aos 80% sai no log do backend.
6. **LGPD.** A Resend guarda nos EUA os dados da conta e os logs, com o corpo do e-mail (e o link)
   por 30 dias, qualquer que seja a região de envio, e não assina as cláusulas-padrão da ANPD. O
   envio só liga com a D7 da #460 resolvida pelo jurídico e com a política de dados atualizada.

Os links dos e-mails apontam para `https://${SITE_HOST}`: `/redefinir-senha`, `/escritorio` (onde o
produtor vê os convites) e `/equipe/convite`.

**O que impede a subida com `EMAIL_MODO=SMTP`:** host, usuário ou senha vazios, remetente fora do
domínio verificado e link que não seja `https` (fora de dev/test). Com o envio desligado nada disso
é conferido. Quem sobe `homolog` na própria máquina com `SMTP` precisa passar as três URLs em
`https`.

**Health:** o health do SMTP fica desligado (`management.health.mail.enabled: false` no
`application.yml` e `MANAGEMENT_HEALTH_MAIL_ENABLED` no compose). Com ele ligado, o provedor fora do
ar ou o `SPRING_MAIL_HOST` vazio poriam o `/actuator/health` em `DOWN`, e o `deploy.sh` reverteria o
deploy. A falha de envio aparece só no log do backend: procure `SMTP: N falhas seguidas` (conexão,
autenticação ou cota; sai na 3ª falha seguida e a cada 10), `Teto de e-mail` e
`envios permitidos em` (aviso aos 80% do teto).

**Ordem para ligar** (o compose novo e a imagem nova podem chegar em qualquer ordem):

1. Conta, domínio, DNS, rastreamento e API key da Resend (seção acima), com o domínio verificado.
2. Credenciais no `.env` **com o envio ainda desligado**, `docker compose up -d backend` e o
   `/healthz` tem de seguir `UP`:
   ```
   EMAIL_MODO=DESLIGADO
   EMAIL_REMETENTE=AgroConIA <nao-responda@agroconia.com.br>
   SPRING_MAIL_HOST=smtp.resend.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=resend
   SPRING_MAIL_PASSWORD=<API key re_... da Resend>
   ```
3. Só depois da política de dados com a Resend como operadora e da decisão sobre a transferência
   internacional (D7 da #460): `EMAIL_MODO=SMTP` e `docker compose up -d backend`. O aviso "Envio
   de e-mail desligado" some do log. Se usar o Pro na janela da banca (D11), suba o
   `EMAIL_TETO_POR_DIA` enquanto ele durar e volte a `80` quando a conta voltar ao Free.
4. Teste real: os três fluxos para uma caixa da equipe e para o mail-tester. No
   `Authentication-Results`, confira `spf=pass` (`send.agroconia.com.br`), `dkim=pass`
   (`d=agroconia.com.br`) e `dmarc=pass`, o link no host certo e nenhum envio para conta
   `@example.com` do seed.

**Reverter:** `EMAIL_MODO=DESLIGADO` no `.env` e `docker compose up -d backend`. Não precisa de
deploy de código.

A Oracle bloqueia a porta 25 de saída; a 587 e a 465 são liberadas, mas confira a egress da security
list se houver regra restritiva.

## Endereço próprio do escritório (#464)

Cada escritório pode ter o próprio endereço, `{slug}.agroconia.com.br`. O produtor continua em
`www`. O backend já serve o recurso: slug, endpoint público, o "ask" do Caddy e o login no
subdomínio. Até os passos abaixo, ele fica **dormente**: sem o registro `A *` e sem o Caddyfile
novo, nenhum subdomínio abre, e o `www` não muda.

**Quando ligar:** só com o backend e o frontend (modo escritório) prontos e ensaiados. Se não
estiver pronto até o congelamento (26/11), fica para depois da banca (03/12). Todos os passos são
manuais e do Murillo, na VPS e na Hostinger.

### Como funciona

- **Certificados sob demanda:** o Caddy pede um certificado por slug, no primeiro acesso, via
  On-Demand TLS. Antes de pedir, ele pergunta ao backend: `GET
  http://backend:8080/interno/tls/permitido?chave=...&domain=...`. Essa rota fica fora de `/api`,
  então só a rede interna a alcança.
- **Quem o backend autoriza:** só responde 200 com a `TLS_ASK_CHAVE` certa e um slug servido
  (ativo, com CNPJ, ou em carência com redirecionamento). Slug inexistente ou reservado, inclusive o
  `www`, nunca ganha certificado, e o handshake falha.
- **Site catch-all, nunca o curinga:** o bloco é `https://`, e não `*.{$SITE_DOMINIO}`. Testado em
  2026-11-09 com o `caddy:2.10-alpine`: com o site curinga, o Caddy **deixa de emitir o certificado
  do `www`** (passa a esperar o certificado curinga, que o `on_demand` nunca emite), e o `www` cai.
  Com o catch-all, os sites com nome (`www`, os redirecionamentos) seguem com o certificado próprio,
  e o matcher `host *.{$SITE_DOMINIO}` mais o `abort` barram o resto.
- **Cabeçalhos:** o site dos escritórios importa o mesmo snippet `(app)` do `www` (HSTS, `nosniff`,
  `Referrer-Policy: no-referrer`, sem `Server`). Sem o `no-referrer`, o slug iria no `Referer` para
  as fontes externas. Ele também leva `X-Robots-Tag: noindex`, porque o endereço do escritório não
  entra em buscador (frontend#175).
- **Exposição:** cada slug emitido vai para os registros públicos de Certificate Transparency
  (crt.sh), de forma permanente. Por isso o `PUT /api/escritorio/endereco` exige a ciência do
  administrador e o CNPJ do escritório.
- **Limites do Let's Encrypt:**
  - 50 certificados novos por domínio registrado a cada 7 dias (renovações não contam);
  - 5 validações com falha por nome por hora.

  Por isso o primeiro teste é na CA de staging.

### Passo a passo

1. **Pré-requisitos:**
   - o backend com a #464 já no ar (o CD implanta a `main`);
   - o frontend com o modo escritório;
   - o reset da demonstração ([`docs/demonstracao-banca.md`](../docs/demonstracao-banca.md)), que
     dá o slug `ruraloeste` à Contabilidade Rural Oeste.
2. **`.env`** (antes de copiar o `compose.yaml` novo, que exige as duas variáveis):

   ```bash
   cd /opt/agrocontabil && cp .env .env.anterior
   echo "SITE_DOMINIO=agroconia.com.br" >> .env
   echo "TLS_ASK_CHAVE=$(openssl rand -hex 32)" >> .env   # so letras e digitos: vai na URL
   grep -qE '^TLS_ASK_CHAVE=[A-Za-z0-9_-]{32,}$' .env && echo "chave ok"
   ```

   **Hex, nunca Base64.** Uma chave com `+`, `/` ou `=` chega alterada na URL do ask. O backend
   sobe assim mesmo, mas avisa em ERROR no log e o ask recusa todos os certificados.

3. **DNS na Hostinger:**
   - **criar:** registro `A`, nome `*`, apontando para o IP da VPS, com o TTL padrão;
   - **não criar:** registro `AAAA`;
   - **manter:** os registros de `@` e de `www` (o curinga não os sobrepõe).

   Conferir, de fora da VPS:

   ```bash
   dig +short qualquercoisa.agroconia.com.br   # o IP da VPS
   dig +short ruraloeste.agroconia.com.br      # o IP da VPS
   dig +short www.agroconia.com.br             # o IP da VPS, como antes
   ```

   A Hostinger ressalva que domínio *hospedado* nela exige criar cada subdomínio. Aqui só o DNS
   está lá, e o `dig` tira a dúvida.
4. **Arquivos**, guardando os anteriores para reverter:

   ```bash
   cp Caddyfile Caddyfile.anterior && cp compose.yaml compose.yaml.anterior
   # copiar deploy/Caddyfile e deploy/compose.yaml do repositorio para /opt/agrocontabil
   ```

5. **CA de staging primeiro.** No `Caddyfile` da VPS, dentro do bloco `https://`, acrescentar a
   linha da CA de staging ao `tls`:

   ```caddyfile
   tls {
   	on_demand
   	ca https://acme-staging-v02.api.letsencrypt.org/directory
   }
   ```

6. **Validar antes de aplicar.** Um Caddyfile inválido derruba o `www` na subida do container.

   ```bash
   docker compose config -q && echo "compose ok"
   docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
   ```

   O segundo comando precisa dizer `Valid configuration`.
7. **Aplicar.** O backend e o Caddy são recriados com as variáveis novas. O backend leva ~2 min
   para ficar `healthy`, e o Caddy reinicia em segundos; os certificados ficam no volume
   `caddy_data`. Fazer num horário calmo.

   ```bash
   docker compose up -d
   ```

8. **Conferir:**

   ```bash
   curl -s https://www.agroconia.com.br/healthz        # UP: o www segue igual
   docker compose exec caddy sh -c 'wget -qSO- "http://backend:8080/interno/tls/permitido?chave=$TLS_ASK_CHAVE&domain=ruraloeste.agroconia.com.br" 2>&1 | head -1'   # 200
   docker compose exec caddy sh -c 'wget -qSO- "http://backend:8080/interno/tls/permitido?chave=$TLS_ASK_CHAVE&domain=naoexiste.agroconia.com.br" 2>&1 | head -1'    # 404
   curl -vk https://ruraloeste.agroconia.com.br/ 2>&1 | grep -i "issuer"   # (STAGING); o 1o acesso espera alguns segundos
   curl -skI https://ruraloeste.agroconia.com.br/ | grep -iE "x-robots-tag|referrer-policy"   # noindex e no-referrer
   curl -v https://naoexiste.agroconia.com.br/ 2>&1 | tail -2              # falha no handshake
   docker compose logs caddy | grep -iE "on_demand|obtain|error" | tail
   ```

   Depois, o login de `ruraloeste.agroconia.com.br` com a administradora da demonstração. Pelo
   `www`, todas as contas entram como sempre.
9. **Produção.**
   - Tirar a linha `ca` do `Caddyfile`, validar de novo (passo 6) e reiniciar o Caddy:
     `docker compose restart caddy`. O reinício limpa o certificado de staging da memória, e o
     próximo acesso pede o de produção.
   - Conferir com `curl -v https://ruraloeste.agroconia.com.br/ 2>&1 | grep -i issuer`: o emissor
     tem de ser o Let's Encrypt, sem "STAGING".
10. **Reverter**, se algo der errado:

    ```bash
    cp Caddyfile.anterior Caddyfile && docker compose restart caddy
    ```

    Isso volta ao site único, e o `www` segue. O registro `A *` e as variáveis do `.env` podem
    ficar: sem o bloco `https://`, o Caddy não atende os subdomínios.

### Rotação da chave do ask

Trocar a `TLS_ASK_CHAVE` no `.env` e rodar `docker compose up -d backend caddy`. Os dois leem a
mesma variável. Os certificados já emitidos continuam valendo; só os slugs novos dependem do ask.

A chave vai na URL do ask, porque o módulo de permissão do Caddy 2.10 não manda cabeçalho. Por
isso ela fica na configuração do Caddy (a API de admin, só dentro do container) e pode aparecer nos
logs dele quando o ask falha. Ela só vale na rede interna do compose, mas não a cole em chat nem em
issue. Se vazar, rotacione.

## Primeira subida

```bash
cd /opt/agrocontabil
cp .env.example .env && chmod 600 .env   # preencher; segredos:
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # CRIPTO_CHAVE
openssl rand -hex 32      # TLS_ASK_CHAVE (vai na URL do ask: hex, nunca base64); e SITE_DOMINIO=agroconia.com.br
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

# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (embedding e modelo local da Ceres; desde 2026-11-10 o chat vai pela API da Anthropic),
ClamAV e Caddy (HTTPS automático) numa VM só.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 8 OCPU / 47 GiB de RAM, disco de 96 GB (conferido em 2026-11-06), Ubuntu 24.04 |
| Endereço | `https://www.agroconia.com.br` (`SITE_HOST`). `agroconia.com.br` e o antigo `agrocontabil.<ip-com-hífens>.sslip.io` redirecionam para ele (`SITE_REDIRECIONA`) |
| DNS | Hostinger (`dns-parking.com`): registro `A` de `@` e de `www` para o IP da VPS, sem `AAAA` (a VM não tem IPv6). O `A *` dos endereços dos escritórios entra pela seção [Endereço próprio do escritório](#endereço-próprio-do-escritório-464) |
| Acesso SSH | `ssh -i ~/.ssh/agrocontabil_lightsail ubuntu@<ip>` (a chave privada fica só no PC de quem fez o deploy) |
| Pasta na VM | `/opt/agrocontabil` — `compose.yaml`, `Caddyfile`, `.env` (600), `certs/nfe-teste.p12`, `jornada/` (quadros da landing, seção [Jornada da landing](#jornada-da-landing-frontend174)) |
| Imagens | `ghcr.io/supersonicteam/backend` e `ghcr.io/supersonicteam/frontend` (workflow `docker-publish.yml` de cada repo, amd64 + arm64) |

## Profile `homolog`, sem senha no Caddy

Desde o login do frontend (frontend#93, 2026-10-30) o backend sobe em **`homolog`**: só o JWT do
login autentica (o mock do `X-Usuario-Id` é só do `dev`, #221), com `JWT_SECRET` e `CRIPTO_CHAVE`
próprios do `.env`. Por isso o Caddy não pede mais senha (o basic auth saiu em 2026-11-01). Até
então o backend rodava em `dev`, onde qualquer requisição declarava qualquer identidade, e o basic
auth era a única barreira.

Continua sendo **demonstração com dados fictícios**: o certificado da NF-e é o de teste (público),
a SEFAZ é o mock (com `autorizar-terceiros` ligado, para a importação de NF-e de terceiro: a
conferência dela na SEFAZ é simulada; o profile `prod` recusa o mock, #263) e o envio
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

**Formato (#485):** os três e-mails saem em `multipart/alternative` (texto puro e HTML, em UTF-8),
de um modelo só (`service/ModeloEmail`, textos em `service/MensagensEmail`). O logo é o
`/apple-touch-icon.png` do frontend, no host do link do e-mail; sem pixel nem link de rastreamento.
Para ver como ficam, `./mvnw test -Dtest=PreviaEmailsManual -Dprevia.saida=<pasta>` gera o HTML, o
texto e o `.eml` de cada um com dados de exemplo.

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

## Modelo da Ceres pela API da Anthropic (#483)

**Estado da VPS: desde 2026-11-10 a Ceres responde pelo `claude-haiku-5-5`** (decisão do Murillo).
O padrão do `compose.yaml` continua sendo o `qwen3:4b-instruct` no Ollama local; quem liga o
Claude é o `.env`. A VPS é ambiente de demonstração, com dados semeados: **para produtores reais,
a política de dados e a transferência internacional (LGPD, art. 33) precisam estar resolvidas
antes** (pendência no `CLAUDE.md`, "Modelo da Ceres", e em `docs/lgpd.md`).

Para refazer a troca noutro ambiente:

1. A imagem do backend precisa ser a do PR #483 ou posterior (o CD cuida disso no merge).
2. **Tome o lock do deploy e faça os passos 3 a 6 dentro dele**, para não cruzar com um deploy do
   CD (o `deploy.sh` regrava o `.env` e recria o backend): `cd /opt/agrocontabil && flock -w 900
   .deploy.lock bash` abre um shell com o lock; `exit` ao terminar. Um deploy que chegue nesse
   intervalo espera até 15 minutos, então combine a janela sem merges.
3. No `.env` (600), acrescente `AI_CHAT_PROVIDER=anthropic` e `ANTHROPIC_API_KEY=<chave>`. A chave
   é criada no Claude Console **ligada a um workspace** (sem isso a API recusa toda chamada com
   400), própria do ambiente, e não passa por chat, issue nem repositório. O `AI_CHAT_MODEL` não
   muda; o modelo da Anthropic é o `AI_ANTHROPIC_MODEL` (padrão `claude-haiku-5-5`). Com o
   compose antigo essas linhas ainda não têm efeito.
4. O `compose.yaml` precisa repassar o provedor e a chave ao backend (o antigo fixa
   `AI_CHAT_PROVIDER: ollama` e ignora o `.env`). **Não copie o `deploy/compose.yaml` inteiro
   enquanto o `.env` não tiver `SITE_DOMINIO` e `TLS_ASK_CHAVE`** (seção "Endereço próprio do
   escritório"): ele os exige e o `docker compose` recusa qualquer comando sem eles. Nesse caso,
   leve ao compose em uso só os dois trechos do provedor: no `ollama-init`, o `AI_CHAT_PROVIDER` e
   o `entrypoint` que só baixa o modelo de chat quando ele é local; no `backend`,
   `AI_CHAT_PROVIDER`, `ANTHROPIC_API_KEY`, `AI_ANTHROPIC_MODEL` e `AI_ANTHROPIC_THINKING`. Na VPS,
   em 2026-11-10, o compose em uso era o `deploy/compose.yaml` do commit `59a8262` (#471) e passou
   a ser esse arquivo mais os dois trechos (anterior em `compose.yaml.bak-20261110-1823`). Enquanto
   o `.env` de lá não tiver as variáveis do #464, toda mudança de deploy entra assim, à mão.
5. Grave o arquivo novo ao lado e valide com o `.env` real:
   `docker compose -f <arquivo novo> config -q`. Guarde o anterior (`cp -p compose.yaml
   compose.yaml.bak-<data>`) e só então troque.
6. `docker compose up -d --no-deps backend` e espere o container ficar `healthy`. Se não ficar,
   volte ao compose anterior e rode o mesmo comando.
7. Fora do lock, confira com uma pergunta **digitada** à Ceres pelo site (não um dos três atalhos
   da tela, que não chamam o modelo) e leia a auditoria:

   ```bash
   docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select criado_em, modelo, rota, desfecho, camada_classificacao, tokens_entrada, latencia_ms from assistente_interacao order by criado_em desc limit 5"'
   ```

   Vale como prova a linha com `modelo` `anthropic/claude-haiku-5-5`, `desfecho` diferente de
   `MODO_FALLBACK` **e `tokens_entrada` preenchido**. Linha sem tokens não chamou o modelo (atalho
   fixo, por exemplo) e sai com o mesmo `modelo` mesmo com a chave inválida.

Sem a chave o backend **não sobe** (`AnthropicApiKeyConfig`). O Ollama segue no ar: o embedding
(`bge-m3`) é sempre local. Com o provedor por API, a pergunta e os dados de negócio do produtor,
já mascarados, saem do servidor (`docs/lgpd.md`, "Provedor externo do modelo da Ceres").

Medido na VPS em 2026-11-10: nas quatro primeiras chamadas ao modelo (duas perguntas fora de
escopo e duas de dados), de 1,0 a 1,4 s cada, e cerca de 6.000 tokens de entrada por pergunta de
dados. Lei e misto não foram medidos na VPS; na medição local com o gabarito (87 × 3), p50 de
1,4 s e p95 de 4,2 s no geral, com o misto em 4,2 s de p50.

**Reverter:** comente `AI_CHAT_PROVIDER` no `.env` e rode `docker compose up -d --no-deps backend`.
O backend volta ao `qwen3:4b-instruct` do Ollama, que segue baixado. Se ele ficou mais de 24 horas
sem uso (`OLLAMA_KEEP_ALIVE`), a primeira pergunta depois da reversão demora mais: o modelo volta
a ser carregado na memória.

## Acessos da página inicial (#484)

O QR do cartão de visita leva a `https://www.agroconia.com.br/?utm_source=cartao`. Para contar as
escaneadas, o Caddy registra os acessos à página inicial do `www`, num arquivo do volume
`caddy_data`, que sobrevive aos deploys. Conta acessos, não pessoas: a mesma pessoa que escaneia
duas vezes conta duas.

**O que entra (minimizado, sem dado pessoal):**
- **Só a raiz `/` do `www`.** Ficam fora:
  - `/api`, que leva CPF/CNPJ na busca;
  - o `/healthz` do monitor;
  - as rotas e os arquivos do SPA, porque a query de uma rota recarregada vem da tela;
  - os endereços dos escritórios.
- **Campos de cada linha:** data, método, host, URI (com a query, onde fica o `utm_source`),
  status, tamanho, duração e o user agent. O user agent separa robô de pessoa.
- **Fora de cada linha:**
  - IP (`remote_ip` e `client_ip`) e porta;
  - todos os cabeçalhos da requisição (cookies inclusive) e os da resposta;
  - os identificadores de clique que redes e anúncios acrescentam ao link (`fbclid`, `gclid`,
    `msclkid` e afins), que ligam o acesso a um perfil. Saem da URI; o `utm_source` fica.

Conferido no `caddy:2.10-alpine`. Com `/?utm_source=cartao`, `/api/notas?busca=<CPF>`, `/healthz`,
`/notas?busca=<CPF>` e um asset, todos com cookie, só a primeira requisição foi registrada, sem IP
nem cookie.

**Retenção curta:**
- **Arquivo ativo:** `/data/acessos/www.log`, até 1 MiB. Com ~300 a 350 bytes por linha, cabem
  ~3.000 acessos à página inicial: ~60 dias a 50 acessos por dia, ~15 dias a 200 por dia.
- **Antigos:** passando de 1 MiB, o ativo vira um antigo compactado (`www-<data>.log.gz`). Ficam no
  máximo 3, apagados 30 dias depois. O que importar para o relatório, conte e anote antes.

### Contar

Total de escaneadas do cartão, no ativo e nos antigos:

```bash
docker compose exec -T caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep -c 'utm_source=cartao'"
```

Por dia, em Brasília:

```bash
docker compose exec -T caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep 'utm_source=cartao' | sed 's/.*\"ts\":\([0-9]*\).*/\1/' | TZ=UTC+3 awk '{ print strftime(\"%Y-%m-%d\", \$1) }' | sort | uniq -c"
```

Trocar `utm_source=cartao` por outra origem conta outra campanha. Sem filtro, o primeiro comando
conta os acessos à página inicial.

### Aplicar na VPS

Desde 2026-11-10, a VPS roda o `Caddyfile` da `main`, que já traz o registro. Num Caddyfile mais
antigo, entram só as linhas do `log` no bloco `{$SITE_HOST}`: o bloco `log { ... }`, o
`@foraDaContagem`, o `log_skip` e o `log_append`.

```bash
cd /opt/agrocontabil && cp Caddyfile Caddyfile.anterior
# editar o Caddyfile (bloco {$SITE_HOST})
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
```

- **Sem queda:** o `reload` troca a configuração sem reiniciar o Caddy. Com o arquivo inválido, ele
  recusa e segue com a anterior.
- **Conferir:**
  1. abrir `https://www.agroconia.com.br/?utm_source=cartao`;
  2. rodar o primeiro comando de contagem;
  3. ver com `docker compose exec caddy cat /data/acessos/www.log` que a linha não tem IP.
- **Reverter:** `cp Caddyfile.anterior Caddyfile` e o mesmo `reload`.

## Jornada da landing (frontend#174)

A landing tem uma seção 3D: uma sequência de imagens que anda com a rolagem. Os quadros **não vão
na imagem do frontend** (seriam 81 MB a mais em cada imagem; decisão de 2026-11-09). Ficam na VPS,
em `/opt/agrocontabil/jornada/<versão>/`, e o Caddy os serve em `/jornada/<versão>/`, no mesmo host
do site. O player lê `/jornada/v0.1/manifesto.json` e tira dele os caminhos dos quadros.

- **Pacote:** o zip da Release do repositório privado `luizGervazio/Animation-Agroconia`
  (`agroconia-jornada-v0.1.zip`: 75 MB, 2.442 arquivos, em AVIF e em WebP, a reserva para navegador
  sem AVIF). No ar desde 2026-11-12: a `v0.1`.
- **No Caddy:** o `handle_path /jornada/*` do snippet `(app)` serve a pasta `/srv/jornada`, que o
  `compose.yaml` monta de `./jornada`, só para leitura. Vale também nos endereços dos escritórios
  (o mesmo snippet). O `nginx.conf` do frontend não entra nisso: o Caddy responde antes.
- **Cache de um ano** (`Cache-Control: public, max-age=31536000, immutable`), só em arquivo que
  existe. Por isso **uma versão publicada nunca muda**: pacote novo entra como pasta nova (`v0.2`).
  Um arquivo trocado dentro da `v0.1` não chegaria a quem já visitou. O 404 sai sem o cabeçalho,
  para a falta de um arquivo não ficar guardada no navegador.
- **Só imagem e JSON, e nada enviado por cliente.** A pasta é servida na origem do aplicativo, em
  todo host. Um `.html` ali rodaria com a sessão de quem o abrisse (o cookie de refresh não separa
  subdomínios, #464). Por isso há duas barreiras: o Caddy só entrega `.avif`, `.webp` e `.json`
  (o resto é 404, mesmo que o arquivo exista), e o `publicar-jornada.sh` só deixa entrar `.avif`,
  `.webp` e o `manifesto.json`. O `LEIA-ME.md` do pacote, documento interno, fica de fora.

Conferido no `caddy:2.10-alpine`: `manifesto.json` sai como `application/json`, `.avif` como
`image/avif` e `.webp` como `image/webp`, sem regra de tipo no Caddyfile. Caminho com `..` não chega
ao disco (cai no frontend, como qualquer rota fora de `/jornada/*`).

### Publicar um pacote

**O pacote entra antes do frontend que o pede.** Até ele chegar, o player recebe 404 (sem cache).

```bash
# no PC, com o zip da Release
scp -i ~/.ssh/agrocontabil_lightsail agroconia-jornada-v0.2.zip ubuntu@<ip>:/tmp/
# na VPS
cd /opt/agrocontabil
./publicar-jornada.sh /tmp/agroconia-jornada-v0.2.zip v0.2 && rm /tmp/agroconia-jornada-v0.2.zip
```

O script (nesta pasta, copiado para `/opt/agrocontabil`) extrai e confere o pacote fora de
`jornada/` e só então o move para `jornada/v0.2/`, de uma vez. Ele recusa a versão que já existe, o
manifesto de outra versão, qualquer arquivo fora dos tipos esperados e o pacote com mais de 1 GB
aberto (`JORNADA_TETO_MB`; a extração cai no disco do banco). Nenhum container reinicia: a pasta
nova já aparece no Caddy.

Conferir de fora da VPS. O status 200 sozinho não prova nada: sem a rota no Caddy, `/jornada/*` cai
no frontend, que devolve o `index.html` com 200 e `text/html`.

```bash
curl -sI https://www.agroconia.com.br/jornada/v0.2/manifesto.json | grep -iE "^(HTTP|content-type|cache-control)"            # 200, application/json
curl -sI https://www.agroconia.com.br/jornada/v0.2/parados/16x9/nome.avif | grep -iE "^(HTTP|content-type|cache-control)"   # 200, image/avif
```

**Apagar uma versão antiga** (`rm -rf jornada/v0.1`) só depois de o frontend que a pede sair do ar.
Uma aba aberta antes do deploy continua pedindo a versão antiga.

### Primeira vez num ambiente

A montagem da pasta só entra quando o container do Caddy é recriado; o `reload` não basta.

```bash
cd /opt/agrocontabil
mkdir -p jornada                                  # antes do up: se faltar, o Docker cria a pasta vazia e do root
# copiar compose.yaml, Caddyfile e publicar-jornada.sh do repositório; publicar o pacote (acima)
docker compose config -q                          # o compose novo com o .env real
docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile   # Valid configuration
docker compose up -d --no-deps caddy              # recria só o Caddy; o HTTPS cai por alguns segundos
curl -s https://www.agroconia.com.br/healthz      # UP
```

- **Validar antes de recriar:** com o Caddyfile inválido, o container novo não sobe e o site cai. O
  `run` valida num container à parte, com o arquivo e as variáveis que o novo vai usar.
- **`--no-deps`:** evita recriar o backend e o frontend quando o `compose.yaml` copiado também
  mudou para eles. Essas mudanças entram no próximo deploy de cada um.
- **Certificados:** ficam no volume `caddy_data` e sobrevivem à recriação.
- **Reverter:** voltar o `compose.yaml` e o `Caddyfile` anteriores e repetir o `up`.

## Endereço próprio do escritório (#464)

Cada escritório pode ter o próprio endereço, `{slug}.agroconia.com.br`. O produtor continua em
`www`. O backend já serve o recurso: slug, endpoint público, o "ask" do Caddy e o login no
subdomínio. Desde 2026-11-10, a VPS já roda o `Caddyfile` e o `compose.yaml` da `main`, com o
bloco `https://` e o "ask" no ar, e o `.env` já tem `SITE_DOMINIO` e `TLS_ASK_CHAVE`. O recurso
segue **dormente** só porque falta o registro `A *`: sem ele, nenhum subdomínio resolve, e o `www`
não muda.

**Quando ligar:** só com o backend e o frontend (modo escritório) prontos e ensaiados. Se não
estiver pronto até o congelamento (26/11), fica para depois da banca (03/12). Todos os passos são
manuais e do Murillo, na VPS e na Hostinger.

### Como funciona

- **Certificados sob demanda:** o Caddy pede um certificado por slug, no primeiro acesso, via
  On-Demand TLS. Antes de pedir, ele pergunta ao backend: `GET
  http://backend:8080/interno/tls/permitido?chave=...&domain=...`. Essa rota fica fora de `/api`,
  então só a rede interna a alcança.
- **Quem o backend autoriza:** só responde 200 com a `TLS_ASK_CHAVE` certa e um slug servido
  (ativo, ou em carência com redirecionamento) de escritório com o **CNPJ verificado** (#520, seção
  abaixo). Slug inexistente, reservado (inclusive o `www`) ou de escritório não verificado nunca
  ganha certificado, e o handshake falha.
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

### Verificação do CNPJ (#520)

O cadastro público de contador empresarial grava o CNPJ e o slug na hora, e o CNPJ só tem o dígito
verificador conferido. Sem a verificação, uns 50 cadastros com slugs diferentes esgotariam a cota
semanal de certificados do Let's Encrypt (com o `A *` no ar, qualquer subdomínio resolve). Por
isso o ask, a página pública e o login pelo subdomínio só valem para o escritório com o CNPJ
verificado. O slug do escritório não verificado fica reservado (ninguém mais o grava), mas não é
servido.

- **Quem verifica:** a GWNext, depois de conferir fora do sistema que o CNPJ é do escritório
  (contrato social, cartão CNPJ, contato com o escritório). Não há endpoint: é o comando do profile
  `verificacao-cnpj`, auditado (`ESCRITORIO_CNPJ_VERIFICADO`) sem o CNPJ no registro nem no log.
- **Trocar o CNPJ** (`PATCH /api/escritorio`) tira a verificação na hora: o endereço deixa de ser
  servido e o login pelo subdomínio cai no próximo refresh. Trocar o nome não tira.
- **A demonstração não precisa de nada:** o `SeedDemonstracao` marca a Contabilidade Rural Oeste
  como verificada (método `DEMONSTRACAO`) ao criá-la e, no banco já semeado, na subida do backend.
  Isso depende de `DEMONSTRACAO_HABILITADA=true` no `compose.yaml` da VPS.

**Depois do deploy da #520**, conferir que o `ruraloeste` continua servido (os outros slugs de
teste deixam de ser, de propósito):

```bash
docker compose logs backend | grep -iE "CNPJ do escritorio" | tail -3
docker compose exec caddy sh -c 'wget -qSO- "http://backend:8080/interno/tls/permitido?chave=$TLS_ASK_CHAVE&domain=ruraloeste.agroconia.com.br" 2>&1 | head -1'   # 200
```

O seed roda logo depois de o backend aceitar conexões: nos primeiros segundos da subida, o ask do
`ruraloeste` ainda responde 404. Com o certificado já emitido e guardado pelo Caddy, nada muda para
quem acessa. Sem o 200 depois disso, verifique pelo comando abaixo, com o id e o CNPJ do escritório
da demonstração.

**Verificar um escritório** (o backend continua no ar; o comando sobe outro container, sem web nem
jobs, e encerra):

```bash
cd /opt/agrocontabil
docker compose exec -T postgres psql -U agrocontabil -d agrocontabil -c   "SELECT id, nome, cnpj, cnpj_verificado_em FROM escritorio WHERE slug = 'campoverde'"
docker compose run --rm -e SPRING_PROFILES_ACTIVE=homolog,verificacao-cnpj backend   --verificacao.escritorio=<id> --verificacao.cnpj=<cnpj conferido>
echo $?   # 0 = verificado (ou ja estava); 1 = recusado, motivo no log
```

- O `--verificacao.cnpj` é o CNPJ que você conferiu. Se o administrador o trocou depois da
  conferência, o comando recusa (`CNPJ_DIFERENTE_DO_CONFERIDO`): confira o novo.
- Um CNPJ fica verificado em um escritório só (`CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO`). Numa
  contestação, revogue o errado antes de verificar o outro.
- **Revogar:** `--verificacao.escritorio=<id> --verificacao.revogar=true`. O endereço sai do ar na
  hora. O certificado já emitido continua no cache do Caddy até vencer, mas o login pelo
  subdomínio e a página pública param. **No escritório da demonstração a revogação não dura:** a
  próxima subida do backend o verifica de novo pelo seed. Para tirá-lo do ar, remova o endereço
  (`DELETE /api/escritorio/endereco`, com a administradora) ou desligue o seed.
- Local: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,verificacao-cnpj
  -Dspring-boot.run.arguments="--verificacao.escritorio=<id> --verificacao.cnpj=<cnpj>"`.

### Passo a passo

**A ordem importa.** O bloco `https://` já está no ar: no instante em que o registro `A *`
existir, o primeiro acesso a um slug servido pede o certificado. Por isso a CA de staging entra
**antes** do DNS. Na ordem inversa, o primeiro certificado sai direto em produção, e um erro gasta
a cota de falhas do Let's Encrypt.

1. **Pré-requisitos:**
   - o backend com a #464 já no ar (o CD implanta a `main`);
   - o frontend com o modo escritório;
   - o reset da demonstração ([`docs/demonstracao-banca.md`](../docs/demonstracao-banca.md)), que
     dá o slug `ruraloeste` à Contabilidade Rural Oeste.
2. **Conferir o `.env`** (já preenchido desde 2026-11-10):

   ```bash
   cd /opt/agrocontabil
   grep -q '^SITE_DOMINIO=agroconia.com.br$' .env && echo "dominio ok"
   grep -qE '^TLS_ASK_CHAVE=[A-Za-z0-9_-]{32,}$' .env && echo "chave ok"
   ```

   **Hex, nunca Base64.** Uma chave com `+`, `/` ou `=` chega alterada na URL do ask. O backend
   sobe assim mesmo, mas avisa em ERROR no log e o ask recusa todos os certificados. Para gerar uma
   nova: `openssl rand -hex 32` (e veja "Rotação da chave do ask").
3. **Conferir os arquivos**, já os da `main` desde 2026-11-10. O `Caddyfile` tem de ter o bloco
   `https://` com `tls { on_demand }` e o `on_demand_tls` com o ask. O `compose.yaml` tem de
   repassar a chave ao backend (`APP_ESCRITORIO_TLS_ASK_CHAVE`) e ao Caddy (`TLS_ASK_CHAVE`). Guardar
   uma cópia do Caddyfile para reverter:

   ```bash
   grep -n "on_demand" Caddyfile
   grep -n "TLS_ASK_CHAVE" compose.yaml   # duas linhas: backend e caddy
   cp Caddyfile Caddyfile.anterior
   ```

4. **CA de staging, antes do DNS.** No `Caddyfile` da VPS, dentro do bloco `https://`,
   acrescentar a linha da CA de staging ao `tls`:

   ```caddyfile
   tls {
   	on_demand
   	ca https://acme-staging-v02.api.letsencrypt.org/directory
   }
   ```

5. **Validar, aplicar e conferir o ask, ainda sem DNS.**

   ```bash
   docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile
   docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
   docker compose exec caddy sh -c 'wget -qSO- "http://backend:8080/interno/tls/permitido?chave=$TLS_ASK_CHAVE&domain=ruraloeste.agroconia.com.br" 2>&1 | head -1'   # 200
   docker compose exec caddy sh -c 'wget -qSO- "http://backend:8080/interno/tls/permitido?chave=$TLS_ASK_CHAVE&domain=naoexiste.agroconia.com.br" 2>&1 | head -1'    # 404
   ```

   - O `validate` precisa dizer `Valid configuration`.
   - O `reload` troca a configuração sem derrubar o `www`. Com o arquivo inválido, ele recusa e
     segue com a anterior.
   - Um editor que grava o arquivo novo por cima, com outro inode, deixa o container com o antigo.
     Nesse caso, `docker compose restart caddy`.
   - Sem o 200 no `ruraloeste`, pare aqui: a chave não bate entre o backend e o Caddy, ou falta o
     reset da demonstração. Com o DNS ainda sem o `A *`, nada mudou para fora.
6. **DNS na Hostinger**, só agora:
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
7. **Conferir.** O `sed` do último comando mascara a chave do ask: com o backend fora do ar, o
   Caddy loga em ERROR a URL inteira do ask, com a chave.

   ```bash
   curl -s https://www.agroconia.com.br/healthz        # UP: o www segue igual
   curl -vk https://ruraloeste.agroconia.com.br/ 2>&1 | grep -i "issuer"   # (STAGING); o 1o acesso espera alguns segundos
   curl -skI https://ruraloeste.agroconia.com.br/ | grep -iE "x-robots-tag|referrer-policy"   # noindex e no-referrer
   curl -v https://naoexiste.agroconia.com.br/ 2>&1 | tail -2              # falha no handshake
   docker compose logs caddy | grep -iE "on_demand|obtain|error" | sed -E 's/chave=[^&" ]+/chave=***/g' | tail
   ```

   Depois, o login de `ruraloeste.agroconia.com.br` com a administradora da demonstração. Pelo
   `www`, todas as contas entram como sempre.
8. **Produção.**
   - Tirar a linha `ca` do `Caddyfile`, validar de novo (passo 5) e reiniciar o Caddy:
     `docker compose restart caddy`. O reinício limpa o certificado de staging da memória, e o
     próximo acesso pede o de produção.
   - Conferir com `curl -v https://ruraloeste.agroconia.com.br/ 2>&1 | grep -i issuer`: o emissor
     tem de ser o Let's Encrypt, sem "STAGING".
9. **Reverter**, se algo der errado: apagar o registro `A *` na Hostinger. Sem ele, nenhum
   subdomínio resolve, e o `www` segue igual. O efeito não é imediato: os resolvedores guardam a
   resposta até o TTL vencer.
   - **Cortar na hora:** comentar com `#` todas as linhas do bloco `https://` do Caddyfile e
     aplicar como no passo 5 (`validate` e `reload`). Sem o bloco, o Caddy não atende os
     subdomínios.
   - **Voltar o Caddyfile ao de antes do passo 4** (com o bloco, sem a CA de staging):

     ```bash
     cp Caddyfile.anterior Caddyfile && docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
     ```

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

Desde 2026-11-10 há também o uso da API da Anthropic pela Ceres, cobrado por token na conta do
Claude Console (Haiku 5.5, cerca de 6.000 tokens de entrada por pergunta de dados). O gasto
aparece no painel de uso do Console e não foi estimado aqui.

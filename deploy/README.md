# Deploy — VPS Oracle Cloud (Always Free)

Ambiente de demonstração do AgroContábil: backend, frontend, Postgres + pgvector, Ollama (embedding e modelo local da Ceres; desde 2026-11-10 o chat vai pela API da Anthropic),
ClamAV e o proxy `agroconia-caddy` numa VM só. A VM é compartilhada: o HTTPS e as portas são da
**borda** (repositório `GWNexT-Technologies/infra-borda`), em que o AgroConIA é um inquilino.

| Item | Valor |
|------|-------|
| VM | `agrocontabil`, Oracle Cloud, Brazil Southeast (Vinhedo), VM.Standard.A1.Flex (Ampere **arm64**), 8 OCPU / 47 GiB de RAM, disco de 96 GB (conferido em 2026-11-06), Ubuntu 24.04 |
| Endereço | `https://www.agroconia.com.br` (`SITE_HOST`). `agroconia.com.br` e o antigo `agrocontabil.<ip-com-hífens>.sslip.io` redirecionam para ele ([`borda/agroconia.caddy`](borda/agroconia.caddy)) |
| DNS | Hostinger (`dns-parking.com`): registro `A` de `@` e de `www` para o IP da VPS, sem `AAAA` (a VM não tem IPv6). O `A *` serve os [endereços dos escritórios](#endereço-próprio-do-escritório-464) |
| Acesso SSH | `ssh -i ~/.ssh/agrocontabil_lightsail ubuntu@<ip>` (a chave privada fica só no PC de quem fez o deploy) |
| Pasta na VM | `/opt/agrocontabil` — `compose.yaml`, `caddy/`, `.env` (600), `certs/nfe-teste.p12`, `jornada/` (quadros da landing, seção [Jornada da landing](#jornada-da-landing-frontend174)) |
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
- Nenhum contêiner do AgroConIA publica porta: 80, 443 e 443/udp são do Caddy da borda. Backend,
  Postgres, Ollama e ClamAV ficam na rede interna do compose; o Actuator não é exposto (o proxy só
  repassa `/api/*`).

## Borda e proxy

> **A migração é feita uma vez, pelo [`migracao-borda.md`](migracao-borda.md).** Até essa janela, a
> VPS segue com o serviço `caddy` antigo, e os comandos com `agroconia-caddy` ainda não valem.

```
Internet ─► borda (Caddy: 80/443, TLS, HTTP/3, certificados) ─► rede borda ─► agroconia-caddy:80 ─► backend:8080 | frontend:80 | jornada/
```

| O quê | De quem | Arquivo |
|-------|---------|---------|
| Portas, certificados, conta ACME (volume `agrocontabil_caddy_data`), catch-all neutro | borda | `infra-borda` |
| O `ask` do certificado sob demanda (opção global, um por instância; exceção registrada) | borda | `infra-borda` |
| Domínios do AgroConIA, TLS deles, HSTS e redirecionamentos | AgroConIA, instalado na borda | [`borda/agroconia.caddy`](borda/agroconia.caddy) |
| Scripts de validar, instalar e conferir; contrato e lista de permitidos dos inquilinos | borda | `infra-borda`, `README.md` |
| `/api`, `/jornada`, `/healthz`, demais cabeçalhos, `noindex`, log de acessos, ponte do `ask` (porta 8081) | AgroConIA | [`caddy/Caddyfile`](caddy/Caddyfile) |

- **Rede `borda`:** `172.31.255.0/24`, o Caddy da borda fixo em `172.31.255.2`, inquilinos de `.128`
  a `.254`. Do AgroConIA entra **só o `agroconia-caddy`**: `backend` e `frontend` nunca, porque o
  Compose publica o nome do serviço na rede.
- **IP do visitante:** o `agroconia-caddy` confia nos `X-Forwarded-*` só de `172.31.255.2/32`; o
  backend, só de proxy interno. Nunca troque pela faixa inteira: outro inquilino forjaria o IP.
- **Contrato da borda no compose:** o `agroconia-caddy` roda com `cap_drop: [ALL]`,
  `cap_add: [NET_BIND_SERVICE]`, `no-new-privileges` e sem porta publicada, no projeto
  `agrocontabil`. O `conferir.sh` da borda recusa o que faltar.
- **Cada cabeçalho em um lugar só:** HSTS no `borda/agroconia.caddy`, o resto no `caddy/Caddyfile`.
- **`tls force_automate` e `tls { key_type p256 }` no `www` são obrigatórios** por causa do curinga
  `*.agroconia.com.br`; sem as duas, o `www` fica sem certificado (explicado no arquivo).
- **Antes de mudar um dos dois arquivos ou a imagem do Caddy:**
  `bash deploy/ensaio-borda/ensaio.sh` (dois Caddy de verdade, local, só precisa de Docker).

**Aplicar mudança no `caddy/Caddyfile`** (sem queda; arquivo inválido é recusado e o anterior segue):

```bash
cd /opt/agrocontabil && cp -p caddy/Caddyfile Caddyfile.proxy-anterior
# copiar o novo para caddy/Caddyfile
docker compose exec -w /etc/caddy agroconia-caddy caddy validate --config /etc/caddy/Caddyfile
docker compose exec -w /etc/caddy agroconia-caddy caddy reload --config /etc/caddy/Caddyfile
```

Se o `validate` ou o `reload` falhar, **restaure o arquivo na hora**: inválido no disco, o proxy não
sobe no próximo reinício.

**Aplicar mudança no `borda/agroconia.caddy`:** copie o arquivo para `/opt/agrocontabil/borda/` e
use o script da borda, que valida, instala em `/opt/borda/sites/`, recarrega sem queda e confere.
Nunca copie direto para a pasta: um arquivo inválido derruba a borda inteira, com os outros
sistemas, no próximo reinício. Rode dentro de um `tmux`.

```bash
/opt/borda/scripts/instalar-inquilino.sh /opt/agrocontabil/borda/agroconia.caddy
```

Voltar à versão anterior: `/opt/borda/scripts/instalar-inquilino.sh --desfazer agroconia`. A borda
aceita uma **lista de permitidos**: uma diretiva nova pode exigir PR no `infra-borda` antes.

**Tirar os endereços dos escritórios do ar** (o `www` segue): remova o bloco `*.agroconia.com.br`
do `borda/agroconia.caddy` e instale o arquivo como acima. Sem o bloco, o `www` pode ficar como
está. Apagar o registro `A *` na Hostinger também corta, mas só quando o TTL vencer.

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
- **Arquivos de configuração** (`compose.yaml`, `caddy/`, `borda/`, scripts) não vão pelo CD, que só troca
  imagem. `compose.yaml` e scripts: copie para `/opt/agrocontabil` e rode `docker compose up -d`. Os
  dois arquivos do Caddy **não** entram com o `up`: siga [Borda e proxy](#borda-e-proxy). **Até a
  [janela da migração](migracao-borda.md), não copie o `compose.yaml` novo:** ele não tem o serviço
  `caddy` que está no ar.

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
   por 30 dias, qualquer que seja a região de envio, e não assina as cláusulas-padrão da ANPD. A
   política de dados de 2026-11-17 (#498) cita a Resend como operadora e a base do art. 33 em que a
   GWNext se apoia (IX, com o art. 7º, V), **decisão da equipe sem revisão jurídica**; o mapa, a
   retenção e as alternativas estão em `docs/lgpd.md`, seção "E-mail pela Resend". A exclusão de
   dados do AgroConIA não alcança a Resend; um e-mail isolado só sai pelo suporte dela. Se o risco
   não for aceito, a saída é o Amazon SES em `sa-east-1` (sem transferência).

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
3. Só com a política de dados que cita a Resend como operadora (2026-11-17, #498) e a decisão
   sobre a transferência internacional registradas (item 6 acima): `EMAIL_MODO=SMTP` e
   `docker compose up -d backend`. O aviso "Envio
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
dados. Na medição local com o gabarito (87 × 3), p50 de 1,4 s e p95 de 4,2 s no geral, com o misto
em 4,2 s de p50. O tempo por rota na VPS (dados, lei e misto, pela API pública) foi medido em
2026-11-15, antes de a fidelidade da rota de lei ser ligada lá:
`docs/avaliacao/medicao-vps-tempo-2026-11-15.md`.

**Reverter:** comente `AI_CHAT_PROVIDER` no `.env` e rode `docker compose up -d --no-deps backend`.
O backend volta ao `qwen3:4b-instruct` do Ollama, que segue baixado. Se ele ficou mais de 24 horas
sem uso (`OLLAMA_KEEP_ALIVE`), a primeira pergunta depois da reversão demora mais: o modelo volta
a ser carregado na memória.

## Fidelidade da rota de lei (#269)

A correção da rota de lei (conferência em código mais o juiz, uma segunda chamada ao modelo) está
**desligada por padrão**, no código e no `compose.yaml`: copiar o compose não liga nada. Quem liga é o
`.env`, com a configuração escolhida pelo Murillo em 2026-11-13 (`docs/ceres-assistente.md`,
"Configuração escolhida"; booleanos em minúsculas). **Estado na VPS:** ligada desde 2026-11-16, por
decisão do Murillo, com estas cinco variáveis (conferida pela tela; o `.env` de lá é a fonte:
confira-o antes de assumir o estado). O tempo da rota de lei com ela ligada não foi medido na VPS.

```
AI_FIDELIDADE_LEI_HABILITADA=true
AI_FIDELIDADE_LEI_JUIZ_HABILITADO=true
AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO=justificativa
AI_FIDELIDADE_LEI_JUIZ_RESGATE_HABILITADO=true
AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE=true
```

O veto, a cobertura mínima (0,7; 0,3 com o juiz) e os tetos do juiz (`AI_TETO_JUIZ`,
`AI_TETO_JUIZ_JUSTIFICATIVA`) também passam pelo compose, com o padrão do código, e ficam como estão.
O provedor é o do Haiku (`AI_CHAT_PROVIDER=anthropic`, seção acima). Com o juiz ligado, cada pergunta
de lei faz uma chamada a mais ao modelo e a reserva da cota por pergunta sobe de 31.744 para 44.032
créditos com a configuração `justificativa` (40.448 com o juiz `atual`).

1. **O `compose.yaml` com as variáveis precisa estar na VPS antes**; senão o `.env` não tem efeito
   (o compose lista as variáveis do backend uma a uma). Vale a mesma cautela do passo 4 da seção
   anterior: sem `SITE_DOMINIO` e `TLS_ASK_CHAVE` no `.env`, leve ao compose em uso só as linhas
   `AI_FIDELIDADE_LEI_*`, `AI_TETO_JUIZ` e `AI_TETO_JUIZ_JUSTIFICATIVA` do `backend`. Guarde o
   anterior (`cp -p`) e valide com `docker compose -f <arquivo> config -q`.
2. Acrescente as cinco linhas ao `.env` (600).
3. Recrie **só o backend**: `docker compose up -d --no-deps backend`, e espere ficar `healthy`.
4. Confira com uma pergunta de lei digitada e a auditoria (`assistente_interacao`, coluna
   `fidelidade_lei`).

**Reverter:** comente as cinco linhas (ou ponha `AI_FIDELIDADE_LEI_HABILITADA=false`) e rode
`docker compose up -d --no-deps backend`. Não precisa de deploy de código.

## Continuidade da conversa da Ceres (#499)

Desligada por padrão (`AI_CONTINUIDADE_HABILITADA=false`). Ligada, vale para **todos os
produtores**: a Ceres entende "tem certeza?", "e algum crédito?" e "e a segunda?" pelas até três
trocas anteriores, que o Angular devolve assinadas. Nada é gravado no servidor.

**Antes de ligar na VPS** (com o provedor por API, as trocas anteriores de **qualquer conta**,
inclusive as do cadastro público, vão inteiras à Anthropic, fora do Brasil: valores, datas,
números de nota e o texto livre que o escritório escreve, como o título de um pedido e o motivo de
uma devolução; ver `docs/lgpd.md`, "Continuidade da conversa"):

1. A política de dados nova no ar (`GET /api/politica-dados`, versão 2026-11-24 ou posterior).
2. O aviso na tela da Ceres (o Angular lê `conversaVaiAoProvedor` de `GET /api/assistente/uso`).
3. O prazo de retenção no provedor escrito na política e no `docs/lgpd.md`: **feito em 2026-11-24**
   (Console da organização da equipe: retenção ativada, 30 dias; retenção zero não está ativa).
   Falta só conferir que a `ANTHROPIC_API_KEY` da VPS é dessa organização (ressalva em
   `docs/lgpd.md`).
4. A medição da #499 e o OK do Murillo (o código está na `main` com a flag desligada, #505).
5. A base legal da transferência internacional (LGPD, art. 33) e o contrato, da #498, resolvidos,
   como exige o `docs/lgpd.md`, "Continuidade da conversa".
6. **A continuidade junto com a fidelidade da rota de lei** (ligada na VPS desde 2026-11-16): a
   medição da #499 roda com a fidelidade desligada, e a combinação das duas flags não foi medida.
   Com as duas ligadas, a frase do "corrijo" da reconferência de lei é texto do modelo que sai sem
   passar pelo juiz da fidelidade (comentário de 2026-11-13 na #499). Antes de ligar, o Murillo
   decide entre (a) passar a frase corrigida pela conferência de fidelidade (camada 1 e juiz),
   (b) aceitar a concordância da segunda amostra como conferência suficiente ou (c) com a
   fidelidade ligada, a reconferência só confirmar ou apontar o apoio, sem corrigir por texto do
   modelo; e a combinação é medida. A (b) é o que o código faz hoje; a (a) e a (c) não estão
   implementadas.

A lista completa dos pré-requisitos e a decisão de ligar para a banca são controladas pela #584.

**Julgamento de produto do "tem certeza?" (`AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO`, #591):**
**ligado por padrão** (decisão do Murillo, 2026-11-19: `true` no `application.yml` e no
`compose.yaml`), mas só tem efeito com a continuidade ligada, que segue **desligada**: com
`AI_CONTINUIDADE_HABILITADA=false` nenhuma chamada de julgamento acontece e o comportamento é o da
`main`. No "tem certeza?" de dados, o "corrijo" só sai com prova de que a consulta
anterior estava errada. Produto errado ("diesel" para uma pergunta de herbicida) é a prova mais
plausível, e a tabela de produto, em código, nem sempre a dá: produto fora da tabela de redução
(combustível) ou só como item de anexo (insumo). Ligada a chave, **nesse caso que a tabela não
decide**, uma chamada curta ao modelo julga se as palavras da pergunta nomeiam outro produto que o
consultado. Só a resposta `OUTRO` vale como prova; `MESMO`, `INCERTO`, formato fora, corte pelo
teto, modelo fora ou falta de reserva fecham sem prova, e a consulta anterior é reexecutada sem
modelo.

- **O que vai ao provedor** (a Anthropic, fora do Brasil, como o resto da continuidade): até 8
  palavras de conteúdo da pergunta, cada uma pelo `DataSanitizer`, e o produto consultado. Nunca a
  pergunta inteira, a resposta anterior, valores, o produtor ou o histórico. A chamada não abre
  transação e passa pela reserva da cota.
- **Custo medido** (remedição de 19/11, Haiku 5.5, 5 confirmações): cerca de 368 tokens de entrada
  e 7 de saída por chamada, perto de 0,9 s. Roda só em confirmação de dados com produto ausente da
  pergunta e tabela inconclusiva (5 vezes em toda a rodada); as demais confirmações não gastam
  nada a mais. A reserva da cota para esse caminho é de 40.064 créditos com os padrões
  (`AI_TETO_JULGAMENTO_PRODUTO`, 32 tokens de saída), abaixo do caminho com reescrita; o gasto real
  é o do que rodou.
- **Limites:** só nos pares de dados (no MISTO a dúvida reexecuta); sem a chave, produto fora da
  tabela reexecuta a consulta anterior, sem "corrijo" e sem troca.
- **Desligar:** `AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO=false` no `.env` e
  `docker compose up -d --no-deps backend`; o `deploy/compose.yaml` repassa a variável e o teto
  (`AI_TETO_JULGAMENTO_PRODUTO`). Sem a chave, produto fora da tabela reexecuta a consulta anterior,
  sem "corrijo" e sem troca. Ligar a continuidade segue decisão à parte do Murillo.

**Ligar:** com o lock do deploy (`cd /opt/agrocontabil && flock -w 900 .deploy.lock bash`),
acrescente `AI_CONTINUIDADE_HABILITADA=true` ao `.env` e confira que o compose em uso repassa a
variável ao backend (`AI_CONTINUIDADE_HABILITADA`, `AI_TETO_REESCRITA` e `AI_TETO_RECONFERENCIA`,
como no `deploy/compose.yaml`; o compose antigo não as repassa, e sem isso a variável do `.env`
não chega ao container). Depois, `docker compose up -d --no-deps backend` e espere `healthy`. O log
da subida mostra um `WARN` "continuidade da conversa LIGADA com o modelo por API".

**Desligar:** comente a linha no `.env` e rode o mesmo `up`. As conversas abertas continuam
funcionando (o histórico passa a ser ignorado); o limite de corpo de 80 KB vale nos dois estados.

**Cota da demonstração:** com a flag ligada, a conta "cota no fim" fica com o teto da pergunta com
reescrita (44 créditos) mais um; o job da segunda-feira e a subida ajustam a semana.

## Acessos da página inicial (#484)

O QR do cartão de visita leva a `https://www.agroconia.com.br/?utm_source=cartao`. Para contar as
escaneadas, o `agroconia-caddy` registra os acessos à página inicial do `www`, num arquivo do volume
`acessos`, que sobrevive aos deploys. Os registros de antes da migração para a borda ficam em
`/data/acessos` do volume `agrocontabil_caddy_data`, que passou a ser da borda (o roteiro da migração
manda contar e apagar). Conta acessos, não pessoas: a mesma pessoa que escaneia
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
docker compose exec -T agroconia-caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep -c 'utm_source=cartao'"
```

Por dia, em Brasília:

```bash
docker compose exec -T agroconia-caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep 'utm_source=cartao' | sed 's/.*\"ts\":\([0-9]*\).*/\1/' | TZ=UTC+3 awk '{ print strftime(\"%Y-%m-%d\", \$1) }' | sort | uniq -c"
```

Trocar `utm_source=cartao` por outra origem conta outra campanha. Sem filtro, o primeiro comando
conta os acessos à página inicial.

## Jornada da landing (frontend#174)

A landing tem uma seção 3D: uma sequência de imagens que anda com a rolagem. Os quadros **não vão
na imagem do frontend** (seriam 81 MB a mais em cada imagem; decisão de 2026-11-09). Ficam na VPS,
em `/opt/agrocontabil/jornada/<versão>/`, e o `agroconia-caddy` os serve em `/jornada/<versão>/`, no mesmo host
do site. O player lê `/jornada/v0.1/manifesto.json` e tira dele os caminhos dos quadros.

- **Pacote:** o zip da Release do repositório privado `luizGervazio/Animation-Agroconia`
  (`agroconia-jornada-v0.1.zip`: 75 MB, 2.442 arquivos, em AVIF e em WebP, a reserva para navegador
  sem AVIF). No ar desde 2026-11-12: a `v0.1`.
- **No proxy:** o `handle_path /jornada/*` do snippet `(app)` serve a pasta `/srv/jornada`, que o
  `compose.yaml` monta de `./jornada`, só para leitura. Vale também nos endereços dos escritórios
  (o mesmo snippet). O `nginx.conf` do frontend não entra nisso: o proxy responde antes.
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

A montagem da pasta só entra quando o contêiner do `agroconia-caddy` é recriado; o `reload` não
basta.

```bash
cd /opt/agrocontabil
mkdir -p jornada                                  # antes do up: se faltar, o Docker cria a pasta vazia e do root
docker compose run --rm --no-deps agroconia-caddy caddy validate --config /etc/caddy/Caddyfile   # Valid configuration; senao, pare
docker compose up -d --no-deps agroconia-caddy    # recria só o proxy; o site cai por alguns segundos
curl -s https://www.agroconia.com.br/healthz      # UP
```

- **`--no-deps`:** evita recriar o backend e o frontend quando o `compose.yaml` copiado também
  mudou para eles. Essas mudanças entram no próximo deploy de cada um.

## Endereço próprio do escritório (#464)

Cada escritório pode ter o próprio endereço, `{slug}.agroconia.com.br`. O produtor continua em
`www`. O registro `A *` já existe na Hostinger (conferido em 25/11/2026), e o `.env` tem
`SITE_DOMINIO` e `TLS_ASK_CHAVE`.

### Como funciona

- **Certificados sob demanda:** a borda pede um certificado por slug, no primeiro acesso, via
  On-Demand TLS. Antes de pedir, ela pergunta: `GET
  http://agroconia-caddy:8081/interno/tls/permitido?chave=...&domain=...`. O `agroconia-caddy`
  repassa ao backend (que não entra na rede `borda`). A porta 8081 não recebe tráfego de visitante,
  e a rota é fora de `/api`: pela internet ela não chega ao backend.
- **Quem o backend autoriza:** só responde 200 com a `TLS_ASK_CHAVE` certa e um slug servido
  (ativo, ou em carência com redirecionamento) de escritório com o **CNPJ verificado** (#520, seção
  abaixo). Slug inexistente, reservado (inclusive o `www`) ou de escritório não verificado nunca
  ganha certificado, e o handshake falha.
- **Curinga no arquivo do inquilino:** o `*.agroconia.com.br` com `tls { on_demand }` está no
  `borda/agroconia.caddy`, e o catch-all da borda é neutro. Com o curinga, o `www` precisa de `tls
  force_automate` **e** `tls { key_type p256 }`: sem a primeira, o Caddy 2.10 não emite o
  certificado dele; só com ela, qualquer outro bloco no Caddyfile da borda o joga na política sob
  demanda, e o `ask` o recusa (reproduzido em 25/11/2026; o ensaio confere).
- **Cabeçalhos:** o `agroconia-caddy` serve o mesmo snippet `(app)` do `www` (`nosniff`,
  `Referrer-Policy: no-referrer`, sem `Server`), e o HSTS vem da borda. Sem o `no-referrer`, o slug
  iria no `Referer` para as fontes externas. O endereço do escritório leva `X-Robots-Tag: noindex`,
  porque não entra em buscador (frontend#175).
- **Exposição:** cada slug emitido vai para os registros públicos de Certificate Transparency
  (crt.sh), de forma permanente. Por isso o `PUT /api/escritorio/endereco` exige a ciência do
  administrador e o CNPJ do escritório.
- **Limites do Let's Encrypt:** 50 certificados novos por domínio registrado a cada 7 dias
  (renovações não contam) e 5 validações com falha por nome por hora. Mudança no certificado sob
  demanda se testa antes na CA de staging.

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
docker compose exec backend sh -c 'wget -qSO /dev/null "http://agroconia-caddy:8081/interno/tls/permitido?chave=$APP_ESCRITORIO_TLS_ASK_CHAVE&domain=ruraloeste.agroconia.com.br" 2>&1 | head -1'   # 200
```

O seed roda logo depois de o backend aceitar conexões: nos primeiros segundos da subida, o ask do
`ruraloeste` ainda responde 404. Com o certificado já emitido e guardado pela borda, nada muda para
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
  hora. O certificado já emitido continua no cache da borda até vencer, mas o login pelo
  subdomínio e a página pública param. **No escritório da demonstração a revogação não dura:** a
  próxima subida do backend o verifica de novo pelo seed. Para tirá-lo do ar, remova o endereço
  (`DELETE /api/escritorio/endereco`, com a administradora) ou desligue o seed.
- Local: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,verificacao-cnpj
  -Dspring-boot.run.arguments="--verificacao.escritorio=<id> --verificacao.cnpj=<cnpj>"`.

### Rotação da chave do ask

A chave existe em **dois** `.env`, com o mesmo valor: `TLS_ASK_CHAVE` em `/opt/agrocontabil/.env`
(backend) e `AGROCONIA_TLS_ASK_CHAVE` em `/opt/borda/.env`. Gere com `openssl rand -hex 32`
(**hex, nunca Base64:** `+`, `/` e `=` chegam alterados na URL), troque nos dois e recrie o backend
(`docker compose up -d --no-deps backend`) e a borda (`docker compose up -d` em `/opt/borda`: o
reload não troca a chave). Rotação pela metade faz o `ask`
recusar tudo; os certificados já emitidos continuam valendo.

A chave vai na URL do ask, porque o módulo de permissão do Caddy 2.10 não manda cabeçalho. Por
isso ela fica na configuração do Caddy da borda e pode aparecer nos logs dele, e nos do
`agroconia-caddy`, quando o ask falha. Não cole esses logs em chat nem em issue sem mascarar
(`sed -E 's/chave=[^&\" ]+/chave=***/g'`). Se vazar, rotacione.

## Primeira subida

```bash
cd /opt/agrocontabil
cp .env.example .env && chmod 600 .env   # preencher; segredos:
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 32   # CRIPTO_CHAVE
openssl rand -hex 32      # TLS_ASK_CHAVE (vai na URL do ask: hex, nunca base64); e SITE_DOMINIO=agroconia.com.br
docker login ghcr.io -u <usuario-github>   # token clássico só com read:packages
docker network inspect borda >/dev/null   # antes, no infra-borda: scripts/criar-rede.sh
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

#### Reingestão por nome de seção e anotação coladas (#606)

O fatiador antigo deixava o **nome da seção seguinte** no fim do último dispositivo de cada seção e
a anotação "Produção de efeitos" (e "Vigência", "Sem eficácia") no texto. Corrigido o fatiador, a base
que já está carregada continua com o texto antigo até ser reingerida. **É passo manual, do Murillo,
e a ordem entre local, VPS e medições é combinada antes** (a base medida tem de ser a da banca; ver
a #583 e a #584). O código não muda base nenhuma sozinho.

O que acontece: a ingestão é idempotente pelo **hash de norma + dispositivo + texto**. Texto
corrigido = hash novo, e a ingestão **recusa** texto novo com a mesma `vigencia-inicio` de uma linha
aberta (`Nada foi ingerido`). Então o passo é apagar **só as linhas que mudam** e reingerir com os
**mesmos argumentos da carga** (mesma `vigencia-inicio`, é a mesma redação; só a `data-captura` é a
de hoje). Os trechos inalterados são pulados pelo hash e **ficam com o embedding que já têm**; os que
mudam (na comparação feita no PR, 1.247 de 8.304: 1.068 da LC 214, 97 da LC 227, 29 da Lei 8.212 e 53
da EC 132; a Lei 9.528 não muda) ganham texto e **embedding novos**, gerados pelo `bge-m3` no Ollama.
O índice HNSW e a coluna `conteudo_tsv_sem_acento` (gerada) se atualizam sozinhos; não há `REINDEX`
nem reinício do backend. **Duração, medida na reingestão local de 22/11/2026** (notebook do Murillo,
Ollama local com o `bge-m3` em CPU, 100% CPU pelo `ollama ps`, sem GPU no contêiner; banco local; o `DELETE` e as quatro ingestões, cada uma subindo a aplicação, com ~15 s de
subida): **847 s no total, 14 min**: LC 214 629 s (1.068 embeddings, ~0,6 s cada), LC 227 102 s (97),
Lei 8.212 53 s (29) e EC 132 62 s (53). **Na VPS não foi medida**: ela também embeda em CPU (8 OCPU),
então espere a mesma ordem de grandeza, sem garantia; o tempo é proporcional aos ~1.250
embeddings, e o log mostra um resumo por norma ao fim.
Enquanto uma norma é refeita, os trechos apagados dela ficam fora da base: faça fora de horário e
**sem medição da Ceres em curso**.

1. **Salve o texto compilado** das cinco normas como o Planalto serve (sem converter), numa pasta
   (`lcp214.htm`, `lcp227.htm`, `l8212cons.htm`, `emc132.htm`, `l9528.htm`; as URLs estão em
   `docs/ceres-assistente.md`). O `curl` puro foi recusado pelo Planalto (conexão reiniciada) em 22/11/2026; o
   `Invoke-WebRequest` com `User-Agent` de navegador funcionou.
2. **Tire o CSV da base que vai ser reingerida** (somente leitura; é o que a ferramenta compara):

   ```bash
   docker compose exec -T postgres psql -U agrocontabil -d agrocontabil -c   "COPY (SELECT norma, dispositivo, vigencia_inicio, vigencia_fim, content, hash FROM legislacao_trecho ORDER BY norma, dispositivo, vigencia_inicio) TO STDOUT WITH (FORMAT csv, HEADER)" > base.csv
   ```

   Na VPS, copie o `base.csv` para a máquina de desenvolvimento.
3. **Comparação a seco**, na máquina de desenvolvimento (não ingere, não gera embedding, não usa o
   Ollama nem o banco):

   ```bash
   ./mvnw test -Dtest=ComparacaoFatiamentoBaseManual -Dcomparacao.pasta=<pasta dos HTMLs> -Dcomparacao.base=base.csv -Dcomparacao.saida=relatorio.md -Dcomparacao.sql-saida=apagar.sql
   ```

   Leia o `relatorio.md`. **Só siga se** a contagem por norma da base e do fatiador for igual
   (5.004, 2.211, 547, 538 e 4), **Somem** e **Aparecem** forem 0 e **OUTRA** for 0; a lista "O que
   sai" tem de ter só nome de seção e as anotações "Produção de efeitos", "Vigência" e "Sem
   eficácia". Qualquer outra coisa (o Planalto pode ter mudado o texto desde a captura da base) é
   para parar e investigar: a ferramenta nem grava o `apagar.sql` nesse caso.
4. **Local:** apague e reingira (Postgres e Ollama no ar; um comando por norma, com os argumentos de
   `docs/ceres-assistente.md` e a `data-captura` de hoje):

   ```bash
   docker exec -i agrocontabil-postgres psql -U agrocontabil -d agrocontabil -v ON_ERROR_STOP=1 < apagar.sql
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="--ingestao.arquivo=<pasta>/lcp214.htm --ingestao.norma='LC 214/2025' --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm --ingestao.data-captura=<hoje> --ingestao.vigencia-inicio=2025-01-16"
   ```

   Se o banco local não tem todos os changeSets da `main` (a subida da aplicação os aplicaria),
   acrescente `--spring.liquibase.enabled=false --spring.jpa.hibernate.ddl-auto=none` aos argumentos
   (a ingestão só usa `legislacao_trecho`); `DEMONSTRACAO_HABILITADA` já vale `false` por padrão fora
   da VPS. Na reingestão de 22/11 a única outra tabela que mudou foi a `shedlock` (a hora de um job
   agendado), conferida por checksum de todas as tabelas antes e depois.

   Repita para `LC 227/2026` (`lcp227.htm`, `--ingestao.vigencia-inicio=2026-01-14`), `Lei 8.212/1991`
   (`l8212cons.htm`, `1991-07-25`) e `EC 132/2023` (`emc132.htm`, `2023-12-21`). A Lei 9.528/1997 não
   precisa. Rode antes cada uma com `--ingestao.simular=true`.
5. **VPS:** com as HTMLs em `/opt/agrocontabil/legislacao/` e o `apagar.sql` (gerado do `base.csv` da
   própria VPS) em `/opt/agrocontabil/`, e **fora de um deploy**:

   ```bash
   cd /opt/agrocontabil
   docker compose exec -T postgres psql -U agrocontabil -d agrocontabil -v ON_ERROR_STOP=1 < apagar.sql
   docker compose run --rm -v /opt/agrocontabil/legislacao:/legislacao:ro -e SPRING_PROFILES_ACTIVE=homolog,ingestao -e DEMONSTRACAO_HABILITADA=false backend   --ingestao.arquivo=/legislacao/lcp214.htm --ingestao.norma='LC 214/2025'   --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm   --ingestao.data-captura=<hoje> --ingestao.vigencia-inicio=2025-01-16
   echo $?   # 0 = ingerida; 1 = falhou, motivo no log
   ```

   O `-e DEMONSTRACAO_HABILITADA=false` importa: o compose da VPS liga o seed da banca e o profile
   `ingestao` não o desliga, então sem isso o container da ingestão rodaria o seed junto do backend
   no ar. Os outros três comandos seguem o padrão do passo 4. Se uma ingestão cair no meio, rode o **mesmo
   comando** de novo: é idempotente (pula o que já entrou) e a base não fica com vigência duplicada.
6. **Conferir** (as quatro consultas têm de dar o resultado indicado):

   ```sql
   -- contagem por norma igual à de antes: 5004, 2211, 547, 538 e 4
   SELECT norma, count(*) FROM legislacao_trecho GROUP BY norma ORDER BY norma;
   -- a consulta da issue: artigo que termina em "Do/Da/Dos/Das Título" sem pontuação final => 0 linhas
   SELECT norma, count(*) FROM legislacao_trecho
    WHERE dispositivo LIKE 'art.%' AND content ~ '\s(Do|Da|Dos|Das|DO|DA|DOS|DAS)\s[^.;:!?]{3,200}[^.;:!?\s]$'
    GROUP BY norma;
   -- anotações do Planalto no texto => 0
   SELECT count(*) FROM legislacao_trecho
    WHERE content ~ 'Produção de efeito|Sem eficácia|\mVigência\M';
   -- nenhum dispositivo com duas redações abertas (a mesma vigência) => 0 linhas
   SELECT norma, dispositivo FROM legislacao_trecho WHERE vigencia_fim = '9999-12-31'
    GROUP BY norma, dispositivo HAVING count(*) > 1;
   ```

   A segunda consulta não conta os itens de anexo (`Anexo XI, item 1`...), cujo texto legítimo é em
   maiúsculas. Depois disso, uma pergunta de lei pela tela confirma que a busca segue
   respondendo.

## CI/CD

```
PR ──► CI (testes) ──► merge na main ──► CI verde ──► docker-publish.yml ──► GHCR ──► deploy na VPS
```

- O `docker-publish.yml` de cada repo dispara por `workflow_run` quando a CI do push na `main`
  termina (backend: `CI - Backend`; frontend: `CI`) e publica `sha-xxxxxxx` + `latest`.
- **Backend: o commit publicado é a ponta verde da `main`, não o que disparou (#621).** Em 2026-11-25 dois
  merges seguidos deixaram a VPS no commit mais antigo: a CI do mais novo terminou primeiro, o publish
  dele foi cancelado pelo do mais antigo (`cancel-in-progress: true`) e o mais antigo publicou por
  cima, sem nenhuma execução vermelha. Desde então o workflow do backend tem um job só (build, push e
  deploy) e:
  - **resolve o alvo quando a execução começa:** o primeiro commit da `main` (primeiro pai, do mais
    novo para trás, até 100) com uma execução de push do `CI - Backend` verde em qualquer tentativa.
    O disparo é só um aviso de que algo mudou; até a CI que falhou avisa e reimplanta a ponta verde
    (com a mesma tag, o `deploy.sh` não recria o container, a menos que o `.env` ou o compose tenham
    mudado à mão sem recriar);
  - **uma execução por vez** (grupo `deploy-vps-backend`, `cancel-in-progress: false`): a que roda
    nunca é cancelada; a pendente pode ser cancelada pela que chega depois, que começa mais tarde e
    publica o que ela publicaria ou algo mais novo (por isso uma execução **cancelada** na lista é
    esperada em merges seguidos);
  - **não refaz a imagem** se a tag do alvo já está no GHCR (só move o `latest`): um build novo da
    mesma tag mudaria o digest e recriaria o container sem motivo;
  - **confere no fim** pelo relatório do `deploy.sh` (`<antes> -> <tag>` e `ok: backend em <tag>`,
    escrito só com a tag no `.env` e o container `healthy`); sem ele, a execução falha com a mensagem.
    O resumo da execução diz o disparo, a ponta da `main`, o alvo, a tag publicada e a de antes.

  Consequências: a VPS termina no commit verde mais novo qualquer que seja a ordem de término das CIs;
  um commit antigo nunca publica por cima de um mais novo (as execuções são em série e o alvo só
  avança). Lista da CI lida sem filtro de estado: a reexecução em andamento de um commit verde não o
  tira do conjunto. **Reexecutar** uma execução antiga, ou o *Run workflow* manual, reimplanta a ponta verde,
  nunca o commit daquela execução: uma versão específica só pelo `deploy.sh` na VM (abaixo). Não
  reexecute execuções anteriores ao PR do #621: elas usam o workflow antigo e publicariam o commit
  delas. O frontend ainda tem o desenho antigo (publish com `cancel-in-progress: true`) e o mesmo
  defeito: depois de merges seguidos lá, confira o `FRONTEND_TAG` da VPS.
- O deploy entra por SSH com uma chave só da CI. Na VPS ela tem
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
- Reimplantar sem novo commit: *Actions → Docker - … (GHCR + deploy) → Run workflow* (no backend,
  reimplanta a ponta verde da `main`; escolha a `main`: um ref antigo roda a versão antiga do workflow).

Deploy manual de uma versão (na VM): `./deploy.sh backend sha-xxxxxxx`.

## Custos

A máquina conferida em 2026-11-06 (8 OCPU, 47 GiB) está acima do limite do Always Free para a A1
(4 OCPU/24 GB). A conta é Pay As You Go: aumentar OCPU/RAM além desse limite ou criar outra VM A1
é cobrado. O que isso significa em custo para esta VM o Murillo ainda vai confirmar. O restante
(até 200 GB de disco, IP público efêmero) segue o Always Free.

Desde 2026-11-10 há também o uso da API da Anthropic pela Ceres, cobrado por token na conta do
Claude Console (Haiku 5.5, cerca de 6.000 tokens de entrada por pergunta de dados). O gasto
aparece no painel de uso do Console e não foi estimado aqui.

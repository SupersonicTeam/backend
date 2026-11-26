# Janela: o Caddy sai do AgroConIA e entra a borda neutra

Roteiro de **uma vez só**, para o Murillo rodar na VPS. Inventário, decisões e riscos:
`GWNexT-Technologies/infra-borda`, issue 1. Depois da janela, o dia a dia está no
[`README.md`](README.md), seção "Borda e proxy".

**Até a janela, a VPS segue com o serviço `caddy` antigo**: os comandos do `README.md` com
`agroconia-caddy` só valem depois dela.

O roteiro junta os dois lados numa ordem só. Os passos da borda são do **runbook do `README.md` do
`infra-borda`** (conferido no `afa9619`, o merge do PR 2). Os comandos da janela estão repetidos
aqui para haver uma lista só; **se divergirem do que está em `/opt/borda`, vale o de lá: pare e
compare**.

## Pré-requisitos

1. **A partir de 27/11/2026, com o OK explícito do Murillo.** Nada abaixo, nem o merge do PR, antes
   disso: todo merge na `main` recria o backend na VPS. A regra escrita do repositório (skill
   `project-conventions`, "Git / PRs") diz que **de 26/11 a 03/12 só entra correção urgente com OK
   do Murillo**: fazer a janela antes de 03/12 é abrir uma exceção a ela. Em 25/11 o Murillo decidiu
   que a janela **pode ser antes de 03/12**. Isso não marca a data nem é o OK do merge: os dois são
   dados na hora, com os demais pré-requisitos prontos.
2. O `infra-borda` na `main`, no commit que vai para a VPS. Se for mais novo que o `afa9619`, releia
   o runbook dele e compare o `docker-compose.yml`: a imagem tem de seguir com o **mesmo digest** do
   [`compose.yaml`](compose.yaml) daqui.
3. `bash deploy/ensaio-borda/ensaio.sh` com `ENSAIO OK` no commit que vai para a VPS.

## 1. Conferências (só leitura, dias antes)

Primeiro o passo 1 do runbook do `infra-borda` ("Conferências de leitura"), com a lista de "Pare
se" dele. Depois, o que é do AgroConIA:

```bash
cd /opt/agrocontabil
sha256sum Caddyfile compose.yaml
ls -1 *.sh *.novo 2>/dev/null
docker compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
```

**Pare se:** o `SITE_REDIRECIONA` tiver um nome que não está no
[`borda/agroconia.caddy`](borda/agroconia.caddy), ou o `SITE_HOST`/`SITE_DOMINIO` não forem
`www.agroconia.com.br`/`agroconia.com.br` (os três saem no passo 1 da borda; nunca rode `printenv`
sem os nomes).

Se o `compose.yaml` da VPS não for o da `main` anterior a este PR, o novo leva junto o que faltava
ao backend (vale no próximo `up` dele). Compare o `sha256sum` com
`git show <commit anterior ao merge>:deploy/compose.yaml | sha256sum`.

## 2. Preparação (sem efeito no ar)

**2.1. Arquivos do AgroConIA.** Do PC, na raiz deste repositório, no commit mergeado:

```bash
scp -i ~/.ssh/agrocontabil_lightsail deploy/compose.yaml ubuntu@<ip>:/opt/agrocontabil/compose.yaml.novo
scp -i ~/.ssh/agrocontabil_lightsail -r deploy/caddy deploy/borda ubuntu@<ip>:/opt/agrocontabil/
```

**2.2. Borda:** passos 2 a 5 do runbook do `infra-borda`, nesta ordem: clonar em `/opt/borda` (dono
`ubuntu`), copiar a chave do `ask` para o `.env` dela sem imprimir, `scripts/criar-rede.sh`,
`docker compose pull && docker compose create`, e validar
`/opt/agrocontabil/borda/agroconia.caddy`. No passo 5, o `www.agroconia.com.br` tem de aparecer
numa política de TLS só dele; **se não aparecer, pare**.

**2.3. Proxy** (a rede `borda` já existe, do passo 2.2):

```bash
cd /opt/agrocontabil && docker compose -f compose.yaml.novo config -q && docker compose -f compose.yaml.novo pull agroconia-caddy && docker compose -f compose.yaml.novo run --rm --no-deps agroconia-caddy caddy validate --config /etc/caddy/Caddyfile
```

Tem de dizer `Valid configuration`.

## 3. Janela

Sem merges. **Dentro de um `tmux`** (uma queda do SSH no meio deixa a troca pela metade) e **num
mesmo shell, com o lock do deploy**:

```bash
tmux new -s borda
```

```bash
cd /opt/agrocontabil && flock -w 900 .deploy.lock bash
```

As funções abaixo valem para a janela inteira e para a volta atrás:

```bash
mascara() { sed 's/chave=[^"&[:space:]]*/chave=<oculta>/g'; }   # toda saida do Caddy passa por aqui
cert() { echo | openssl s_client -connect 127.0.0.1:443 -servername www.agroconia.com.br 2>/dev/null | openssl x509 -noout -fingerprint -sha256 -issuer; }
# curl na VPS falando com ela mesma, sem depender de voltar pelo IP publico
c() { curl --max-time 20 --resolve www.agroconia.com.br:443:127.0.0.1 --resolve agroconia.com.br:443:127.0.0.1 --resolve ruraloeste.agroconia.com.br:443:127.0.0.1 --resolve naoexiste.agroconia.com.br:443:127.0.0.1 --resolve agrocontabil.167-126-45-181.sslip.io:443:127.0.0.1 "$@"; }
```

### 3.1 Backup e retrato do estado atual

```bash
ts=$(date +%Y%m%d-%H%M%S); mkdir -p backups
cp -p compose.yaml "compose.yaml.bak-$ts"; cp -p Caddyfile "Caddyfile.bak-$ts"
cp -p compose.yaml compose.yaml.antes-da-borda   # nome fixo: e este que a volta atras restaura
docker run --rm -v agrocontabil_caddy_data:/d:ro -v "$PWD/backups:/b" caddy:2.10-alpine sh -c "umask 077; tar czf /b/caddy_data-$ts.tgz -C /d ."   # tem chaves privadas
cert | tee backups/cert-antes.txt   # tem de mostrar emissor e impressao; vazio = pare
c -sSI https://www.agroconia.com.br/ | grep -iE '^(HTTP|strict|x-content|referrer|alt-svc|server)' | tee backups/headers-antes.txt
```

### 3.2 Subir só o proxy, ao lado do Caddy antigo (nada muda no ar)

```bash
docker compose -f compose.yaml.novo up -d --no-deps agroconia-caddy
docker compose -f compose.yaml.novo ps agroconia-caddy   # esperar "healthy"; o aviso de órfão (caddy) é esperado
```

**Não use `--remove-orphans` em nenhum passo:** ele apagaria o Caddy antigo, que é a volta atrás.

Testar o proxy pela rede `borda` (os contêineres de teste somem com o `--rm`):

```bash
t() { docker run --rm --network borda caddy:2.10-alpine wget -qO- --header 'Host: www.agroconia.com.br' "http://agroconia-caddy$1"; echo; }
t /healthz            # UP
t /api/v1/health      # resposta do backend
t / | head -c 200     # o index.html
t /jornada/v0.1/manifesto.json | head -c 100
# O ask, pela rede interna (a porta 8081 recusa os outros enderecos da rede borda)
a() { docker compose -f compose.yaml.novo exec -T backend sh -c "wget -qSO /dev/null \"http://agroconia-caddy:8081/interno/tls/permitido?chave=\$APP_ESCRITORIO_TLS_ASK_CHAVE&domain=$1\" 2>&1 | head -1"; }
a ruraloeste.agroconia.com.br   # 200
a naoexiste.agroconia.com.br    # 404
```

**Qualquer falha:** `docker compose -f compose.yaml.novo rm -sf agroconia-caddy` e fim da janela;
o site não foi tocado.

### 3.3 Troca

**Antes de parar o Caddy antigo**, pôr o arquivo do AgroConIA na borda (ela ainda está parada,
então sem reload; entra a cópia validada):

```bash
(cd /opt/borda && BORDA_VALIDADO=/tmp/agroconia.caddy.validado scripts/validar-inquilino.sh /opt/agrocontabil/borda/agroconia.caddy \
  && install -m 644 /tmp/agroconia.caddy.validado sites/.agroconia.caddy.novo \
  && mv sites/.agroconia.caddy.novo sites/agroconia.caddy && rm -f /tmp/agroconia.caddy.validado)
cmp /opt/agrocontabil/borda/agroconia.caddy /opt/borda/sites/agroconia.caddy && echo "arquivo na borda"
```

**Sem o "arquivo na borda", não siga.** Parar o antigo, sem `rm` (começa a indisponibilidade, de
segundos; o `update` garante que ele não volta num reboot):

```bash
docker update --restart=no agrocontabil-caddy-1 && docker stop agrocontabil-caddy-1
```

Subir a borda com o mesmo volume:

```bash
(cd /opt/borda && docker compose up -d) && docker logs --tail 20 borda-caddy 2>&1 | mascara
```

### 3.4 Conferências

```bash
(cd /opt/borda && scripts/conferir.sh)                            # tudo "ok"; o primeiro certificado pode demorar alguns segundos
[ "$(sudo sed -n 's/^TLS_ASK_CHAVE=//p' /opt/agrocontabil/.env | sha256sum)" = "$(docker exec borda-caddy printenv AGROCONIA_TLS_ASK_CHAVE | sha256sum)" ] && echo "chave igual na borda"
c -sS https://www.agroconia.com.br/healthz; echo                  # UP
cert | diff - backups/cert-antes.txt && echo "mesmo certificado"
c -sSI https://www.agroconia.com.br/ | grep -iE '^(HTTP|strict|x-content|referrer|alt-svc|server)' | diff - backups/headers-antes.txt && echo "mesmos cabeçalhos"
c -sSI 'https://agroconia.com.br/x?a=1' | grep -iE '^(HTTP|location)'                       # 301 para o www
c -sSI https://agrocontabil.167-126-45-181.sslip.io/ | grep -iE '^(HTTP|location)'          # 301 para o www
c -sSI https://ruraloeste.agroconia.com.br/ | grep -iE '^(HTTP|x-robots|referrer|strict)'   # 200, noindex, um de cada
c -s https://naoexiste.agroconia.com.br/ -o /dev/null; echo "saida $? (tem de ser diferente de 0)"
```

O `conferir.sh` avisa que o bloco de redirecionamento não tem HSTS: é o comportamento de antes. A
ordem dos cabeçalhos pode mudar no `diff`: o que importa são os mesmos nomes e valores, **uma vez
cada**.

**O `ask` com a chave de verdade:** um escritório que já tem certificado no volume nem pergunta.
Se houver um escritório verificado **sem certificado ainda**, abra o endereço dele; senão, o `a
ruraloeste` do 3.2 e o "chave igual na borda" são a prova que há.

E de fora da VPS: `curl -sS https://www.agroconia.com.br/healthz` = `UP` no PC; no navegador, login
no `www` e em `ruraloeste.agroconia.com.br`; uma pergunta **digitada** à Ceres (a resposta chega,
sem erro no stream); um anexo perto de 5 MB numa pendência; a landing.

**IP real (o teste que não pode faltar).** De um PC, 11 logins seguidos com um e-mail que não existe:

```bash
for i in $(seq 1 11); do curl -s -o /dev/null -w '%{http_code} ' -H 'Content-Type: application/json' -d '{"email":"teste-janela@example.com","senha":"nao-existe-123456"}' https://www.agroconia.com.br/api/auth/login; done; echo
```

Os últimos dão `429`. **Na mesma hora, de outra rede** (celular fora do Wi-Fi), um login no site
tem de funcionar. Se o celular também receber "muitas tentativas", o IP real se perdeu: **volte
atrás**.

Log de acessos sem IP: abrir `https://www.agroconia.com.br/?utm_source=janela` e rodar
`docker compose -f compose.yaml.novo exec agroconia-caddy tail -1 /data/acessos/www.log`.

### 3.5 Concluir

Só com tudo certo:

```bash
mv compose.yaml.novo compose.yaml
docker compose ps
exit      # solta o lock
```

## Volta atrás

Vale enquanto o contêiner `agrocontabil-caddy-1` existir. **Nunca `down -v` nem `volume prune`.**
Se o shell da janela já fechou, retome o lock antes (`flock -w 900 .deploy.lock bash`).

```bash
(cd /opt/borda && docker compose stop)
docker update --restart=unless-stopped agrocontabil-caddy-1 && docker start agrocontabil-caddy-1
```

```bash
cd /opt/agrocontabil
test -f compose.yaml.novo || cp -p compose.yaml compose.yaml.novo
cp -p compose.yaml.antes-da-borda compose.yaml
docker compose -f compose.yaml.novo rm -sf agroconia-caddy
curl -sS https://www.agroconia.com.br/healthz; echo    # UP
```

O contêiner antigo guarda a configuração com que foi criado e o `Caddyfile` da raiz da pasta, que
este roteiro não altera.

## Depois (um ou dois dias estável)

```bash
cd /opt/agrocontabil
docker rm agrocontabil-caddy-1
rm -f atualizar-caddy.sh trocar-compose.sh Caddyfile
ls -1 .env.bak-*      # cópias antigas do .env, com segredos: apagar as que não precisar mais
```

- O volume `agrocontabil_caddy_data` **fica**: é da borda agora. `agrocontabil_caddy_config` pode
  ficar.
- **Logs de acesso antigos: conte e apague.** Eles ficam em `/data/acessos` daquele volume, e quem
  aplica a retenção de 30 dias é quem escreve o arquivo: sem o Caddy antigo, ninguém os apagaria.
  Anote o total e remova **só os arquivos antigos** (o resto de `/data` tem os certificados, e as
  subpastas de `/data/acessos` podem ser de outros sistemas):

  ```bash
  cd /opt/borda
  docker compose exec -T borda-caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep -c 'utm_source=cartao'"
  docker compose exec -T borda-caddy sh -c "rm -f /data/acessos/www.log /data/acessos/www-*.log.gz"
  ```

- `SITE_REDIRECIONA` no `.env` do AgroConIA fica sem uso.

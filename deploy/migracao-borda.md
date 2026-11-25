# Janela: o Caddy sai do AgroConIA e entra a borda neutra

Roteiro de **uma vez só**, para o Murillo rodar na VPS. Inventário, decisões e riscos:
`GWNexT-Technologies/infra-borda`, issue 1. Depois da janela, o dia a dia está no
[`README.md`](README.md), seção "Borda e proxy".

**Até a janela, a VPS segue com o serviço `caddy` antigo**: os comandos do `README.md` com
`agroconia-caddy` só valem depois dela.

## Pré-requisitos

1. **A partir de 27/11/2026, com o OK explícito do Murillo.** Nada abaixo, nem o merge do PR, antes
   disso: todo merge na `main` recria o backend na VPS. A regra escrita do repositório (skill
   `project-conventions`, "Git / PRs") diz que **de 26/11 a 03/12 só entra correção urgente com OK
   do Murillo**: fazer a janela antes de 03/12 é abrir uma exceção a ela. Em 25/11 o Murillo decidiu
   que a janela **pode ser antes de 03/12**. Isso não marca a data nem é o OK do merge: os dois são
   dados na hora, com os demais pré-requisitos prontos.
2. O repositório `infra-borda` pronto e revisado, com:
   - `docker-compose.yml`: Caddy `2.10-alpine` no **mesmo digest** do `compose.yaml` daqui, portas
     `80`, `443` e `443/udp`, rede `borda` externa com `ipv4_address: 172.31.255.2`, e o volume
     `agrocontabil_caddy_data` como `external` em `/data`; e a variável `AGROCONIA_TLS_ASK_CHAVE`
     **repassada ao contêiner** (sem isso o `ask` vai com a chave vazia);
   - `Caddyfile`: o `ask` do AgroConIA na parte global
     (`ask http://agroconia-caddy:8081/interno/tls/permitido?chave={$AGROCONIA_TLS_ASK_CHAVE}`),
     catch-all neutro `https:// { abort }` e `import sites/*.caddy`. **Não definir `acme_ca`**
     (os certificados guardados são reaproveitados pelo diretório da CA);
   - o script que valida e instala o arquivo de um inquilino.

   O modelo mínimo do que o AgroConIA precisa é o [`ensaio-borda/borda.Caddyfile`](ensaio-borda/borda.Caddyfile).
3. `bash deploy/ensaio-borda/ensaio.sh` com `ENSAIO OK` no commit que vai para a VPS.

Nos comandos, `/opt/borda/docker-compose.yml` e o serviço `caddy` dentro dele são o que se espera do
`infra-borda`: **confira os nomes lá antes**.

## 1. Conferências (só leitura, dias antes)

```bash
cd /opt/agrocontabil
sha256sum Caddyfile compose.yaml
ls -1 *.sh *.novo 2>/dev/null
docker compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
C=$(docker compose ps -q caddy)
docker inspect "$C" --format '{{range .Mounts}}{{.Type}} {{.Name}} {{.Source}} -> {{.Destination}}{{println}}{{end}}'
docker exec "$C" printenv SITE_HOST SITE_REDIRECIONA SITE_DOMINIO   # só domínios; nunca "printenv" sem nomes
docker exec "$C" caddy version
docker network inspect borda >/dev/null 2>&1 && echo "a rede borda JÁ existe: conferir a faixa" || echo "sem rede borda"
```

**Pare se:** o volume de `/data` não se chamar `agrocontabil_caddy_data`; o `SITE_REDIRECIONA`
tiver um nome que não está no [`borda/agroconia.caddy`](borda/agroconia.caddy); o
`SITE_HOST`/`SITE_DOMINIO` não forem `www.agroconia.com.br`/`agroconia.com.br`; ou já houver uma
rede `borda` com outra faixa.

Se o `compose.yaml` da VPS não for o da `main` anterior a este PR, o novo leva junto o que faltava
ao backend (vale no próximo `up` dele). Compare antes: `docker compose config --hash backend` no
arquivo em uso e no novo.

## 2. Preparação (sem efeito no ar)

Do PC, na raiz deste repositório, no commit mergeado:

```bash
scp -i ~/.ssh/agrocontabil_lightsail deploy/compose.yaml ubuntu@<ip>:/opt/agrocontabil/compose.yaml.novo
scp -i ~/.ssh/agrocontabil_lightsail -r deploy/caddy deploy/borda ubuntu@<ip>:/opt/agrocontabil/
```

Na VPS (com o `infra-borda` já em `/opt/borda`):

```bash
docker network create --driver bridge --subnet 172.31.255.0/24 --gateway 172.31.255.1 --ip-range 172.31.255.128/25 borda
```

A chave do `ask` vai de um `.env` para o outro **sem aparecer na tela**. O comando não repete a
variável se ela já existir, e garante a quebra de linha antes de acrescentar:

```bash
( umask 077; f=/opt/borda/.env; touch "$f"
  if ! grep -q '^AGROCONIA_TLS_ASK_CHAVE=' "$f"; then
    [ -z "$(tail -c1 "$f")" ] || echo >> "$f"
    sed -n 's/^TLS_ASK_CHAVE=/AGROCONIA_TLS_ASK_CHAVE=/p' /opt/agrocontabil/.env >> "$f"
  fi )
chmod 600 /opt/borda/.env && grep -cE '^AGROCONIA_TLS_ASK_CHAVE=[A-Za-z0-9_-]{32,}$' /opt/borda/.env
```

O último número tem de ser `1`. Depois, instalar o arquivo do inquilino pelo script da borda
(`/opt/agrocontabil/borda/agroconia.caddy` → `/opt/borda/sites/`; com a borda ainda parada, só a
validação e a cópia), validar tudo e baixar as imagens:

```bash
cd /opt/borda && docker compose config -q && docker compose pull && docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
cd /opt/agrocontabil && docker compose -f compose.yaml.novo config -q && docker compose -f compose.yaml.novo pull agroconia-caddy && docker compose -f compose.yaml.novo run --rm --no-deps agroconia-caddy caddy validate --config /etc/caddy/Caddyfile
```

As duas validações têm de dizer `Valid configuration`.

**Política de TLS do `www` na borda de verdade** (o ensaio usa uma borda de mentira). O `grep` mostra
só nomes; a saída inteira do `adapt` tem a chave do `ask` e não deve ir para a tela:

```bash
cd /opt/borda && docker compose run --rm --no-deps -T caddy caddy adapt --config /etc/caddy/Caddyfile 2>/dev/null | grep -oE '"subjects":\[[^]]*"(www|\*)\.agroconia\.com\.br"[^]]*\]' | head -1
```

A linha tem de conter `"www.agroconia.com.br"`. Se aparecer só `"*.agroconia.com.br"`, **pare**: o
`www` ficaria sem certificado.

## 3. Janela

Sem merges. **Tudo num mesmo shell, com o lock do deploy**:

```bash
cd /opt/agrocontabil && flock -w 900 .deploy.lock bash
```

### 3.1 Backup e retrato do estado atual

```bash
ts=$(date +%Y%m%d-%H%M%S); mkdir -p backups
cp -p compose.yaml "compose.yaml.bak-$ts"; cp -p Caddyfile "Caddyfile.bak-$ts"
cp -p compose.yaml compose.yaml.antes-da-borda   # nome fixo: e este que a volta atras restaura
docker run --rm -v agrocontabil_caddy_data:/d:ro -v "$PWD/backups:/b" caddy:2.10-alpine sh -c "umask 077; tar czf /b/caddy_data-$ts.tgz -C /d ."   # tem chaves privadas
cert() { echo | openssl s_client -connect 127.0.0.1:443 -servername www.agroconia.com.br 2>/dev/null | openssl x509 -noout -fingerprint -sha256 -issuer; }
# curl na VPS falando com ela mesma, sem depender de voltar pelo IP publico
c() { curl --max-time 20 --resolve www.agroconia.com.br:443:127.0.0.1 --resolve agroconia.com.br:443:127.0.0.1 --resolve ruraloeste.agroconia.com.br:443:127.0.0.1 --resolve naoexiste.agroconia.com.br:443:127.0.0.1 --resolve agrocontabil.167-126-45-181.sslip.io:443:127.0.0.1 "$@"; }
cert | tee backups/cert-antes.txt   # tem de mostrar emissor e impressao; vazio = pare
c -sSI https://www.agroconia.com.br/ | grep -iE '^(HTTP|strict|x-content|referrer|alt-svc|server)' | tee backups/headers-antes.txt
```

### 3.2 Subir só o proxy, ao lado do Caddy antigo (nada muda no ar)

```bash
docker compose -f compose.yaml.novo up -d --no-deps agroconia-caddy
docker compose -f compose.yaml.novo ps agroconia-caddy   # esperar "healthy"; o aviso de órfão (caddy) é esperado
```

**Não use `--remove-orphans` em nenhum passo:** ele apagaria o Caddy antigo, que é a volta atrás.

Testar o proxy pela rede `borda`:

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

### 3.3 Troca (indisponibilidade de segundos)

```bash
docker stop agrocontabil-caddy-1                 # sem "rm"
(cd /opt/borda && docker compose up -d)
```

### 3.4 Conferências

```bash
c -sS https://www.agroconia.com.br/healthz; echo                 # UP (o primeiro pode esperar a borda subir)
cert | diff - backups/cert-antes.txt && echo "mesmo certificado"
c -sSI https://www.agroconia.com.br/ | grep -iE '^(HTTP|strict|x-content|referrer|alt-svc|server)' | diff - backups/headers-antes.txt && echo "mesmos cabeçalhos"
c -sSI 'https://agroconia.com.br/x?a=1' | grep -iE '^(HTTP|location)'                       # 301 para o www
c -sSI https://agrocontabil.167-126-45-181.sslip.io/ | grep -iE '^(HTTP|location)'          # 301 para o www
c -sSI https://ruraloeste.agroconia.com.br/ | grep -iE '^(HTTP|x-robots|referrer|strict)'   # 200, noindex, um de cada
c -s https://naoexiste.agroconia.com.br/ -o /dev/null; echo "saida $? (tem de ser diferente de 0)"
```

E de fora da VPS, no PC: `curl -sS https://www.agroconia.com.br/healthz` tem de dar `UP`.

A ordem dos cabeçalhos pode mudar no `diff`: o que importa são os mesmos nomes e valores, **uma vez
cada**.

De fora da VPS, no navegador: login no `www` e em `ruraloeste.agroconia.com.br`; uma pergunta
**digitada** à Ceres (a resposta chega, sem erro no stream); um anexo perto de 5 MB numa
pendência; a landing.

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

```bash
(cd /opt/borda && docker compose stop)
docker start agrocontabil-caddy-1
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
  Anote o total e remova (sem tocar no resto de `/data`, que tem os certificados):

  ```bash
  cd /opt/borda
  docker compose exec -T caddy sh -c "{ cat /data/acessos/www.log; zcat /data/acessos/www-*.log.gz 2>/dev/null; } | grep -c 'utm_source=cartao'"
  docker compose exec -T caddy rm -rf /data/acessos
  ```

- `SITE_REDIRECIONA` no `.env` do AgroConIA fica sem uso.

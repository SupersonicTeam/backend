#!/usr/bin/env bash
# Ensaio local da borda: dois Caddy de verdade em cadeia (a borda de ensaio com o
# ../borda/agroconia.caddy, e o ../caddy/Caddyfile atras dela), com backend e frontend de mentira.
# Nada aqui toca a VPS nem a stack de desenvolvimento; tudo e criado e apagado pelo script.
#
#   bash deploy/ensaio-borda/ensaio.sh
#
# Precisa de Docker e curl, das portas 127.0.0.1:18080 e :18443 livres e da faixa 172.31.255.0/24.
set -uo pipefail

export MSYS_NO_PATHCONV=1
AQUI=$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))
DEPLOY=${AQUI%/ensaio-borda}
IMAGEM=$(grep -oE 'caddy:2\.10-alpine@sha256:[0-9a-f]{64}' "$DEPLOY/compose.yaml")
CHAVE=chave-de-ensaio-0123456789abcdef0123
P=ensaio-borda
TMP=$(mktemp -d)
TMPW=$(cd "$TMP" && (pwd -W 2>/dev/null || pwd))
FALHAS=0

limpar() {
  docker rm -f $P-borda $P-proxy $P-backend $P-frontend >/dev/null 2>&1
  docker network rm $P-borda $P-interna >/dev/null 2>&1
  rm -rf "$TMP"
}
trap limpar EXIT
limpar
mkdir -p "$TMP/jornada/v0.1"
echo '{"versao":"0.1"}' > "$TMP/jornada/v0.1/manifesto.json"
echo '<script></script>' > "$TMP/jornada/v0.1/pagina.html"

# O backend de mentira devolve o que recebeu e manda os cabecalhos de seguranca que o de verdade
# manda, para o ensaio pegar cabecalho duplicado.
cat > "$TMP/backend.Caddyfile" <<EOF
:8080 {
	header Strict-Transport-Security "max-age=31536000; includeSubDomains"
	header X-Content-Type-Options nosniff
	header Referrer-Policy no-referrer

	handle /actuator/health {
		respond "{}" 200
	}
	handle /interno/tls/permitido {
		@ok query chave=$CHAVE domain=ruraloeste.agroconia.com.br
		respond @ok 200
		respond 404
	}
	handle {
		respond "backend uri={uri} xff=[{header.X-Forwarded-For}] proto={header.X-Forwarded-Proto} xfh={header.X-Forwarded-Host} host={host}"
	}
}
EOF
printf ':80 {\n\trespond "frontend uri={uri}"\n}\n' > "$TMP/frontend.Caddyfile"

# Um segundo inquilino: o certificado do www do AgroConIA ja caiu so por existir outro bloco.
printf 'www.vizinho.example {
	respond "vizinho"
}
' > "$TMP/vizinho.caddy"

docker network create $P-interna >/dev/null || exit 1
docker network create --driver bridge --subnet 172.31.255.0/24 --gateway 172.31.255.1 \
  --ip-range 172.31.255.128/25 $P-borda >/dev/null || exit 1

for s in backend frontend; do
  docker run -d --name $P-$s --network $P-interna --network-alias $s \
    -v "$TMPW/$s.Caddyfile:/etc/caddy/Caddyfile:ro" "$IMAGEM" >/dev/null || exit 1
done
docker create --name $P-proxy --network $P-interna --network-alias agroconia-caddy \
  -e SITE_HOST=www.agroconia.com.br -e SITE_DOMINIO=agroconia.com.br \
  -v "$DEPLOY/caddy:/etc/caddy:ro" -v "$TMPW/jornada:/srv/jornada:ro" "$IMAGEM" >/dev/null || exit 1
docker network connect --alias agroconia-caddy $P-borda $P-proxy || exit 1
docker start $P-proxy >/dev/null || exit 1
docker run -d --name $P-borda --network $P-borda --ip 172.31.255.2 \
  -p 127.0.0.1:18080:80 -p 127.0.0.1:18443:443 -e AGROCONIA_TLS_ASK_CHAVE=$CHAVE \
  -v "$AQUI/borda.Caddyfile:/etc/caddy/Caddyfile:ro" \
  -v "$DEPLOY/borda/agroconia.caddy:/etc/caddy/sites/agroconia.caddy:ro" \
  -v "$TMPW/vizinho.caddy:/etc/caddy/sites/vizinho.caddy:ro" "$IMAGEM" >/dev/null || exit 1

checar() { # descricao, comando que precisa dar certo
  local d=$1; shift
  if "$@"; then echo "ok      $d"; else echo "FALHOU  $d"; FALHAS=$((FALHAS + 1)); fi
}
contem() { grep -qF -- "$2" <<<"$1"; }
sem() { ! grep -qiF -- "$2" <<<"$1"; }
vezes() { [ "$(grep -ciE "^$2:" <<<"$1")" = "$3" ]; }
pedir() { # host, caminho, opcoes extras do curl
  local h=$1 c=$2; shift 2
  curl -sk --max-time 15 --resolve "$h:18443:127.0.0.1" "$@" "https://$h:18443$c"
}
cabecalhos() { local h=$1 c=$2; shift 2; pedir "$h" "$c" -D - -o /dev/null "$@" | tr -d '\r'; }
recusado() { ! pedir "$1" / -o /dev/null; }
da_borda() { docker run --rm --network $P-borda "$IMAGEM" "$@" 2>&1; }

W=www.agroconia.com.br
E=ruraloeste.agroconia.com.br
curl -s --retry 20 --retry-delay 1 --retry-all-errors --max-time 3 -ko /dev/null \
  --resolve $W:18443:127.0.0.1 https://$W:18443/

echo "--- certificados e roteamento"
checar "validate da borda com o agroconia.caddy" bash -c \
  "docker exec -e AGROCONIA_TLS_ASK_CHAVE=$CHAVE $P-borda caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -q 'Valid configuration'"
checar "validate do agroconia-caddy" bash -c \
  "docker exec $P-proxy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -q 'Valid configuration'"
checar "www serve o frontend (certificado proprio, ao lado do curinga)" contem "$(pedir $W /rota)" "frontend uri=/rota"
checar "o inquilino vizinho segue servido" contem "$(pedir www.vizinho.example /)" "vizinho"

# No modo ACME de producao (o ensaio sobe com CA interna): a primeira politica de TLS que casa com
# o www nao pode ser a sob demanda do curinga.
sed '/^	local_certs$/d; /^	skip_install_trust$/d' "$AQUI/borda.Caddyfile" > "$TMP/borda-acme.Caddyfile"
politicas=$(docker run --rm -e AGROCONIA_TLS_ASK_CHAVE=$CHAVE   -v "$TMPW/borda-acme.Caddyfile:/etc/caddy/Caddyfile:ro"   -v "$DEPLOY/borda/agroconia.caddy:/etc/caddy/sites/agroconia.caddy:ro"   -v "$TMPW/vizinho.caddy:/etc/caddy/sites/vizinho.caddy:ro"   "$IMAGEM" caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile 2>/dev/null)
www_com_politica_propria() {
  # A primeira politica que cita o www (por nome ou pelo curinga) e a que vale para ele.
  local primeira
  primeira=$(grep -oE '"subjects":\[[^]]*"(www|\*)\.agroconia\.com\.br"[^]]*\]' <<<"$politicas" | head -1)
  contem "$primeira" '"www.agroconia.com.br"'
}
checar "modo ACME: o www tem politica propria, antes da sob demanda do curinga" www_com_politica_propria

checar "healthz UP" contem "$(pedir $W /healthz)" "UP"
checar "escritorio autorizado pelo ask ganha certificado e serve o frontend" contem "$(pedir $E /)" "frontend uri=/"
checar "slug que o ask recusa nao ganha certificado" recusado naoexiste.agroconia.com.br
checar "host de outro dominio e recusado" recusado desconhecido.example
checar "escritorio nao tem healthz (cai no frontend)" contem "$(pedir $E /healthz)" "frontend uri=/healthz"

echo "--- IP real, protocolo e host"
r=$(pedir $W /api/eco -H 'X-Forwarded-For: 1.2.3.4' -H 'X-Forwarded-Proto: http' -H 'X-Forwarded-Host: forjado.example')
echo "        $r"
checar "backend recebe /api" contem "$r" "backend uri=/api/eco"
checar "X-Forwarded-For forjado pelo visitante nao chega" sem "$r" "1.2.3.4"
checar "a cadeia e 'visitante, borda'" bash -c "grep -qE 'xff=\[[0-9a-f.:]+, 172\.31\.255\.2\]' <<<\"\$0\"" "$r"
checar "proto https preservado" contem "$r" "proto=https"
checar "X-Forwarded-Host e o endereco pedido" contem "$r" "xfh=$W"
r=$(pedir $E /api/eco)
checar "no escritorio, o backend recebe o host do escritorio" contem "$r" "xfh=$E"
intruso=$(da_borda wget -qO- --header "Host: $W" --header 'X-Forwarded-For: 1.2.3.4' \
  --header 'X-Forwarded-Proto: https' http://agroconia-caddy/api/eco)
echo "        $intruso"
checar "outro container da borda nao forja o IP" sem "$intruso" "1.2.3.4"
checar "e o IP visto e o dele, da faixa dos inquilinos" bash -c \
  "grep -qE 'xff=\[172\.31\.255\.(12[89]|1[3-9][0-9]|2[0-9][0-9])\] proto=http ' <<<\"\$0\"" "$intruso"

echo "--- cabecalhos"
for alvo in "$W /" "$W /api/eco" "$E /" "$E /api/eco" "$W /jornada/v0.1/manifesto.json" "$W /jornada/nada.json"; do
  h=$(cabecalhos $alvo)
  checar "$alvo: um HSTS, um nosniff, um Referrer-Policy, sem Server" bash -c '
    for n in strict-transport-security x-content-type-options referrer-policy; do
      [ "$(grep -ciE "^$n:" <<<"$0")" = 1 ] || exit 1
    done
    ! grep -qiE "^server:" <<<"$0"' "$h"
done
checar "escritorio com noindex (pagina e API)" vezes "$(cabecalhos $E /api/eco)" x-robots-tag 1
checar "www sem noindex" vezes "$(cabecalhos $W /)" x-robots-tag 0
checar "HTTP/3 anunciado" contem "$(cabecalhos $W /)" 'h3='

echo "--- redirecionamentos"
for h in agroconia.com.br agrocontabil.167-126-45-181.sslip.io; do
  c=$(cabecalhos $h '/rota?a=1')
  checar "$h -> 301 para o www com caminho e query" bash -c \
    'grep -qE "^HTTP/[0-9.]+ 301" <<<"$0" && grep -qiF "location: https://www.agroconia.com.br/rota?a=1" <<<"$0"' "$c"
done
checar "porta 80 do www redireciona para https" bash -c \
  "curl -sI --max-time 10 -H 'Host: $W' http://127.0.0.1:18080/ | grep -qiF 'location: https://$W/'"

echo "--- jornada"
h=$(cabecalhos $W /jornada/v0.1/manifesto.json)
checar "manifesto 200 com cache de um ano" bash -c 'grep -qE "^HTTP/[0-9.]+ 200" <<<"$0" && grep -qi immutable <<<"$0"' "$h"
checar "html na pasta da jornada nao sai (404)" bash -c 'grep -qE "^HTTP/[0-9.]+ 404" <<<"$0"' "$(cabecalhos $W /jornada/v0.1/pagina.html)"

echo "--- ask, porta 8081 e administracao"
checar "/interno pela internet nao chega ao backend" contem \
  "$(pedir $W "/interno/tls/permitido?chave=$CHAVE&domain=$E")" "frontend uri=/interno"
checar "ask na 8081 autoriza o slug servido" bash -c \
  "docker run --rm --network $P-borda $IMAGEM wget -qO /dev/null 'http://agroconia-caddy:8081/interno/tls/permitido?chave=$CHAVE&domain=$E'"
checar "ask na 8081 com chave errada: 404" contem \
  "$(da_borda wget -qO- "http://agroconia-caddy:8081/interno/tls/permitido?chave=errada&domain=$E")" "404"
checar "8081 nao repassa a API" contem "$(da_borda wget -qO- http://agroconia-caddy:8081/api/eco)" "404"
checar "API de administracao do proxy fora da rede borda" contem \
  "$(da_borda wget -qO- -T 5 http://agroconia-caddy:2019/config/)" "refused"
checar "reload do proxy funciona" docker exec -w /etc/caddy $P-proxy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1
sem_nome() { ! docker run --rm --network $P-borda "$IMAGEM" getent hosts "$1" >/dev/null 2>&1; }
checar "agroconia-caddy resolve na rede borda" bash -c \
  "docker run --rm --network $P-borda $IMAGEM getent hosts agroconia-caddy >/dev/null"
checar "backend nao resolve na rede borda" sem_nome backend
checar "frontend nao resolve na rede borda" sem_nome frontend

echo "--- log de acessos (#484)"
pedir $W '/?utm_source=cartao&fbclid=abc' -H 'Cookie: s=1' -o /dev/null
sleep 2
log=$(docker exec $P-proxy cat /data/acessos/www.log 2>/dev/null)
checar "registra a raiz, com o utm_source" contem "$log" '"uri":"/?utm_source=cartao"'
checar "so a raiz" bash -c '! grep -qE "\"uri\":\"/[^\"?]" <<<"$0"' "$log"
checar "sem IP" bash -c '! grep -qE "remote_ip|client_ip|172\.31\.255|1\.2\.3\.4" <<<"$0"' "$log"
checar "sem cookie nem fbclid" bash -c '! grep -qiE "cookie|fbclid" <<<"$0"' "$log"

echo
if [ "$FALHAS" = 0 ]; then echo "ENSAIO OK"; else echo "ENSAIO COM $FALHAS FALHA(S)"; exit 1; fi

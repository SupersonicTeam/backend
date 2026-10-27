#!/usr/bin/env bash
# Deploy de UM servico do compose da VPS, chamado pelo GitHub Actions (docker-publish.yml de cada repo).
#
#   deploy.sh <backend|frontend> <tag>      ex.: deploy.sh backend sha-4ae989b
#
# - Lock unico da VPS (flock): backend e frontend fazem deploy em serie, nunca ao mesmo tempo — os
#   dois mexem no mesmo .env e no mesmo compose. Quem chega depois espera (ate DEPLOY_LOCK_TIMEOUT).
# - So o servico pedido e recriado (--no-deps): o deploy do frontend nao reinicia o backend e vice-versa.
# - A tag fica gravada no .env (BACKEND_TAG/FRONTEND_TAG): um `docker compose up -d` manual depois
#   continua na versao implantada, nao volta para latest.
# - Rollback: se o container nao ficar healthy em DEPLOY_HEALTH_TIMEOUT, volta para a tag anterior e
#   sai com erro (o job do Actions falha).
#
# Chamado pela chave SSH da CI via ssh-forcado.sh (authorized_keys com command=), que valida os
# argumentos antes de chegar aqui.
set -euo pipefail

DIR=/opt/agrocontabil
LOCK="$DIR/.deploy.lock"
LOCK_TIMEOUT="${DEPLOY_LOCK_TIMEOUT:-900}"
HEALTH_TIMEOUT="${DEPLOY_HEALTH_TIMEOUT:-300}"

servico="${1:-}"
tag="${2:-}"

case "$servico" in
  backend) var=BACKEND_TAG ;;
  frontend) var=FRONTEND_TAG ;;
  *) echo "servico invalido: '$servico' (backend|frontend)" >&2; exit 2 ;;
esac
if [[ ! "$tag" =~ ^(latest|sha-[0-9a-f]{7,40})$ ]]; then
  echo "tag invalida: '$tag' (latest ou sha-<hex>)" >&2
  exit 2
fi

cd "$DIR"

log() { echo "[deploy $servico $(date '+%F %T')] $*"; }

tag_atual() { grep -E "^$var=" .env | cut -d= -f2- || true; }

gravar_tag() {
  if grep -qE "^$var=" .env; then
    sed -i "s/^$var=.*/$var=$1/" .env
  else
    echo "$var=$1" >> .env
  fi
}

# Espera o container do servico ficar healthy (todo servico implantado tem healthcheck no compose).
esperar_saudavel() {
  local id estado limite=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < limite )); do
    id=$(docker compose ps -q "$servico")
    estado=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo ausente)
    case "$estado" in
      healthy) return 0 ;;
      unhealthy|exited|dead) log "container $estado"; return 1 ;;
    esac
    sleep 5
  done
  log "nao ficou healthy em ${HEALTH_TIMEOUT}s"
  return 1
}

subir() {
  docker compose pull --quiet "$servico"
  docker compose up -d --no-deps "$servico"
}

exec 9>"$LOCK"
log "aguardando o lock da VPS..."
if ! flock -w "$LOCK_TIMEOUT" 9; then
  log "outro deploy segurou o lock por mais de ${LOCK_TIMEOUT}s; desistindo"
  exit 75
fi
log "lock obtido"

anterior=$(tag_atual)
anterior=${anterior:-latest}
log "$anterior -> $tag"

gravar_tag "$tag"
if subir && esperar_saudavel; then
  log "ok: $servico em $tag"
  docker image prune -f >/dev/null || true
  exit 0
fi

log "falhou; ultimas linhas do log:"
docker compose logs --tail 40 "$servico" || true

log "rollback para $anterior"
gravar_tag "$anterior"
if subir && esperar_saudavel; then
  log "rollback ok: $servico de volta em $anterior"
else
  log "ROLLBACK FALHOU: $servico fora do ar, intervencao manual"
fi
exit 1

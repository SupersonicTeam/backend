#!/usr/bin/env bash
# Diagnóstico do classificador de rota no conjunto de desenvolvimento (#497).
# Uso: bash docs/avaliacao/rota-dev/rodar_diagnostico.sh <arquivo-saida.md> [--modelo]
# O conjunto padrao e o perguntas-rota-dev.json; CONJUNTO=<arquivo> troca (#624: o -2).
#
# - Precisa do Ollama local com o bge-m3 (docker start agrocontabil-ollama).
# - Com --modelo, as perguntas que chegam ao desempate vão ao Haiku pela API: a ANTHROPIC_API_KEY é
#   lida do .env do checkout principal só dentro da subshell que sobe a JVM, nunca impressa nem
#   gravada (o mesmo padrão do rodar_medicao_499.sh). Só este conjunto vai ao modelo.
# - Respeita a trava do Ollama das sessões locais: espera a pasta sumir e a cria enquanto roda.
set -u

[ $# -ge 1 ] || { echo "uso: $0 <arquivo-saida.md> [--modelo]"; exit 2; }
mkdir -p "$(dirname "$1")" || exit 1
SAIDA="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
MODELO=false
[ "${2:-}" = "--modelo" ] && MODELO=true
RAIZ=$(cd "$(dirname "$0")/../../.." && pwd) || exit 1
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'

while ! mkdir "$TRAVA" 2>/dev/null; do sleep 30; done
echo "sessao: diagnostico de rota (rota-dev, ${CONJUNTO:-perguntas-rota-dev.json}), inicio $(date -Iseconds)" > "$TRAVA/dono.txt"
trap 'rm -rf "$TRAVA"' EXIT

(
  cd "$RAIZ" || exit 1
  if [ "$MODELO" = true ]; then
    ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
    export ANTHROPIC_API_KEY
  fi
  ./mvnw -q test -Dtest=DiagnosticoRotaDevManual -Dsurefire.failIfNoSpecifiedTests=false \
    -Djacoco.skip=true -Ddiagnostico.saida="$SAIDA" -Ddiagnostico.modelo="$MODELO" \
    -Ddiagnostico.conjunto="${CONJUNTO:-docs/avaliacao/rota-dev/perguntas-rota-dev.json}"
)

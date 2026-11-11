#!/usr/bin/env bash
# Comparação da correção da #497: braço base e braço correção, com o Claude Haiku, local.
#
# Uso: rodar_comparacao_497.sh <ferramentas> <worktree base> <worktree correção> <pasta de saída>
#
# - <ferramentas>: checkout com o conjunto, o executar.py, o pontuar.py e o comparar.py. Os dois
#   braços são lidos pelas mesmas ferramentas, qualquer que seja a versão delas nos worktrees.
# - Os worktrees dos braços precisam estar limpos: o executor grava o SHA de cada um.
# - Pega a trava do Ollama (o embedding bge-m3 é dele) uma vez para os dois braços e solta no fim.
# - Banco próprio por braço no agrocontabil-postgres, recriado do zero, com o seed da
#   demonstração e a base de lei copiada do banco agrocontabil. Nunca a VPS.
# - ANTHROPIC_API_KEY lida do .env do checkout principal só dentro da subshell que sobe a JVM;
#   nunca impressa nem gravada. A senha das contas semeadas é gerada aqui e só vai por variável.
set -u

FERRAMENTAS=$(cd "$1" && pwd) || exit 1
WT_BASE=$(cd "$2" && pwd) || exit 1
WT_CORRECAO=$(cd "$3" && pwd) || exit 1
mkdir -p "$4" || exit 1
SAIDA=$(cd "$4" && pwd) || exit 1
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
CONJUNTO="$FERRAMENTAS/docs/avaliacao/treino/perguntas-ceres-497.json"
SCRIPTS="$FERRAMENTAS/docs/avaliacao/ceres-497"
EMAIL=carlos.rossi@example.com
export PYTHONUTF8=1

ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256")
ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
[ "$ESPERADO" = "$ATUAL" ] || { echo "CONJUNTO DIFERENTE DO .sha256"; exit 13; }
grep -qE '^ANTHROPIC_API_KEY=.+' "$ENV_FILE" || { echo "ANTHROPIC_API_KEY ausente no .env"; exit 12; }
[ "$(git -C "$WT_BASE" rev-parse HEAD)" != "$(git -C "$WT_CORRECAO" rev-parse HEAD)" ] \
  || { echo "OS DOIS BRACOS TEM O MESMO COMMIT"; exit 13; }

OBTIDA=0
for _ in $(seq 1 240); do
  if mkdir "$TRAVA" 2>/dev/null; then OBTIDA=1; break; fi
  echo "trava ocupada: $(cat "$TRAVA/dono.txt" 2>/dev/null); esperando"
  sleep 30
done
# Só a trava criada pelo nosso mkdir é nossa: uma trava alheia sem dono.txt não conta.
[ "$OBTIDA" = 1 ] || { echo "TRAVA NAO OBTIDA"; exit 10; }
echo "sessao conjunto-452 (comparacao #497, Haiku) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"
parar_app() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'server.port=$1' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
}
encerrar() {
  parar_app 8497
  parar_app 8498
  rm -rf "$TRAVA"
}
trap encerrar EXIT

docker start agrocontabil-postgres agrocontabil-ollama >/dev/null || exit 12
sleep 5
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
[ -n "$DB_PASSWORD" ] || { echo "DB_PASSWORD ausente no .env"; exit 12; }
SENHA=$(python -c "import secrets; print(secrets.token_urlsafe(24))")
export DB_PASSWORD SEED_SENHA="$SENHA" CERES_SENHA="$SENHA"
unset SENHA

psql_() { docker exec -i agrocontabil-postgres psql -v ON_ERROR_STOP=1 -U agrocontabil "$@"; }

# braco <rótulo> <worktree> <banco> <porta>
braco() {
  local ROTULO=$1 WT=$2 BANCO=$3 PORTA=$4
  local DIR="$SAIDA/$ROTULO"
  mkdir -p "$DIR"
  echo "== braço $ROTULO: $(git -C "$WT" rev-parse HEAD)"
  psql_ -d postgres -qc "DROP DATABASE IF EXISTS $BANCO WITH (FORCE)" || return 12
  psql_ -d postgres -qc "CREATE DATABASE $BANCO" || return 12

  ( cd "$WT" && ./mvnw -q -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true package \
      > "$DIR/build.log" 2>&1 ) || { echo "BUILD FALHOU ($ROTULO)"; return 14; }
  local JAR
  JAR=$(ls "$WT"/target/*.jar 2>/dev/null | grep -v plain | head -1)
  [ -n "$JAR" ] || { echo "JAR AUSENTE ($ROTULO)"; return 14; }
  (
    cd "$WT" || exit 1
    ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
    export ANTHROPIC_API_KEY
    SPRING_PROFILES_ACTIVE=dev DB_NAME=$BANCO DEMONSTRACAO_HABILITADA=true \
      AI_COTA_CREDITOS_PRODUTOR=100000000 AI_CHAT_PROVIDER=anthropic \
      AI_ANTHROPIC_MODEL=claude-haiku-5-5 \
      exec java -jar "$JAR" --server.port=$PORTA --spring.docker.compose.enabled=false \
      --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN \
      --logging.level.br.com.agrocontabil=INFO > "$DIR/app.log" 2>&1
  ) &

  for _ in $(seq 1 120); do
    curl -sf "http://localhost:$PORTA/api/v1/health" >/dev/null && break
    sleep 5
  done
  curl -sf "http://localhost:$PORTA/api/v1/health" >/dev/null || { echo "APP NAO SUBIU ($ROTULO)"; return 15; }
  for _ in $(seq 1 120); do
    grep -q "Demonstracao da banca nao semeada" "$DIR/app.log" && { echo "SEED FALHOU ($ROTULO)"; return 15; }
    grep -Eq "Demonstracao da banca (ja )?semeada" "$DIR/app.log" && break
    sleep 5
  done
  grep -Eq "Demonstracao da banca (ja )?semeada" "$DIR/app.log" || { echo "SEED NAO TERMINOU ($ROTULO)"; return 15; }

  docker exec agrocontabil-postgres sh -c \
    "pg_dump -U agrocontabil --data-only -t legislacao_trecho agrocontabil | psql -q -v ON_ERROR_STOP=1 -U agrocontabil -d $BANCO" \
    >/dev/null || { echo "COPIA DA BASE DE LEI FALHOU ($ROTULO)"; return 16; }
  psql_ -d "$BANCO" -Atc "select 'trechos', count(*) from legislacao_trecho
    union all select 'notas', count(*) from nota_fiscal
    union all select 'guias', count(*) from guias_fiscais" > "$DIR/banco.txt"

  python "$SCRIPTS/executar.py" --url "http://localhost:$PORTA" --pasta "$WT" \
    --banco "$BANCO" --modelo anthropic/claude-haiku-5-5 --email "$EMAIL" \
    --saida "$DIR/rodada.jsonl" 2>&1 | tee "$DIR/executor.log"
  local STATUS=${PIPESTATUS[0]}
  [ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS ($ROTULO)"; return 18; }

  python "$SCRIPTS/pontuar.py" --perguntas "$CONJUNTO" --execucoes "$DIR/rodada.jsonl" \
    --saida "$DIR/resumo.json" | tee "$DIR/resumo.txt"
  [ "${PIPESTATUS[0]}" = 0 ] || { echo "LEITURA FALHOU ($ROTULO)"; return 19; }
  parar_app "$PORTA"
}

braco base "$WT_BASE" agroconia_497_base 8497 || exit $?
braco correcao "$WT_CORRECAO" agroconia_497_correcao 8498 || exit $?

python "$SCRIPTS/comparar.py" --perguntas "$CONJUNTO" --base "$SAIDA/base/resumo.json" \
  --correcao "$SAIDA/correcao/resumo.json" --saida "$SAIDA/comparacao.json" \
  | tee "$SAIDA/comparacao.txt"
echo "FIM"

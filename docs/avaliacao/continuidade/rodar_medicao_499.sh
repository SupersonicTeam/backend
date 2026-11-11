#!/usr/bin/env bash
# Medição da continuidade da Ceres (#499): flag desligada x ligada, mesmo commit, Haiku, local.
#
# Uso: rodar_medicao_499.sh <worktree limpo do commit medido> <pasta de saída> [ids...]
#
# O executor, a pontuação e o conjunto saem da pasta deste script (a main com o #504 e as
# ferramentas); do worktree medido saem só o JAR e o commit.
#
# Ids depois da pasta = ensaio só com essas conversas, 1 repetição e sem a lei sozinha (não vale
# como rodada oficial).
#
# - Espera a trava do Ollama local (o embedding bge-m3 é dele) e a ausência de Maven ou Java de
#   outra sessão; pega a trava e a solta no fim, inclusive com erro.
# - Um JAR, duas JVMs: 8501 com AI_CONTINUIDADE_HABILITADA=false e 8502 com true, cada uma com
#   banco próprio (agroconia_499_desligado e agroconia_499_ligado), recriado do zero, com o seed
#   da demonstração e a base de lei copiada do banco agrocontabil. Nunca a VPS.
# - Sem CRIPTO_CHAVE no ambiente: vale a chave pública de dev, a mesma com que o executor assina
#   os pares fabricados.
# - ANTHROPIC_API_KEY lida do .env do checkout principal só dentro da subshell que sobe cada JVM;
#   nunca impressa nem gravada. A senha das contas semeadas é gerada aqui e só vai por variável.
set -u

FERRAMENTAS=$(cd "$(dirname "$0")" && pwd) || exit 1
WT=$(cd "$1" && pwd) || exit 1
mkdir -p "$2" || exit 1
SAIDA=$(cd "$2" && pwd) || exit 1
shift 2
IDS=("$@")
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
PORTA_DESLIGADO=8501
PORTA_LIGADO=8502
BANCO_DESLIGADO=agroconia_499_desligado
BANCO_LIGADO=agroconia_499_ligado
MODELO=claude-haiku-5-5
export PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1
unset CRIPTO_CHAVE

grep -qE '^ANTHROPIC_API_KEY=.+' "$ENV_FILE" || { echo "ANTHROPIC_API_KEY ausente no .env"; exit 12; }
[ -z "$(git -C "$WT" status --porcelain)" ] || { echo "WORKTREE COM MUDANCA FORA DE COMMIT"; exit 13; }

alheios() {  # java ou mvn que não são desta medição
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -match '^(java|javaw|mvn)' -and \$_.CommandLine -notmatch 'server.port=850[12]' -and \$_.CommandLine -notmatch [regex]::Escape('$(cygpath -w "$WT" 2>/dev/null || echo "$WT")') } | ForEach-Object { \$_.ProcessId }" 2>/dev/null | tr -d '\r'
}
OBTIDA=0
for _ in $(seq 1 240); do
  if [ -z "$(alheios)" ] && mkdir "$TRAVA" 2>/dev/null; then OBTIDA=1; break; fi
  echo "$(date '+%H:%M:%S') esperando: trava $(cat "$TRAVA/dono.txt" 2>/dev/null || echo livre); java/mvn alheios: $(alheios | tr '\n' ' ')"
  sleep 30
done
[ "$OBTIDA" = 1 ] || { echo "TRAVA NAO OBTIDA"; exit 10; }
echo "sessao conjunto-452 (medicao da continuidade #499, Haiku) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"
encerrar() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'server.port=850[12]' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
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
for banco in $BANCO_DESLIGADO $BANCO_LIGADO; do
  psql_ -d postgres -qc "DROP DATABASE IF EXISTS $banco WITH (FORCE)" || exit 12
  psql_ -d postgres -qc "CREATE DATABASE $banco" || exit 12
done

( cd "$WT" && ./mvnw -q -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true package \
    > "$SAIDA/build.log" 2>&1 ) || { echo "BUILD FALHOU"; exit 14; }
JAR=$(ls "$WT"/target/*.jar | grep -v plain | head -1)

subir() {  # nome porta banco flag
  (
    cd "$WT" || exit 1
    ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
    export ANTHROPIC_API_KEY
    SPRING_PROFILES_ACTIVE=dev DB_NAME=$3 DEMONSTRACAO_HABILITADA=true \
      AI_COTA_CREDITOS_PRODUTOR=100000000 AI_CHAT_PROVIDER=anthropic AI_ANTHROPIC_MODEL=$MODELO \
      AI_CONTINUIDADE_HABILITADA=$4 \
      exec java -jar "$JAR" --server.port=$2 --spring.docker.compose.enabled=false \
      --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN \
      --logging.level.br.com.agrocontabil=INFO > "$SAIDA/$1-app.log" 2>&1
  ) &
}
esperar() {  # nome porta banco
  for _ in $(seq 1 120); do
    curl -sf "http://localhost:$2/api/v1/health" >/dev/null && break
    sleep 5
  done
  curl -sf "http://localhost:$2/api/v1/health" >/dev/null || { echo "APP $1 NAO SUBIU"; exit 15; }
  for _ in $(seq 1 120); do
    grep -q "Demonstracao da banca nao semeada" "$SAIDA/$1-app.log" && { echo "SEED $1 FALHOU"; exit 15; }
    grep -Eq "Demonstracao da banca (ja )?semeada" "$SAIDA/$1-app.log" && break
    sleep 5
  done
  grep -Eq "Demonstracao da banca (ja )?semeada" "$SAIDA/$1-app.log" || { echo "SEED $1 NAO TERMINOU"; exit 15; }
  docker exec agrocontabil-postgres sh -c \
    "pg_dump -U agrocontabil --data-only -t legislacao_trecho agrocontabil | psql -q -v ON_ERROR_STOP=1 -U agrocontabil -d $3" \
    >/dev/null || { echo "COPIA DA BASE DE LEI $1 FALHOU"; exit 16; }
  psql_ -d "$3" -Atc "select 'trechos', count(*) from legislacao_trecho
    union all select 'notas', count(*) from nota_fiscal
    union all select 'guias', count(*) from guias_fiscais" > "$SAIDA/$1-banco.txt"
}
subir desligado $PORTA_DESLIGADO $BANCO_DESLIGADO false
subir ligado $PORTA_LIGADO $BANCO_LIGADO true
esperar desligado $PORTA_DESLIGADO $BANCO_DESLIGADO
esperar ligado $PORTA_LIGADO $BANCO_LIGADO
diff "$SAIDA/desligado-banco.txt" "$SAIDA/ligado-banco.txt" >/dev/null \
  || { echo "OS DOIS BANCOS DIFEREM"; cat "$SAIDA"/*-banco.txt; exit 17; }
# Aquecimento do embedding: a classificação por kNN depende dele.
curl -s http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"aquecimento","keep_alive":"2h"}' >/dev/null

EXTRA=()
[ ${#IDS[@]} -gt 0 ] && EXTRA=(--repeticoes 1 --lei-sozinha 0 --ids "${IDS[@]}")
python "$FERRAMENTAS/executar_continuidade.py" --pasta "$WT" \
  --braco desligado "http://localhost:$PORTA_DESLIGADO" $BANCO_DESLIGADO \
  --braco ligado "http://localhost:$PORTA_LIGADO" $BANCO_LIGADO \
  --modelo "anthropic/$MODELO" --saida-dir "$SAIDA" ${EXTRA[@]+"${EXTRA[@]}"} 2>&1 | tee "$SAIDA/executor.log"
STATUS=${PIPESTATUS[0]}
[ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS"; exit 18; }

python "$FERRAMENTAS/pontuar_continuidade.py" \
  --execucoes "$SAIDA/rodada.jsonl" --saida "$SAIDA/resumo.json" | tee "$SAIDA/resumo.txt"
echo "FIM"

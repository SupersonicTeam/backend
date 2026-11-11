#!/usr/bin/env bash
# Linha de base da #497: rota e recusa da Ceres com o Claude Haiku, main do dia, local.
#
# Uso: rodar_linha_de_base_497.sh <worktree limpo da main> <pasta de saída>
#
# - Espera a trava do Ollama local (o embedding bge-m3 é dele), pega e solta no fim.
# - Banco próprio (agroconia_497) no agrocontabil-postgres, recriado do zero, com o seed da
#   demonstração e a base de lei copiada do banco agrocontabil. Nunca a VPS.
# - ANTHROPIC_API_KEY lida do .env do checkout principal só dentro da subshell que sobe a JVM;
#   nunca impressa nem gravada. A senha das contas semeadas é gerada aqui e só vai por variável.
set -u

WT=$(cd "$1" && pwd) || exit 1
mkdir -p "$2" || exit 1
SAIDA=$(cd "$2" && pwd) || exit 1
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
CONJUNTO="$WT/docs/avaliacao/treino/perguntas-ceres-497.json"
BANCO=agroconia_497
PORTA=8497
EMAIL=carlos.rossi@example.com
export PYTHONUTF8=1

ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256")
ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
[ "$ESPERADO" = "$ATUAL" ] || { echo "CONJUNTO DIFERENTE DO .sha256"; exit 13; }
grep -qE '^ANTHROPIC_API_KEY=.+' "$ENV_FILE" || { echo "ANTHROPIC_API_KEY ausente no .env"; exit 12; }

for _ in $(seq 1 240); do
  mkdir "$TRAVA" 2>/dev/null && break
  echo "trava ocupada: $(cat "$TRAVA/dono.txt" 2>/dev/null); esperando"
  sleep 30
done
[ -d "$TRAVA" ] && [ ! -f "$TRAVA/dono.txt" ] || { echo "TRAVA NAO OBTIDA"; exit 10; }
echo "sessao conjunto-452 (linha de base #497, Haiku) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"
encerrar() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'server.port=$PORTA' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
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
psql_ -d postgres -qc "DROP DATABASE IF EXISTS $BANCO WITH (FORCE)" || exit 12
psql_ -d postgres -qc "CREATE DATABASE $BANCO" || exit 12

( cd "$WT" && ./mvnw -q -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true package \
    > "$SAIDA/build.log" 2>&1 ) || { echo "BUILD FALHOU"; exit 14; }
JAR=$(ls "$WT"/target/*.jar | grep -v plain | head -1)
(
  cd "$WT" || exit 1
  ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
  export ANTHROPIC_API_KEY
  SPRING_PROFILES_ACTIVE=dev DB_NAME=$BANCO DEMONSTRACAO_HABILITADA=true \
    AI_COTA_CREDITOS_PRODUTOR=100000000 AI_CHAT_PROVIDER=anthropic \
    exec java -jar "$JAR" --server.port=$PORTA --spring.docker.compose.enabled=false \
    --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN \
    --logging.level.br.com.agrocontabil=INFO > "$SAIDA/app.log" 2>&1
) &

for _ in $(seq 1 120); do
  curl -sf "http://localhost:$PORTA/api/v1/health" >/dev/null && break
  sleep 5
done
curl -sf "http://localhost:$PORTA/api/v1/health" >/dev/null || { echo "APP NAO SUBIU"; exit 15; }
for _ in $(seq 1 120); do
  grep -q "Demonstracao da banca nao semeada" "$SAIDA/app.log" && { echo "SEED FALHOU"; exit 15; }
  grep -Eq "Demonstracao da banca (ja )?semeada" "$SAIDA/app.log" && break
  sleep 5
done
grep -Eq "Demonstracao da banca (ja )?semeada" "$SAIDA/app.log" || { echo "SEED NAO TERMINOU"; exit 15; }

docker exec agrocontabil-postgres sh -c \
  "pg_dump -U agrocontabil --data-only -t legislacao_trecho agrocontabil | psql -q -v ON_ERROR_STOP=1 -U agrocontabil -d $BANCO" \
  >/dev/null || { echo "COPIA DA BASE DE LEI FALHOU"; exit 16; }
psql_ -d $BANCO -Atc "select 'trechos', count(*) from legislacao_trecho
  union all select 'notas', count(*) from nota_fiscal
  union all select 'guias', count(*) from guias_fiscais" > "$SAIDA/banco.txt"
grep -i "anthropic\|claude" "$SAIDA/app.log" | grep -iv "key" | head -5 > "$SAIDA/provedor.txt"

python "$WT/docs/avaliacao/ceres-497/executar.py" --url "http://localhost:$PORTA" --pasta "$WT" \
  --banco $BANCO --modelo anthropic/claude-haiku-5-5 --email "$EMAIL" \
  --saida "$SAIDA/rodada.jsonl" 2>&1 | tee "$SAIDA/executor.log"
STATUS=${PIPESTATUS[0]}
[ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS"; exit 18; }

python "$WT/docs/avaliacao/ceres-497/pontuar.py" --perguntas "$CONJUNTO" \
  --execucoes "$SAIDA/rodada.jsonl" --saida "$SAIDA/resumo.json" | tee "$SAIDA/resumo.txt"
echo "FIM"

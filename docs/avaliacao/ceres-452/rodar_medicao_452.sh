#!/usr/bin/env bash
# Rodada da medição da #452: base (main) x correção, local, profile dev, seed da demonstração.
#
# Uso: rodar_medicao_452.sh <worktree da main> <worktree da correção> <pasta de saída>
#
# - Pega a trava do Ollama local (mkdir) e recusa com outra JVM da Ceres no ar.
# - Um banco por braço no container agrocontabil-postgres (agroconia_452_base e
#   agroconia_452_correcao), recriados do zero; a base de lei (legislacao_trecho) é copiada do
#   banco agrocontabil depois do Liquibase.
# - A senha das contas semeadas é gerada aqui, fica só na memória do processo e vai para os filhos
#   por SEED_SENHA e CERES_SENHA. Nunca em arquivo nem na saída.
# - Cota da Ceres alta nos dois braços (AI_COTA_CREDITOS_PRODUTOR): 59 x 3 perguntas passam da
#   cota semanal padrão; a mesma configuração nos dois braços.
set -u

MAIN_WT=$(cd "$1" && pwd) || exit 1
CORR_WT=$(cd "$2" && pwd) || exit 1
mkdir -p "$3" || exit 1
SAIDA=$(cd "$3" && pwd) || exit 1
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
CONJUNTO="$MAIN_WT/docs/avaliacao/treino/perguntas-ceres-452.json"
EMAIL=carlos.rossi@example.com
PIDS=()

ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256")
ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
[ "$ESPERADO" = "$ATUAL" ] || { echo "CONJUNTO DIFERENTE DO .sha256"; exit 13; }

if ! mkdir "$TRAVA" 2>/dev/null; then
  echo "TRAVA OCUPADA: $(cat "$TRAVA/dono.txt" 2>/dev/null)"
  exit 10
fi
encerrar() {
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null; done
  sleep 5
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'server.port=845[23]' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
  rm -rf "$TRAVA"
}
trap encerrar EXIT
echo "sessao conjunto-452 (medicao #452, base x correcao) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"

processos=$(powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ForEach-Object { \$_.CommandLine }" 2>/dev/null) \
  || { echo "NAO DEU PARA CONFERIR AS JVMs"; exit 11; }
if printf '%s\n' "$processos" | grep -Eiq 'spring-boot:run|AgroContabilApplication|backend-tcc.*\.jar'; then
  echo "OUTRA JVM DA CERES NO AR"; exit 11
fi

docker start agrocontabil-postgres agrocontabil-ollama >/dev/null || exit 12
sleep 5
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
[ -n "$DB_PASSWORD" ] || { echo "DB_PASSWORD ausente no .env"; exit 12; }
SENHA=$(python -c "
import secrets, string
a = string.ascii_letters + string.digits
print('Rx' + ''.join(secrets.choice(a) for _ in range(18)) + '!9')")
export DB_PASSWORD SEED_SENHA="$SENHA" CERES_SENHA="$SENHA"
unset SENHA

psql_() { docker exec -i agrocontabil-postgres psql -v ON_ERROR_STOP=1 -U agrocontabil "$@"; }
for db in agroconia_452_base agroconia_452_correcao; do
  psql_ -d postgres -qc "DROP DATABASE IF EXISTS $db WITH (FORCE)" || exit 12
  psql_ -d postgres -qc "CREATE DATABASE $db" || exit 12
done

subir() {  # nome worktree porta banco
  local nome=$1 wt=$2 porta=$3 banco=$4
  ( cd "$wt" && ./mvnw -q -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true package \
      > "$SAIDA/$nome-build.log" 2>&1 ) || { echo "BUILD FALHOU: $nome"; exit 14; }
  local jar
  jar=$(ls "$wt"/target/*.jar | grep -v plain | head -1)
  ( cd "$wt" && SPRING_PROFILES_ACTIVE=dev DB_NAME=$banco DEMONSTRACAO_HABILITADA=true \
      AI_COTA_CREDITOS_PRODUTOR=100000000 \
      java -jar "$jar" --server.port="$porta" --spring.docker.compose.enabled=false \
      --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN \
      --logging.level.br.com.agrocontabil=INFO \
      > "$SAIDA/$nome-app.log" 2>&1 ) &
  PIDS+=($!)
}
esperar() {  # porta
  for _ in $(seq 1 120); do
    curl -sf "http://localhost:$1/api/v1/health" >/dev/null && return 0
    sleep 5
  done
  echo "APP NA PORTA $1 NAO SUBIU"; exit 15
}

subir base "$MAIN_WT" 8452 agroconia_452_base
subir correcao "$CORR_WT" 8453 agroconia_452_correcao
esperar 8452
esperar 8453

for db in agroconia_452_base agroconia_452_correcao; do
  docker exec agrocontabil-postgres sh -c \
    "pg_dump -U agrocontabil --data-only -t legislacao_trecho agrocontabil | psql -q -v ON_ERROR_STOP=1 -U agrocontabil -d $db" \
    >/dev/null || { echo "COPIA DA BASE DE LEI FALHOU: $db"; exit 16; }
done

# Paridade dos dois bancos: o que a Ceres consulta do produtor da medição.
paridade() {
  psql_ -d "$1" -Atc "
    select 'trechos', count(*)::text from legislacao_trecho
    union all select 'notas ' || status || ' ' || origem, count(*) || ' ' || coalesce(sum(valor_total_nota),0)
      from nota_fiscal group by status, origem
    union all select 'guias ' || status || ' ' || to_char(data_vencimento,'YYYY-MM'), count(*) || ' ' || sum(valor)
      from guias_fiscais group by status, to_char(data_vencimento,'YYYY-MM')
    order by 1"
}
paridade agroconia_452_base > "$SAIDA/paridade-base.txt"
paridade agroconia_452_correcao > "$SAIDA/paridade-correcao.txt"
if ! diff -q "$SAIDA/paridade-base.txt" "$SAIDA/paridade-correcao.txt" >/dev/null; then
  echo "BANCOS DIFERENTES ENTRE OS BRACOS"; diff "$SAIDA/paridade-base.txt" "$SAIDA/paridade-correcao.txt"
  exit 17
fi

python "$MAIN_WT/docs/avaliacao/ceres-452/executar.py" \
  --braco base http://localhost:8452 "$MAIN_WT" \
  --braco correcao http://localhost:8453 "$CORR_WT" \
  --email "$EMAIL" --saida-dir "$SAIDA" 2>&1 | tee "$SAIDA/executor.log"
STATUS=${PIPESTATUS[0]}
[ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS"; exit 18; }

P="$MAIN_WT/docs/avaliacao/ceres-452/pontuar.py"
python "$P" pontuar --perguntas "$CONJUNTO" --execucoes "$SAIDA/base.jsonl" --saida "$SAIDA/resumo-base.json"
python "$P" pontuar --perguntas "$CONJUNTO" --execucoes "$SAIDA/correcao.jsonl" --saida "$SAIDA/resumo-correcao.json"
python "$P" comparar --base "$SAIDA/resumo-base.json" --correcao "$SAIDA/resumo-correcao.json" \
  | tee "$SAIDA/decisao.json"
echo "FIM"

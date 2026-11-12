#!/usr/bin/env bash
# Medição da correção da #516: braço base e braço correção, com o Claude Haiku, local. Cada braço
# roda o ceres-516-v1 e o ceres-497-v1 (não regressão, com a l-08 à parte) na mesma subida.
#
# Uso: rodar_medicao_516.sh <ferramentas> <worktree base> <worktree correção> <pasta de saída>
#
# - <ferramentas>: checkout com os dois conjuntos, o executar.py e o pontuar.py da #497 e o
#   decidir.py desta pasta. Os dois braços são lidos pelas mesmas ferramentas.
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
CONJUNTO_516="$FERRAMENTAS/docs/avaliacao/ceres-516/conjunto-516.json"
CONJUNTO_497="$FERRAMENTAS/docs/avaliacao/treino/perguntas-ceres-497.json"
SCRIPTS="$FERRAMENTAS/docs/avaliacao/ceres-497"
DECIDIR="$FERRAMENTAS/docs/avaliacao/ceres-516/decidir.py"
EMAIL=carlos.rossi@example.com
export PYTHONUTF8=1

for CONJUNTO in "$CONJUNTO_516" "$CONJUNTO_497"; do
  ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256")
  ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
  [ "$ESPERADO" = "$ATUAL" ] || { echo "CONJUNTO DIFERENTE DO .sha256: $CONJUNTO"; exit 13; }
done
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
echo "sessao conjunto-452 (medicao #516, Haiku) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"
parar_app() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'server.port=$1' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
}
encerrar() {
  parar_app 8516
  parar_app 8517
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

  local NOME CONJ STATUS
  for NOME in 516 497; do
    if [ "$NOME" = 516 ]; then CONJ=$CONJUNTO_516; else CONJ=$CONJUNTO_497; fi
    # O ritmo de 3,5 s é de cada executor: entre dois, a folga fica aqui (20 por minuto no chat).
    sleep 4
    python "$SCRIPTS/executar.py" --url "http://localhost:$PORTA" --pasta "$WT" \
      --banco "$BANCO" --modelo anthropic/claude-haiku-5-5 --email "$EMAIL" \
      --conjunto "$CONJ" --saida "$DIR/rodada-$NOME.jsonl" 2>&1 | tee "$DIR/executor-$NOME.log"
    STATUS=${PIPESTATUS[0]}
    [ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS ($ROTULO, $NOME)"; return 18; }
    python "$SCRIPTS/pontuar.py" --perguntas "$CONJ" --execucoes "$DIR/rodada-$NOME.jsonl" \
      --saida "$DIR/resumo-$NOME.json" | tee "$DIR/resumo-$NOME.txt"
    [ "${PIPESTATUS[0]}" = 0 ] || { echo "LEITURA FALHOU ($ROTULO, $NOME)"; return 19; }
  done
  parar_app "$PORTA"
}

braco base "$WT_BASE" agroconia_516_base 8516 || exit $?
braco correcao "$WT_CORRECAO" agroconia_516_correcao 8517 || exit $?

python "$DECIDIR" --conjunto-516 "$CONJUNTO_516" --conjunto-497 "$CONJUNTO_497" \
  --base-516 "$SAIDA/base/resumo-516.json" --correcao-516 "$SAIDA/correcao/resumo-516.json" \
  --base-497 "$SAIDA/base/resumo-497.json" --correcao-497 "$SAIDA/correcao/resumo-497.json" \
  --saida "$SAIDA/decisao.json" | tee "$SAIDA/decisao.txt"
[ "${PIPESTATUS[0]}" = 0 ] || { echo "DECISAO FALHOU"; exit 20; }
# Comparação completa do ceres-497-v1 pela regra do #510, só para o relatório (não decide).
python "$SCRIPTS/comparar.py" --perguntas "$CONJUNTO_497" --base "$SAIDA/base/resumo-497.json" \
  --correcao "$SAIDA/correcao/resumo-497.json" --contaminadas l-08 \
  --saida "$SAIDA/comparacao-497.json" > "$SAIDA/comparacao-497.txt"
echo "FIM"

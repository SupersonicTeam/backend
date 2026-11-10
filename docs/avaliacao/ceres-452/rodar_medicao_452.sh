#!/usr/bin/env bash
# Rodada da medição da #452: base (main) x correção, local, profile dev, seed da demonstração.
#
# Uso: rodar_medicao_452.sh <worktree da main> <worktree da correção> <pasta de saída> <conjunto>
#        [<id fora da decisão> ...]
#
# <conjunto> é o caminho do conjunto congelado relativo à raiz do worktree da main, por exemplo
# docs/avaliacao/treino/perguntas-ceres-452-v2.json (a regra de cada conjunto diz qual usar).
# Os ids depois dele são as perguntas que a regra da rodada tira da decisão (na v2f, v2-g4-06,
# regra-de-decisao-v2f.md): rodam nos dois braços e vão ao `pontuar --fora-da-decisao`.
#
# - Pega a trava do Ollama local (mkdir) e recusa com outra JVM da Ceres no ar.
# - Um banco por braço no container agrocontabil-postgres (agroconia_452_base e
#   agroconia_452_correcao), recriados do zero; a base de lei (legislacao_trecho) é copiada do
#   banco agrocontabil depois do Liquibase.
# - A senha das contas semeadas é gerada aqui, fica só na memória do processo e vai para os filhos
#   por SEED_SENHA e CERES_SENHA. Nunca em arquivo nem na saída.
# - Cota da Ceres alta nos dois braços (AI_COTA_CREDITOS_PRODUTOR): as perguntas do conjunto x 3
#   repetições passam da cota semanal padrão; a mesma configuração nos dois braços.
# - Paridade que barra a rodada: só o que as funções da Ceres leem para o produtor da medição
#   (paridade_produtor.sql: notas e itens, guias, apuração, alertas e pendências dele, obrigações
#   do catálogo do escritório que o atende, trechos de lei). A paridade geral do banco fica em
#   paridade-geral-*.txt, sem barrar: a ordem do DataLoader e do SeedDemonstracao pode mudar de
#   um JAR para outro, e os produtores do DataLoader (sem nota) ganham ou não apurações zeradas.
set -u

[ $# -ge 4 ] || { echo "Uso: $0 <worktree da main> <worktree da correcao> <saida> <conjunto> [<id fora da decisao> ...]"; exit 1; }
MAIN_WT=$(cd "$1" && pwd) || exit 1
CORR_WT=$(cd "$2" && pwd) || exit 1
CONJUNTO="$MAIN_WT/$4"
[ -f "$CONJUNTO" ] && [ -f "$CONJUNTO.sha256" ] || { echo "CONJUNTO OU .sha256 AUSENTE: $CONJUNTO"; exit 13; }
mkdir -p "$3" || exit 1
SAIDA=$(cd "$3" && pwd) || exit 1
shift 4
FORA=()
for id in "$@"; do FORA+=(--fora-da-decisao "$id"); done
# Id fora do conjunto quebra aqui, antes da rodada, e não no pontuar, uma hora depois.
if [ $# -gt 0 ]; then
  PYTHONUTF8=1 python - "$CONJUNTO" "$@" <<'EOF' || exit 13
import json, sys
ids = {p["id"] for p in json.load(open(sys.argv[1], encoding="utf-8"))["perguntas"]}
fora = [i for i in sys.argv[2:] if i not in ids]
if fora:
    sys.exit("PERGUNTA FORA DA DECISAO QUE NAO ESTA NO CONJUNTO: " + ", ".join(fora))
EOF
fi
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
EMAIL=carlos.rossi@example.com
# O mesmo produtor pelo nome do seed: o e-mail e o CPF ficam cifrados no banco.
NOME_PRODUTOR='Carlos Eduardo Rossi'
SQL_PARIDADE="$MAIN_WT/docs/avaliacao/ceres-452/paridade_produtor.sql"
[ -f "$SQL_PARIDADE" ] || { echo "PARIDADE SEM A CONSULTA: $SQL_PARIDADE"; exit 13; }
PIDS=()

ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256")
ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
[ "$ESPERADO" = "$ATUAL" ] || { echo "CONJUNTO DIFERENTE DO .sha256"; exit 13; }

if ! mkdir "$TRAVA" 2>/dev/null; then
  echo "TRAVA OCUPADA: $(cat "$TRAVA/dono.txt" 2>/dev/null)"
  exit 10
fi
encerrar() {
  # O kill só alcança a subshell que lançou o java (no Git Bash a JVM é processo do Windows): quem
  # encerra as JVMs é o Stop-Process pela porta, logo abaixo. As portas 8452 e 8453 são só desta
  # medição.
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
SENHA=$(python -c "import secrets; print(secrets.token_urlsafe(24))")
export DB_PASSWORD SEED_SENHA="$SENHA" CERES_SENHA="$SENHA"
unset SENHA
# Saída do Python em UTF-8 também no console do Windows (o tee gravava cp1252).
export PYTHONUTF8=1

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
# O SeedDemonstracao é CommandLineRunner: roda depois de o Tomcat aceitar conexões, então o health
# responde antes de o seed terminar. Espera a linha final do seed no log da aplicação.
esperar_seed() {  # nome
  local log="$SAIDA/$1-app.log"
  for _ in $(seq 1 120); do
    grep -q "Demonstracao da banca nao semeada" "$log" && { echo "SEED FALHOU: $1"; exit 15; }
    grep -Eq "Demonstracao da banca (ja )?semeada" "$log" && return 0
    sleep 5
  done
  echo "SEED NAO TERMINOU: $1"; exit 15
}

subir base "$MAIN_WT" 8452 agroconia_452_base
subir correcao "$CORR_WT" 8453 agroconia_452_correcao
esperar 8452
esperar 8453
esperar_seed base
esperar_seed correcao

for db in agroconia_452_base agroconia_452_correcao; do
  docker exec agrocontabil-postgres sh -c \
    "pg_dump -U agrocontabil --data-only -t legislacao_trecho agrocontabil | psql -q -v ON_ERROR_STOP=1 -U agrocontabil -d $db" \
    >/dev/null || { echo "COPIA DA BASE DE LEI FALHOU: $db"; exit 16; }
done

# Paridade que barra: o que a Ceres consulta do produtor da medição (ver o topo do arquivo).
paridade_produtor() {  # banco
  psql_ -d "$1" -At -v nome="$NOME_PRODUTOR" < "$SQL_PARIDADE"
}
# Paridade geral do banco: só registro, não barra.
paridade_geral() {  # banco
  psql_ -d "$1" -Atc "
    select 'trechos', count(*)::text from legislacao_trecho
    union all select 'notas ' || status || ' ' || origem, count(*) || ' ' || coalesce(sum(valor_total_nota),0)
      from nota_fiscal group by status, origem
    union all select 'guias ' || status || ' ' || to_char(data_vencimento,'YYYY-MM'), count(*) || ' ' || sum(valor)
      from guias_fiscais group by status, to_char(data_vencimento,'YYYY-MM')
    union all select 'apuracao ' || periodo || ' ' || status,
        count(*) || ' ' || sum(saldo_ibs) || ' ' || sum(saldo_cbs) || ' ' || sum(total_notas)
      from apuracao_mensal group by periodo, status
    order by 1"
}
for braco in base correcao; do
  paridade_produtor "agroconia_452_$braco" > "$SAIDA/paridade-$braco.txt" \
    || { echo "PARIDADE FALHOU: $braco"; exit 17; }
  grep -qx 'produtor da medicao|1' "$SAIDA/paridade-$braco.txt" \
    || { echo "PRODUTOR DA MEDICAO AUSENTE OU REPETIDO: $braco"; exit 17; }
  paridade_geral "agroconia_452_$braco" > "$SAIDA/paridade-geral-$braco.txt" \
    || { echo "PARIDADE GERAL FALHOU: $braco"; exit 17; }
done
if ! diff -q "$SAIDA/paridade-base.txt" "$SAIDA/paridade-correcao.txt" >/dev/null; then
  echo "BANCOS DIFERENTES ENTRE OS BRACOS (produtor da medicao)"
  diff "$SAIDA/paridade-base.txt" "$SAIDA/paridade-correcao.txt"
  exit 17
fi
if ! diff -q "$SAIDA/paridade-geral-base.txt" "$SAIDA/paridade-geral-correcao.txt" >/dev/null; then
  echo "AVISO: paridade geral difere fora do produtor da medicao (nao barra; arquivos paridade-geral-*)"
  diff "$SAIDA/paridade-geral-base.txt" "$SAIDA/paridade-geral-correcao.txt"
fi

python "$MAIN_WT/docs/avaliacao/ceres-452/executar.py" \
  --braco base http://localhost:8452 "$MAIN_WT" \
  --braco correcao http://localhost:8453 "$CORR_WT" \
  --email "$EMAIL" --conjunto "$CONJUNTO" --saida-dir "$SAIDA" 2>&1 | tee "$SAIDA/executor.log"
STATUS=${PIPESTATUS[0]}
[ "$STATUS" = 0 ] || { echo "EXECUTOR SAIU COM $STATUS"; exit 18; }

P="$MAIN_WT/docs/avaliacao/ceres-452/pontuar.py"
python "$P" pontuar --perguntas "$CONJUNTO" --execucoes "$SAIDA/base.jsonl" --saida "$SAIDA/resumo-base.json" \
  ${FORA[@]+"${FORA[@]}"}
python "$P" pontuar --perguntas "$CONJUNTO" --execucoes "$SAIDA/correcao.jsonl" --saida "$SAIDA/resumo-correcao.json" \
  ${FORA[@]+"${FORA[@]}"}
python "$P" comparar --base "$SAIDA/resumo-base.json" --correcao "$SAIDA/resumo-correcao.json" \
  | tee "$SAIDA/decisao.json"
echo "FIM"

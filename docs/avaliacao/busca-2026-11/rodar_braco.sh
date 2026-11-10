#!/usr/bin/env bash
# Roda um braço da medição da busca de lei (#436): recall e citações, com a trava do Ollama.
#
#   rodar_braco.sh BRACO REPO PARTICAO DESTINO LOGS
#
# BRACO     A, B, C ou D
# REPO      pasta do checkout com o código do braço
# PARTICAO  "-" (braço A, sem a propriedade) ou desligado | regimes-especificos | ampliada
# DESTINO   pasta das saídas (JSON e metadados)
# LOGS      pasta dos logs do Maven
#
# A senha do banco vai só no ambiente do comando do Maven, lida do .env, nunca ecoada.
# Saída 10: a trava do Ollama é de outra sessão (nada roda). Saída 11: há outra JVM da Ceres, ou
# não deu para conferir. Saída 12: o recall, as citações ou a repetição dos erros falhou (o motivo
# fica nos metadados). Saída 13: o conjunto não confere com o .sha256 ao lado dele (nada roda).
# Os braços de 2026-11-07 rodaram com a versão anterior deste roteiro, que só registrava as falhas
# nos metadados e não conferia o .sha256; nenhuma falha aconteceu e o conjunto conferia.
#
# Os caminhos da trava e do .env são os da máquina em que a medição rodou: em outra máquina, ajuste
# TRAVA e ENV_FILE antes de rodar.
set -u

BRACO=$1
PARTICAO=$3
TRAVA=/c/Users/surik/AppData/Local/Temp/agroconia-ollama-lock
ENV_FILE='/d/Faculdade/Projetos/SuperSonicTeam - ShowRural2026/backend-tcc/.env'
AQUI=$(cd "$(dirname "$0")" && pwd)

# Caminhos absolutos: o roteiro faz "cd" para o REPO antes do Maven, e um caminho relativo passaria a
# apontar para outro lugar no meio do braço.
mkdir -p "$4" "$5" || exit 1
REPO=$(cd "$2" && pwd) || exit 1
DESTINO=$(cd "$4" && pwd) || exit 1
LOGS=$(cd "$5" && pwd) || exit 1
CONJUNTO="$REPO/docs/avaliacao/treino/perguntas-lei-busca-2026-11.json"
META="$DESTINO/$BRACO-metadados.txt"

ESPERADO=$(cut -d' ' -f1 "$CONJUNTO.sha256" 2>/dev/null)
ATUAL=$(sha256sum "$CONJUNTO" | cut -d' ' -f1)
if [ -z "$ESPERADO" ] || [ "$ESPERADO" != "$ATUAL" ]; then
  echo "CONJUNTO DIFERENTE DO .sha256: esperado '$ESPERADO', arquivo '$ATUAL'"
  exit 13
fi
if ! mkdir "$TRAVA" 2>/dev/null; then
  echo "TRAVA OCUPADA: $(cat "$TRAVA/dono.txt" 2>/dev/null)"
  exit 10
fi
trap 'rm -rf "$TRAVA"' EXIT
echo "sessao 436-conjunto-busca (medicao #436, braco $BRACO) $(date '+%Y-%m-%dT%H:%M:%S%z')" > "$TRAVA/dono.txt"

if ! processos=$(powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ForEach-Object { \$_.CommandLine }" 2>/dev/null); then
  echo "NAO DEU PARA CONFERIR AS JVMs (powershell falhou)"
  exit 11
fi
ceres=$(printf '%s\n' "$processos" | grep -Ei 'Diagnostico|spring-boot:run|AgroContabilApplication' || true)
if [ -n "$ceres" ]; then
  echo "OUTRA JVM DA CERES: $ceres"
  exit 11
fi

PROPRIEDADE=()
if [ "$PARTICAO" != "-" ]; then
  PROPRIEDADE=("-Dapp.assistente.rag.particao-caso-especial=$PARTICAO")
fi

{
  echo "braco: $BRACO"
  echo "particao: $PARTICAO"
  echo "sha: $(git -C "$REPO" rev-parse HEAD) ($(git -C "$REPO" rev-parse --abbrev-ref HEAD))"
  echo "conjunto sha256: $(sha256sum "$CONJUNTO" | cut -d' ' -f1)"
  echo "inicio: $(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "ollama list:"
  docker exec agrocontabil-ollama ollama list
  echo "ollama ps (antes):"
  docker exec agrocontabil-ollama ollama ps
  echo "trechos por norma (vigentes):"
  docker exec -e PGOPTIONS='-c default_transaction_read_only=on' agrocontabil-postgres \
    psql -U agrocontabil -d agrocontabil -At -c \
    "select norma, count(*) from legislacao_trecho where vigencia_fim = '9999-12-31' group by norma order by norma"
} > "$META" 2>&1

cd "$REPO" || exit 1

DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' "$ENV_FILE" | tr -d '\r')" AI_READ_TIMEOUT=240s ./mvnw test \
  -Dtest=DiagnosticoRecallLeiManual -Ddiagnostico.perguntas="$CONJUNTO" \
  -Ddiagnostico.saida="$DESTINO/$BRACO-recall.json" -Dspring.docker.compose.enabled=false \
  "${PROPRIEDADE[@]}" > "$LOGS/$BRACO-recall.log" 2>&1
codigo=$?
echo "recall: saida $codigo em $(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$META"
[ $codigo -eq 0 ] || { echo "FALHOU: recall"; exit 12; }

DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' "$ENV_FILE" | tr -d '\r')" AI_READ_TIMEOUT=240s ./mvnw test \
  -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.perguntas="$CONJUNTO" \
  -Ddiagnostico.saida="$DESTINO/$BRACO-citacoes-bruto.json" -Ddiagnostico.repeticoes=3 \
  -Dspring.docker.compose.enabled=false "${PROPRIEDADE[@]}" > "$LOGS/$BRACO-citacoes.log" 2>&1
codigo=$?
echo "citacoes: saida $codigo em $(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$META"
[ $codigo -eq 0 ] || { echo "FALHOU: citacoes"; exit 12; }

ERROS=$(python -X utf8 -I "$AQUI/refazer_erros.py" ids "$DESTINO/$BRACO-citacoes-bruto.json") \
  || { echo "FALHOU: leitura dos erros" | tee -a "$META"; exit 12; }
if [ -n "$ERROS" ]; then
  echo "ERRO_MODELO em: $ERROS (refeitas no fim do braco)" >> "$META"
  DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' "$ENV_FILE" | tr -d '\r')" AI_READ_TIMEOUT=240s ./mvnw test \
    -Dtest=DiagnosticoCitacoesLeiManual -Ddiagnostico.perguntas="$CONJUNTO" \
    -Ddiagnostico.saida="$DESTINO/$BRACO-citacoes-refeitas.json" -Ddiagnostico.repeticoes=3 \
    -Ddiagnostico.ids="$ERROS" -Dspring.docker.compose.enabled=false "${PROPRIEDADE[@]}" \
    > "$LOGS/$BRACO-citacoes-refeitas.log" 2>&1
  codigo=$?
  if [ $codigo -ne 0 ] || ! PERSISTEM=$(python -X utf8 -I "$AQUI/refazer_erros.py" mesclar \
      "$DESTINO/$BRACO-citacoes-bruto.json" "$DESTINO/$BRACO-citacoes-refeitas.json" \
      "$DESTINO/$BRACO-citacoes.json"); then
    echo "FALHOU: repeticao dos erros (Maven saida $codigo)" | tee -a "$META"
    exit 12
  fi
  echo "ERRO_MODELO que persiste: ${PERSISTEM:-nenhum}" >> "$META"
else
  cp "$DESTINO/$BRACO-citacoes-bruto.json" "$DESTINO/$BRACO-citacoes.json"
  echo "ERRO_MODELO: nenhum" >> "$META"
fi

{
  echo "ollama ps (depois):"
  docker exec agrocontabil-ollama ollama ps
  echo "fim: $(date '+%Y-%m-%dT%H:%M:%S%z')"
} >> "$META" 2>&1
echo "BRACO $BRACO CONCLUIDO"

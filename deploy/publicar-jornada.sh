#!/usr/bin/env bash
# Publica um pacote de quadros da jornada 3D da landing (frontend#174) na VPS.
#
#   publicar-jornada.sh <zip> <versao>      ex.: publicar-jornada.sh /tmp/agroconia-jornada-v0.2.zip v0.2
#
# O zip e o da Release do repositorio dos quadros (uma pasta so no topo, com manifesto.json, etapas/
# e parados/). Ele vira /opt/agrocontabil/jornada/<versao>/, que o Caddy serve em /jornada/<versao>/
# (Caddyfile, handle_path /jornada/*). Nao mexe em container: a pasta nova ja aparece no Caddy.
#
# - Versao publicada nao muda: o navegador guarda os arquivos por um ano (Cache-Control immutable),
#   entao o script recusa uma versao que ja existe. Pacote novo = versao nova.
# - Tudo o que estiver em jornada/ e publico: so entram .avif, .webp e o manifesto.json. O LEIA-ME.md
#   do pacote (documento interno) fica de fora; qualquer outro arquivo faz o script parar.
# - O pacote e extraido e conferido FORA de jornada/ (nada sem conferir fica ao alcance do Caddy) e
#   so entra, de uma vez, com tudo certo. Se algo falhar, nada fica publicado.
set -euo pipefail

DIR=/opt/agrocontabil
BASE="$DIR/jornada"
# Teto do pacote aberto: a extracao cai no mesmo disco do banco. A v0.1 tem 80 MB.
TETO_MB="${JORNADA_TETO_MB:-1024}"

zip="${1:-}"
versao="${2:-}"

falha() { echo "publicar-jornada: $*" >&2; exit 1; }

if [[ ! "$versao" =~ ^v[0-9]+\.[0-9]+$ ]]; then
  echo "uso: publicar-jornada.sh <zip> <versao>   (versao como v0.2; recebido: '$versao')" >&2
  exit 2
fi
[[ -f "$zip" ]] || falha "zip nao encontrado: '$zip'"

destino="$BASE/$versao"
[[ ! -e "$destino" ]] || falha "$destino ja existe; pacote novo entra como versao nova"

mkdir -p "$BASE"
[[ -w "$BASE" ]] \
  || falha "sem permissao de escrita em $BASE (criada pelo Docker, do root? sudo chown $(id -un): $BASE)"

# -I: o Python nao le variaveis de ambiente nem a pasta atual. O zipfile nunca extrai mais do que o
# tamanho declarado de cada entrada, entao a soma abaixo limita o que vai ao disco.
python3 -I - "$zip" "$TETO_MB" <<'PY' || falha "zip invalido ou acima do teto de $TETO_MB MB abertos"
import sys
import zipfile

try:
    with zipfile.ZipFile(sys.argv[1]) as pacote:
        aberto = sum(item.file_size for item in pacote.infolist())
except (zipfile.BadZipFile, OSError) as erro:
    sys.exit("nao deu para ler o zip: %s" % erro)
if aberto > int(sys.argv[2]) * 1024 * 1024:
    sys.exit("pacote aberto: %d MB" % (aberto // (1024 * 1024)))
PY

# Ao lado de jornada/, no mesmo disco: o mv final e uma troca de nome, de uma vez.
tmp=$(mktemp -d "$DIR/.jornada-extraindo-XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# O zipfile descarta caminho absoluto e ".." dos nomes: nada sai da pasta temporaria.
python3 -I -m zipfile -e "$zip" "$tmp"

mapfile -t topo < <(find "$tmp" -mindepth 1 -maxdepth 1)
if [[ "${#topo[@]}" -ne 1 || ! -d "${topo[0]}" ]]; then
  falha "o zip deveria ter uma pasta so no topo"
fi
# Nome fixo: o da pasta vem do zip e nao pode virar padrao nos find abaixo.
raiz="$tmp/pacote"
[[ "${topo[0]}" == "$raiz" ]] || mv -T "${topo[0]}" "$raiz"
if [[ ! -f "$raiz/manifesto.json" || ! -d "$raiz/etapas" || ! -d "$raiz/parados" ]]; then
  falha "faltam manifesto.json, etapas/ ou parados/ no pacote"
fi

rm -f "$raiz/LEIA-ME.md"
estranhos=$(find "$raiz" ! -type f ! -type d | wc -l)
[[ "$estranhos" -eq 0 ]] || falha "$estranhos entradas do pacote nao sao arquivo nem pasta (link?)"
fora=$(find "$raiz" -type f ! -name '*.avif' ! -name '*.webp' ! -path "$raiz/manifesto.json" | sed "s|^$raiz/||")
[[ -z "$fora" ]] || falha "arquivos de tipo inesperado no pacote: $(echo "$fora" | head -5 | tr '\n' ' ')"

# O manifesto tem de ser JSON valido e da mesma versao do caminho (v0.2 = "versao": "0.2"). O resto
# do formato e contrato do player, nao deste script.
python3 -I - "$raiz/manifesto.json" "${versao#v}" <<'PY' || falha "manifesto.json invalido ou de outra versao"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as arquivo:
    manifesto = json.load(arquivo)
versao = manifesto.get("versao") if isinstance(manifesto, dict) else None
if str(versao) != sys.argv[2]:
    sys.exit("versao do manifesto: %r; esperada: %r" % (versao, sys.argv[2]))
PY

# O Caddy le a pasta como root, mas ela e do ubuntu: leitura para todos, escrita so do dono.
chmod -R u=rwX,go=rX "$raiz"
# -T: se o destino surgiu no meio (outra execucao), falha em vez de mover o pacote para dentro dele.
mv -T "$raiz" "$destino"

echo "publicado em $destino"
echo "arquivos: $(find "$destino" -type f | wc -l) (avif: $(find "$destino" -name '*.avif' | wc -l), webp: $(find "$destino" -name '*.webp' | wc -l))"
echo "tamanho: $(du -sh "$destino" | cut -f1)"

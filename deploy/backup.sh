#!/usr/bin/env bash
# Backup diario do Postgres da VPS (cron do usuario ubuntu, 03:30 de Brasilia):
#
#   30 3 * * * /opt/agrocontabil/backup.sh >> /opt/agrocontabil/backups/backup.log 2>&1
#
# pg_dump em formato custom (comprimido, restauravel por tabela) em backups/, mantendo os
# BACKUP_RETENCAO_DIAS mais recentes. Fica no mesmo disco: protege de erro humano e de migration
# ruim, nao de perder a VM — para isso existe a politica de backup do volume na Oracle (README).
#
# Restaurar (banco vazio, backend parado):
#   docker compose stop backend
#   docker compose exec -T postgres pg_restore -U agrocontabil -d agrocontabil --clean --if-exists \
#     < backups/agrocontabil-AAAA-MM-DD.dump
#   docker compose start backend
set -euo pipefail

DIR=/opt/agrocontabil
RETENCAO="${BACKUP_RETENCAO_DIAS:-7}"
cd "$DIR"
mkdir -p backups
chmod 700 backups

arquivo="backups/agrocontabil-$(TZ=America/Sao_Paulo date +%F).dump"
parcial="$arquivo.parcial"

# Le usuario/banco do .env (os mesmos do compose); sem senha: o pg_dump roda dentro do container.
usuario=$(grep -E '^DB_USER=' .env | cut -d= -f2-)
banco=$(grep -E '^DB_NAME=' .env | cut -d= -f2-)

docker compose exec -T postgres pg_dump -U "${usuario:-agrocontabil}" -d "${banco:-agrocontabil}" \
  --format=custom --compress=6 > "$parcial"
# Arquivo parcial nunca vira backup: so renomeia depois do pg_dump sair com sucesso.
mv "$parcial" "$arquivo"
chmod 600 "$arquivo"

find backups -name 'agrocontabil-*.dump' -mtime +"$((RETENCAO - 1))" -delete
echo "[backup $(date '+%F %T')] ok: $arquivo ($(du -h "$arquivo" | cut -f1))"

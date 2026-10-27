#!/usr/bin/env bash
# Comando forcado da chave SSH da CI (authorized_keys: command="/opt/agrocontabil/ssh-forcado.sh",...).
# A chave nao abre shell: o que o Actions manda vira SSH_ORIGINAL_COMMAND e so passa se for
# exatamente "deploy <backend|frontend> <tag>". Qualquer outra coisa e recusada.
set -euo pipefail

read -r -a partes <<< "${SSH_ORIGINAL_COMMAND:-}"
if [[ ${#partes[@]} -ne 3 || "${partes[0]}" != deploy ]]; then
  echo "recusado: use 'deploy <backend|frontend> <tag>'" >&2
  exit 2
fi
exec /opt/agrocontabil/deploy.sh "${partes[1]}" "${partes[2]}"

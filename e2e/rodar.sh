#!/usr/bin/env bash
# Sobe a stack isolada da suite E2E, roda o Playwright e derruba tudo no fim (issue #321).
#
#   ./rodar.sh                 sobe, roda a suite e derruba (inclusive com falha)
#   ./rodar.sh --manter        nao derruba no fim (para depurar; derrube com ./rodar.sh --derrubar)
#   ./rodar.sh --sem-subir     usa a stack ja no ar (de um --manter)
#   ./rodar.sh --derrubar      so derruba a stack
#   ./rodar.sh -- <args>       repassa o resto ao "playwright test" (ex.: -- tests/uc003-ceres.spec.ts)
#
# Variaveis (README.md): E2E_BACKEND_IMAGE (sem ela, constroi deste checkout), E2E_FRONTEND_IMAGE
# (padrao ghcr.io/supersonicteam/frontend:latest) e E2E_PORTA (padrao 18321).
#
# Nunca toca nos containers de desenvolvimento (agrocontabil-postgres, agrocontabil-ollama) nem na
# VPS: o projeto compose e o agroconia-e2e, com rede e banco proprios.
set -euo pipefail

cd "$(dirname "$0")"

manter=false
subir=true
args_playwright=()
while [ $# -gt 0 ]; do
  case "$1" in
    --manter) manter=true ;;
    --sem-subir) subir=false; manter=true ;;
    --derrubar)
      # O compose exige os segredos so para interpolar o arquivo; derrubar nao usa nenhum deles,
      # entao funciona mesmo sem o e2e/.env.
      E2E_DB_PASSWORD=x E2E_SENHA=x E2E_JWT_SECRET=x E2E_CRIPTO_CHAVE=x \
        docker compose down -v --remove-orphans
      exit 0
      ;;
    --) shift; args_playwright=("$@"); break ;;
    *) echo "Opcao desconhecida: $1" >&2; exit 2 ;;
  esac
  shift
done

# Segredos de TESTE desta stack, gerados uma vez no e2e/.env (fora do git). Nunca os da VPS: o
# banco e descartavel e as contas so existem aqui. O compose le o .env sozinho; o Playwright, pelo
# ambiente exportado abaixo.
if [ ! -f .env ]; then
  node -e '
    const c = require("crypto");
    const u = (n) => c.randomBytes(n).toString("base64url");
    process.stdout.write([
      "# Gerado pelo rodar.sh: segredos de TESTE da stack E2E, nunca os da VPS.",
      `E2E_SENHA=E2e-${u(18)}`,
      `E2E_JWT_SECRET=${u(48)}`,
      `E2E_CRIPTO_CHAVE=${c.randomBytes(32).toString("base64")}`,
      `E2E_DB_PASSWORD=${u(24)}`,
      "",
    ].join("\n"));
  ' > .env
  echo "Segredos de teste gerados em e2e/.env"
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a
export E2E_PORTA="${E2E_PORTA:-18321}"
export E2E_BASE_URL="http://localhost:${E2E_PORTA}"

derrubar() {
  docker compose logs --no-color --timestamps > stack.log 2>&1 || true
  if [ "$manter" = false ]; then
    docker compose down -v --remove-orphans || true
  else
    echo "Stack mantida em ${E2E_BASE_URL} (derrube com ./rodar.sh --derrubar)."
  fi
}

if [ "$subir" = true ]; then
  trap derrubar EXIT
  # Comeca sempre do zero: banco vazio e o seed da banca de novo.
  docker compose down -v --remove-orphans > /dev/null 2>&1 || true
  if [ -z "${E2E_BACKEND_IMAGE:-}" ]; then
    echo "Construindo o backend deste checkout..."
    docker compose build backend
  elif [[ "$E2E_BACKEND_IMAGE" == */* ]]; then
    docker compose pull backend
  elif ! docker image inspect "$E2E_BACKEND_IMAGE" > /dev/null 2>&1; then
    echo "A imagem local $E2E_BACKEND_IMAGE nao existe. Tire o E2E_BACKEND_IMAGE para construir" \
      "deste checkout." >&2
    exit 1
  fi
  # Imagem de registro (com "/") vem sempre na versao mais nova; tag local (sem "/") fica como esta.
  if [[ "${E2E_FRONTEND_IMAGE:-ghcr.io/supersonicteam/frontend:latest}" == */* ]]; then
    if ! docker compose pull frontend; then
      echo "Nao foi possivel baixar a imagem do frontend (privada no GHCR). Faca 'docker login" \
        "ghcr.io' com um token com read:packages, ou construa a imagem local e use" \
        "E2E_FRONTEND_IMAGE (README.md, 'Imagem do frontend')." >&2
      exit 1
    fi
  fi
  echo "Subindo a stack (o seed da banca leva um ou dois minutos)..."
  docker compose up -d --no-build --wait --wait-timeout 600
else
  trap derrubar EXIT
fi

echo "Stack no ar em ${E2E_BASE_URL}. Rodando a suite..."
npx playwright test "${args_playwright[@]+"${args_playwright[@]}"}"

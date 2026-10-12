#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=.env

ensure_env() {
  if [[ -f "$ENV_FILE" ]]; then
    return
  fi
  echo "Creating $ENV_FILE with random secrets"
  {
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
    echo "GARAGE_RPC_SECRET=$(openssl rand -hex 32)"
    echo "GARAGE_ADMIN_TOKEN=$(openssl rand -hex 32)"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
}

up() {
  ensure_env
  docker compose up -d --wait
  dev/garage-init.sh
  echo "Ready. Grafana: http://localhost:3000"
}

down() {
  docker compose down
}

logs() {
  docker compose logs -f "$@"
}

reset() {
  read -r -p "This deletes all local data. Continue? [y/N] " answer
  if [[ "$answer" != "y" ]]; then
    echo "Aborted"
    exit 1
  fi
  docker compose down -v
  rm -f "$ENV_FILE"
}

usage() {
  echo "Usage: scripts/dev.sh {up|down|logs [service]|reset}" >&2
  exit 1
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  logs) shift; logs "$@" ;;
  reset) reset ;;
  *) usage ;;
esac

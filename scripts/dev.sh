#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=.env

ensure_secret() {
  local name=$1 bytes=$2
  if ! grep -q "^${name}=" "$ENV_FILE"; then
    echo "${name}=$(openssl rand -hex "$bytes")" >> "$ENV_FILE"
  fi
}

ensure_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "Creating $ENV_FILE with random secrets"
    touch "$ENV_FILE"
    chmod 600 "$ENV_FILE"
  fi
  ensure_secret POSTGRES_PASSWORD 16
  ensure_secret IDENTITY_DB_PASSWORD 16
  ensure_secret EDGE_SIDECAR_DB_PASSWORD 16
  ensure_secret CONTROL_PLANE_DB_PASSWORD 16
  ensure_secret BROKER_DB_PASSWORD 16
}

set_role_password() {
  local role=$1 password=$2
  docker compose exec -T postgres psql -U triedge -d triedge -v ON_ERROR_STOP=1 -q \
    -v role="$role" -v password="$password" <<'SQL'
ALTER ROLE :"role" PASSWORD :'password';
SQL
}

up() {
  ensure_env
  docker compose up -d --wait
  migrate
  echo "Ready. Grafana: http://localhost:3000"
}

migrate() {
  ensure_env
  docker compose run --rm --build flyway migrate
  set -a
  source "$ENV_FILE"
  set +a
  set_role_password identity_service "$IDENTITY_DB_PASSWORD"
  set_role_password edge_sidecar "$EDGE_SIDECAR_DB_PASSWORD"
  set_role_password control_plane "$CONTROL_PLANE_DB_PASSWORD"
  set_role_password broker "$BROKER_DB_PASSWORD"
}

app() {
  ensure_env
  ./gradlew --quiet :services:identity:bootJar :services:edge-sidecar:bootJar \
    :services:control-plane:bootJar :services:broker:bootJar :services:echo:bootJar
  docker compose --profile app up -d --build
  echo "Waiting for the gateway on http://localhost:8080"
  for _ in $(seq 1 60); do
    if curl -fs -o /dev/null http://localhost:8080/.well-known/jwks.json; then
      echo "Gateway ready: http://localhost:8080"
      return
    fi
    sleep 2
  done
  echo "Gateway did not become ready; see scripts/dev.sh logs" >&2
  exit 1
}

load() {
  docker compose --profile tools run --rm k6
}

down() {
  docker compose --profile app --profile tools down
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
  docker compose --profile app --profile tools down -v
  rm -f "$ENV_FILE"
}

usage() {
  echo "Usage: scripts/dev.sh {up|app|load|down|migrate|logs [service]|reset}" >&2
  exit 1
}

case "${1:-}" in
  up) up ;;
  app) app ;;
  down) down ;;
  migrate) migrate ;;
  logs) shift; logs "$@" ;;
  load) load ;;
  reset) reset ;;
  *) usage ;;
esac

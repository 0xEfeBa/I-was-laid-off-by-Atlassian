#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=.env
KEY_NAME=triedge-dev
BUCKETS=(ci-logs ci-artifacts)

garage() {
  docker compose exec -T -e RUST_LOG=warn garage /garage "$@"
}

if garage layout show | grep -q "Current cluster layout version: 0"; then
  node_id=$(garage node id -q | cut -d@ -f1)
  garage layout assign -z local -c 1G "$node_id" > /dev/null
  garage layout apply --version 1 > /dev/null
fi

for bucket in "${BUCKETS[@]}"; do
  if ! garage bucket info "$bucket" > /dev/null 2>&1; then
    garage bucket create "$bucket" > /dev/null
  fi
done

if ! garage key info "$KEY_NAME" > /dev/null 2>&1; then
  garage key create "$KEY_NAME" > /dev/null
fi

for bucket in "${BUCKETS[@]}"; do
  garage bucket allow --read --write --owner "$bucket" --key "$KEY_NAME" > /dev/null
done

if ! grep -q '^GARAGE_S3_ACCESS_KEY=' "$ENV_FILE"; then
  key_info=$(garage key info --show-secret "$KEY_NAME")
  {
    echo "GARAGE_S3_ACCESS_KEY=$(awk '/^Key ID:/ {print $3}' <<< "$key_info")"
    echo "GARAGE_S3_SECRET_KEY=$(awk '/^Secret key:/ {print $3}' <<< "$key_info")"
  } >> "$ENV_FILE"
fi

echo "Garage ready: ${BUCKETS[*]}"

#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

STATE=.state
PROJECT=tri-edge
REGIONS="eu us ap"
REMOTE_DIR=/opt/tri-edge
SSH_OPTS=(-i "$STATE/id_ed25519" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$STATE/known_hosts"
  -o ConnectTimeout=10 -o ServerAliveInterval=15 -o LogLevel=ERROR)

location() {
  case $1 in eu) echo nbg1 ;; us) echo ash ;; ap) echo sin ;; esac
}

wg_ip() {
  case $1 in eu) echo 10.66.0.1 ;; us) echo 10.66.0.2 ;; ap) echo 10.66.0.3 ;; esac
}

pg_port() {
  case $1 in eu) echo 5431 ;; us) echo 5432 ;; ap) echo 5433 ;; esac
}

tunnel_port() {
  case $1 in eu) echo 15431 ;; us) echo 25431 ;; ap) echo 35431 ;; esac
}

require_token() {
  if [[ -z "${HCLOUD_TOKEN:-}" ]]; then
    echo "Set HCLOUD_TOKEN to a Hetzner Cloud API token first" >&2
    exit 1
  fi
}

public_ip() {
  cat "$STATE/ip-$1"
}

secret() {
  cat "$STATE/secret-$1"
}

remote() {
  local region=$1
  shift
  ssh "${SSH_OPTS[@]}" "root@$(public_ip "$region")" "$@"
}

dc() {
  local region=$1
  shift
  remote "$region" "cd $REMOTE_DIR && docker compose -f deploy/hetzner/region.compose.yaml --env-file region.env $*"
}

psql_on() {
  local cluster=$1 database=$2
  dc "$cluster" "exec -T -e PGPASSWORD=$(secret postgres) db-$cluster psql -h 127.0.0.1 -p $(pg_port "$cluster") -U postgres -d $database -v ON_ERROR_STOP=1 -tA"
}

each() {
  local pids=() region
  for region in $REGIONS; do
    "$@" "$region" &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do
    wait "$pid"
  done
}

server_type() {
  hcloud server-type list -o json | python3 -c '
import json, sys
wanted = set(sys.argv[1:])
best = None
for t in json.load(sys.stdin):
    if t.get("deprecation") or t.get("architecture") != "x86" or t["cores"] < 2 or t["memory"] < 4:
        continue
    prices = {p["location"]: float(p["price_hourly"]["gross"]) for p in t["prices"]}
    retired = {l["name"] for l in t.get("locations", []) if l.get("deprecation")}
    if not wanted <= prices.keys() or wanted & retired:
        continue
    cost = max(prices[l] for l in wanted)
    if best is None or cost < best[0]:
        best = (cost, t["name"])
if best is None:
    sys.exit("No server type with 2+ cores and 4+ GB is sold in all of " + ", ".join(sorted(wanted)))
print(best[1])
' "$(location eu)" "$(location us)" "$(location ap)"
}

firewall_rules() {
  python3 - "$(public_ip eu)" "$(public_ip us)" "$(public_ip ap)" <<'PY'
import json, sys
servers = [ip + "/32" for ip in sys.argv[1:]]
anywhere = ["0.0.0.0/0", "::/0"]
print(json.dumps([
    {"direction": "in", "protocol": "tcp", "port": "22", "source_ips": anywhere},
    {"direction": "in", "protocol": "tcp", "port": "8080", "source_ips": anywhere},
    {"direction": "in", "protocol": "udp", "port": "51820", "source_ips": servers},
    {"direction": "in", "protocol": "icmp", "source_ips": servers},
]))
PY
}

new_secret() {
  test -f "$STATE/secret-$1" || (umask 077 && openssl rand -hex 16 > "$STATE/secret-$1")
}

signing_jwk() {
  openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null | openssl ec -text -noout 2>/dev/null | python3 -c '
import base64, json, re, secrets, sys
text = sys.stdin.read()
def block(name, end):
    part = text.split(name + ":", 1)[1].split(end, 1)[0]
    return bytes.fromhex(re.sub(r"[^0-9a-f]", "", part))
b64 = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
d = block("priv", "pub")[-32:].rjust(32, b"\0")
pub = block("pub", "ASN1 OID")
print(json.dumps({"kty": "EC", "crv": "P-256", "use": "sig", "alg": "ES256", "kid": secrets.token_hex(8),
                  "x": b64(pub[1:33]), "y": b64(pub[33:65]), "d": b64(d)}))
'
}

prepare_state() {
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  test -f "$STATE/id_ed25519" || ssh-keygen -q -t ed25519 -N "" -C "$PROJECT" -f "$STATE/id_ed25519"
  for name in postgres replication identity edge_sidecar broker control_plane; do
    new_secret "$name"
  done
  test -f "$STATE/signing.jwk" || (umask 077 && signing_jwk > "$STATE/signing.jwk")
}

create_servers() {
  local type
  type=$(server_type)
  echo "Creating three $type servers"
  for region in $REGIONS; do
    if ! hcloud server describe "$PROJECT-$region" >/dev/null 2>&1; then
      hcloud server create --name "$PROJECT-$region" --type "$type" --image ubuntu-24.04 \
        --location "$(location "$region")" --ssh-key "$PROJECT" --firewall "$PROJECT" \
        --user-data-from-file cloud-init.yaml --label project="$PROJECT" --label region="$region" >/dev/null &
    fi
  done
  wait
  for region in $REGIONS; do
    hcloud server ip "$PROJECT-$region" > "$STATE/ip-$region"
  done
}

wait_for_server() {
  local region=$1
  for _ in $(seq 1 60); do
    if remote "$region" true 2>/dev/null; then
      break
    fi
    sleep 5
  done
  remote "$region" "cloud-init status --wait >/dev/null"
  echo "$region is up ($(public_ip "$region"))"
}

setup_wireguard() {
  for region in $REGIONS; do
    remote "$region" "umask 077; test -f /etc/wireguard/private.key || wg genkey > /etc/wireguard/private.key; wg pubkey < /etc/wireguard/private.key" \
      > "$STATE/wg-$region.pub"
  done
  for region in $REGIONS; do
    {
      printf '[Interface]\nAddress = %s/24\nListenPort = 51820\nPostUp = wg set %%i private-key /etc/wireguard/private.key\n\n' "$(wg_ip "$region")"
      for peer in $REGIONS; do
        if [[ "$peer" != "$region" ]]; then
          printf '[Peer]\nPublicKey = %s\nEndpoint = %s:51820\nAllowedIPs = %s/32\nPersistentKeepalive = 25\n\n' \
            "$(cat "$STATE/wg-$peer.pub")" "$(public_ip "$peer")" "$(wg_ip "$peer")"
        fi
      done
    } | remote "$region" "umask 077; cat > /etc/wireguard/wg0.conf && systemctl enable wg-quick@wg0 >/dev/null 2>&1 && systemctl restart wg-quick@wg0"
  done
  for peer in us ap; do
    remote eu "ping -c 1 -W 5 $(wg_ip "$peer") >/dev/null" || { echo "WireGuard tunnel to $peer is not up" >&2; exit 1; }
  done
  echo "WireGuard mesh is up"
}

bundle() {
  (cd "$ROOT" && ./gradlew --quiet :services:identity:bootJar :services:edge-sidecar:bootJar \
    :services:broker:bootJar :services:control-plane:bootJar :services:echo:bootJar)
  tar -czf "$STATE/bundle.tgz" -C "$ROOT" docker db deploy/system-tenants envoy/bootstrap.yaml \
    deploy/hetzner/patroni.Dockerfile deploy/hetzner/patroni.yml deploy/hetzner/region.compose.yaml \
    services/identity/build/libs/app.jar services/edge-sidecar/build/libs/app.jar \
    services/broker/build/libs/app.jar services/control-plane/build/libs/app.jar services/echo/build/libs/app.jar
}

distribute() {
  echo "Uploading $(du -h "$STATE/bundle.tgz" | cut -f1) to eu"
  scp -q "${SSH_OPTS[@]}" "$STATE/bundle.tgz" "root@$(public_ip eu):/opt/bundle.tgz"
  remote eu "(timeout 600 python3 -m http.server 8099 --bind $(wg_ip eu) --directory /opt >/dev/null 2>&1 &)"
  sleep 2
  for region in us ap; do
    remote "$region" "curl -fsS -o /opt/bundle.tgz http://$(wg_ip eu):8099/bundle.tgz" &
  done
  wait
  remote eu "pkill -f 'http.server 8099' || true"
  for region in $REGIONS; do
    remote "$region" "rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR && tar -xzf /opt/bundle.tgz -C $REMOTE_DIR"
  done
}

write_env() {
  local region=$1
  {
    echo "REGION=$region"
    echo "SELF=$(wg_ip "$region")"
    echo "EU_IP=$(wg_ip eu)"
    echo "US_IP=$(wg_ip us)"
    echo "AP_IP=$(wg_ip ap)"
    echo "POSTGRES_PASSWORD=$(secret postgres)"
    echo "REPLICATION_PASSWORD=$(secret replication)"
    echo "IDENTITY_DB_PASSWORD=$(secret identity)"
    echo "EDGE_SIDECAR_DB_PASSWORD=$(secret edge_sidecar)"
    echo "BROKER_DB_PASSWORD=$(secret broker)"
    echo "CONTROL_PLANE_DB_PASSWORD=$(secret control_plane)"
    echo "IDENTITY_SIGNING_JWK=$(cat "$STATE/signing.jwk")"
  } | remote "$region" "umask 077; cat > $REMOTE_DIR/region.env"
}

build_images() {
  dc "$1" "build --quiet"
  echo "Images built in $1"
}

start_etcd() {
  dc "$1" "up -d etcd" >/dev/null 2>&1
}

wait_for_etcd() {
  for _ in $(seq 1 60); do
    if dc eu "exec -T etcd etcdctl endpoint health --cluster" >/dev/null 2>&1; then
      echo "etcd cluster is healthy"
      return
    fi
    sleep 2
  done
  echo "etcd cluster did not become healthy" >&2
  exit 1
}

start_home_database() {
  local region=$1
  dc "$region" "up -d db-$region" >/dev/null 2>&1
  for _ in $(seq 1 90); do
    if remote "$region" "curl -fs -o /dev/null http://127.0.0.1:$(rest_port "$region")/primary"; then
      echo "Cluster $region has its leader in $region"
      return
    fi
    sleep 2
  done
  echo "Cluster $region did not elect a leader" >&2
  exit 1
}

rest_port() {
  case $1 in eu) echo 8008 ;; us) echo 8009 ;; ap) echo 8010 ;; esac
}

start_replicas() {
  local region=$1 cluster
  for cluster in $REGIONS; do
    if [[ "$cluster" != "$region" ]]; then
      dc "$region" "up -d db-$cluster" >/dev/null 2>&1
    fi
  done
}

wait_for_replicas() {
  local cluster=$1
  for _ in $(seq 1 120); do
    if [[ "$(remote "$cluster" "curl -fs http://127.0.0.1:$(rest_port "$cluster")/cluster" | python3 -c '
import json, sys
members = json.load(sys.stdin)["members"]
print(sum(1 for m in members if m["role"] != "leader" and m["state"] == "streaming"))
' 2>/dev/null)" == "2" ]]; then
      echo "Cluster $cluster has two streaming replicas"
      return
    fi
    sleep 3
  done
  echo "Cluster $cluster replicas are not streaming" >&2
  exit 1
}

migrate() {
  local cluster=$1 port
  port=$(pg_port "$cluster")
  if [[ "$(psql_on "$cluster" postgres <<< "SELECT count(*) FROM pg_database WHERE datname = 'triedge'")" == "0" ]]; then
    psql_on "$cluster" postgres <<< "CREATE DATABASE triedge" >/dev/null
  fi
  remote "$cluster" "docker build -q -t tri-edge-flyway $REMOTE_DIR/db >/dev/null && docker run --rm --network host tri-edge-flyway \
    -url='jdbc:postgresql://127.0.0.1:$port/triedge' -user=postgres -password=$(secret postgres) migrate" >/dev/null
  psql_on "$cluster" triedge >/dev/null <<SQL
ALTER ROLE identity_service PASSWORD '$(secret identity)';
ALTER ROLE edge_sidecar PASSWORD '$(secret edge_sidecar)';
ALTER ROLE broker PASSWORD '$(secret broker)';
ALTER ROLE control_plane PASSWORD '$(secret control_plane)';
SQL
  echo "Cluster $cluster is migrated"
}

start_apps() {
  dc "$1" "up -d identity edge-sidecar broker control-plane echo envoy" >/dev/null 2>&1
}

wait_for_gateway() {
  local region=$1
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null -H "Host: localhost" "http://$(public_ip "$region"):8080/.well-known/jwks.json"; then
      echo "Gateway in $region: http://$(public_ip "$region"):8080"
      return
    fi
    sleep 3
  done
  echo "Gateway in $region did not come up; see: deploy/hetzner/regions.sh logs $region" >&2
  exit 1
}

up() {
  require_token
  prepare_state
  hcloud ssh-key describe "$PROJECT" >/dev/null 2>&1 \
    || hcloud ssh-key create --name "$PROJECT" --public-key-from-file "$STATE/id_ed25519.pub" --label project="$PROJECT" >/dev/null
  hcloud firewall describe "$PROJECT" >/dev/null 2>&1 \
    || hcloud firewall create --name "$PROJECT" --label project="$PROJECT" >/dev/null
  create_servers
  firewall_rules > "$STATE/firewall.json"
  hcloud firewall replace-rules "$PROJECT" --rules-file "$STATE/firewall.json" >/dev/null
  bundle
  each wait_for_server
  setup_wireguard
  distribute
  each write_env
  each build_images
  each start_etcd
  wait_for_etcd
  each start_home_database
  each start_replicas
  each wait_for_replicas
  for cluster in $REGIONS; do
    migrate "$cluster"
  done
  each start_apps
  each wait_for_gateway
  echo "Ready. Next: deploy/hetzner/regions.sh measure, then test-db or test"
}

measure() {
  require_token
  for peer in us ap; do
    echo "eu -> $peer round trip: $(remote eu "ping -c 5 -q $(wg_ip "$peer") | tail -1 | cut -d/ -f5") ms"
  done
  psql_on eu postgres <<< "CREATE TABLE IF NOT EXISTS bench (id bigint PRIMARY KEY)" >/dev/null
  dc eu "exec -T db-eu sh -c 'cat > /tmp/insert.sql'" <<'SQL'
\set id random(1, 9000000000000000)
INSERT INTO bench VALUES (:id) ON CONFLICT DO NOTHING;
SQL
  printf '\n| %-30s | %-7s | %-12s | %-10s |\n' "commit waits for" "clients" "latency" "commits/s"
  printf '| %-30s | %-7s | %-12s | %-10s |\n' "------------------------------" "-------" "------------" "----------"
  for mode in local on; do
    for clients in 1 16; do
      local out label
      out=$(dc eu "exec -T -e PGPASSWORD=$(secret postgres) -e PGOPTIONS='-c synchronous_commit=$mode' db-eu pgbench -n -h 127.0.0.1 -p 5431 -U postgres -c $clients -j $clients -T 20 -f /tmp/insert.sql postgres")
      label=$([[ $mode == local ]] && echo "eu only" || echo "eu + one other continent")
      printf '| %-30s | %-7s | %-12s | %-10s |\n' "$label" "$clients" \
        "$(grep 'latency average' <<< "$out" | awk '{print $4 " ms"}')" \
        "$(grep '^tps' <<< "$out" | awk '{printf "%.0f", $3}')"
    done
  done
}

close_tunnels() {
  for region in $REGIONS; do
    ssh "${SSH_OPTS[@]}" -S "$STATE/tunnel-$region" -O exit "root@$(public_ip "$region")" >/dev/null 2>&1 || true
  done
}

test_db() {
  require_token
  psql_on eu postgres <<< "CREATE TABLE IF NOT EXISTS writes (id bigint PRIMARY KEY); TRUNCATE writes;" >/dev/null
  trap close_tunnels EXIT
  for region in $REGIONS; do
    ssh "${SSH_OPTS[@]}" -f -N -M -S "$STATE/tunnel-$region" -o ExitOnForwardFailure=yes \
      -L "$(tunnel_port "$region"):127.0.0.1:5431" "root@$(public_ip "$region")"
  done
  (cd "$ROOT" && ./gradlew :tests:region-loss:test --tests '*RemoteRegionLossIT' --rerun \
    -Ptriedge.remote.eu="127.0.0.1:$(tunnel_port eu)" \
    -Ptriedge.remote.us="127.0.0.1:$(tunnel_port us)" \
    -Ptriedge.remote.ap="127.0.0.1:$(tunnel_port ap)" \
    -Ptriedge.remote.password="$(secret postgres)" \
    -Ptriedge.remote.kill="hcloud server poweroff $PROJECT-eu")
  echo "eu is powered off. Run deploy/hetzner/regions.sh heal to bring it back."
}

test_apps() {
  require_token
  (cd "$ROOT/tests/e2e" && EU_URL="http://$(public_ip eu):8080" US_URL="http://$(public_ip us):8080" \
    AP_URL="http://$(public_ip ap):8080" KILL_COMMAND="hcloud server poweroff $PROJECT-eu" \
    pnpm exec playwright test -c playwright.regions.config.ts)
  echo "eu is powered off. Run deploy/hetzner/regions.sh heal to bring it back."
}

heal() {
  require_token
  hcloud server poweron "$PROJECT-eu" >/dev/null
  wait_for_server eu
  for _ in $(seq 1 60); do
    if remote us "curl -fs http://127.0.0.1:8009/cluster" >/dev/null 2>&1 \
      && [[ "$(dc us "exec -T db-eu /opt/patroni/bin/patronictl -c /etc/patroni.yml list -f json" | python3 -c '
import json, sys
print(next((m["State"] for m in json.load(sys.stdin) if m["Member"] == "eu-eu"), ""))')" == "streaming" ]]; then
      break
    fi
    sleep 5
  done
  dc us "exec -T db-eu /opt/patroni/bin/patronictl -c /etc/patroni.yml switchover eu --candidate eu-eu --force" >/dev/null
  wait_for_gateway eu
  status
}

status() {
  require_token
  hcloud server list -l project="$PROJECT"
  local region
  for region in us ap eu; do
    if remote "$region" true 2>/dev/null; then
      for cluster in $REGIONS; do
        dc "$region" "exec -T db-$cluster /opt/patroni/bin/patronictl -c /etc/patroni.yml list" 2>/dev/null | sed -n '1,7p'
      done
      return
    fi
  done
}

logs() {
  require_token
  dc "${1:-eu}" "logs --tail 100 ${2:-}"
}

down() {
  require_token
  for region in $REGIONS; do
    hcloud server delete "$PROJECT-$region" >/dev/null 2>&1 &
  done
  wait
  hcloud firewall delete "$PROJECT" >/dev/null 2>&1 || true
  hcloud ssh-key delete "$PROJECT" >/dev/null 2>&1 || true
  rm -rf "$STATE"
  hcloud server list -l project="$PROJECT"
}

case "${1:-}" in
  up) up ;;
  measure) measure ;;
  test-db) test_db ;;
  test) test_apps ;;
  heal) heal ;;
  status) status ;;
  logs) shift; logs "$@" ;;
  down) down ;;
  *) echo "Usage: deploy/hetzner/regions.sh {up|measure|test-db|test|heal|status|logs [region] [service]|down}" >&2; exit 1 ;;
esac

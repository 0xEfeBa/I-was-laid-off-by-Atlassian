#!/bin/sh
set -eu

ETCDCTL=${ETCDCTL:-etcdctl}
: "${ETCD_ROOT_PASSWORD:?}" "${ETCD_PATRONI_PASSWORD:?}" "${ETCD_IDENTITY_PASSWORD:?}" "${ETCD_BROKER_PASSWORD:?}"
: "${ETCD_CONTROL_PLANE_PASSWORD:?}" "${ETCD_EDGE_SIDECAR_PASSWORD:?}"

if ! status=$($ETCDCTL auth status 2>/dev/null) || printf '%s' "$status" | grep -q 'Status: true'; then
  export ETCDCTL_USER="root:$ETCD_ROOT_PASSWORD"
fi

ctl() {
  $ETCDCTL "$@" >/dev/null
}

user() {
  if ! $ETCDCTL user get "$1" >/dev/null 2>&1; then
    printf '%s\n' "$2" | ctl user add "$1" --interactive=false
  else
    printf '%s\n' "$2" | ctl user passwd "$1" --interactive=false
  fi
}

role() {
  name=$1
  shift
  $ETCDCTL role get "$name" >/dev/null 2>&1 || ctl role add "$name"
  while [ $# -gt 0 ]; do
    ctl role grant-permission "$name" "$1" "$2" --prefix=true
    shift 2
  done
}

user root "$ETCD_ROOT_PASSWORD"
ctl user grant-role root root

role patroni readwrite /triedge/db/
role identity readwrite /triedge/usernames/ readwrite /triedge/emails/
role broker readwrite /triedge/tenant-names/ readwrite /triedge/tenant-hosts/ read /triedge/tenants/ read /triedge/db/
role control-plane readwrite /triedge/tenants/
role edge-sidecar read /triedge/db/

user patroni "$ETCD_PATRONI_PASSWORD"
user identity "$ETCD_IDENTITY_PASSWORD"
user broker "$ETCD_BROKER_PASSWORD"
user control-plane "$ETCD_CONTROL_PLANE_PASSWORD"
user edge-sidecar "$ETCD_EDGE_SIDECAR_PASSWORD"
for name in patroni identity broker control-plane edge-sidecar; do
  ctl user grant-role "$name" "$name"
done

if [ -z "${ETCDCTL_USER:-}" ]; then
  ctl auth enable
fi
echo "etcd authentication is enabled"

#!/bin/sh
set -eu

SOURCE=${TENANT_SOURCE:-10.99.0.1}
ALLOW=${TENANT_ALLOW:-}
SERVERS=${SERVER_IPS:-}
CHAIN=TRIEDGE-TENANT
BLOCKED="0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4"

ip addr replace "$SOURCE/32" dev lo

iptables -w -N "$CHAIN" 2>/dev/null || iptables -w -F "$CHAIN"
for allowed in $ALLOW; do
  iptables -w -A "$CHAIN" -p tcp -d "${allowed%:*}" --dport "${allowed##*:}" -j RETURN
done
for range in $BLOCKED $SERVERS; do
  iptables -w -A "$CHAIN" -d "$range" -j REJECT
done
iptables -w -C OUTPUT -s "$SOURCE" -j "$CHAIN" 2>/dev/null || iptables -w -I OUTPUT -s "$SOURCE" -j "$CHAIN"
iptables -w -t nat -C POSTROUTING -s "$SOURCE" ! -o lo -j MASQUERADE 2>/dev/null \
  || iptables -w -t nat -A POSTROUTING -s "$SOURCE" ! -o lo -j MASQUERADE

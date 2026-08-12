#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
site_address="${1:?public DNS hostname required}"
source_file="${2:-/home/ubuntu/eacl-deploy/infra/caddy/Caddyfile.https}"
[[ "$site_address" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]
test -f "$source_file"
drop_in="$(mktemp)"
cleanup() { rm -f -- "$drop_in"; }
trap cleanup EXIT
printf '[Service]\nEnvironment=EACL_SITE_ADDRESS=%s\n' "$site_address" >"$drop_in"
install -d -o root -g root -m 0755 /etc/systemd/system/caddy.service.d
install -o root -g root -m 0644 "$drop_in" \
  /etc/systemd/system/caddy.service.d/eacl-site.conf
install -o root -g root -m 0644 "$source_file" /etc/caddy/Caddyfile
EACL_SITE_ADDRESS="$site_address" caddy validate --config /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl restart caddy
echo "domain HTTPS configuration enabled"

#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
ssh -i "$ssh_key" -o IdentitiesOnly=yes "ubuntu@$host" \
  'curl --fail --silent --show-error http://127.0.0.1:8088/api/health && sudo systemctl --no-pager --full status eacl-datahike-demo caddy'

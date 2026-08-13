#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
sha="${3:?previous release SHA-256 required}"
[[ "$sha" =~ ^[0-9a-f]{64}$ ]]

ssh -i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new \
  "ubuntu@$host" bash -s -- "$sha" <<'REMOTE'
set -euo pipefail
sha="$1"
release="/opt/eacl-datahike-demo/releases/$sha.jar"
test -f "$release"
echo "$sha  $release" | sha256sum --check
sudo ln -sfn "releases/$sha.jar" /opt/eacl-datahike-demo/current
sudo systemctl restart eacl-datahike-demo
curl --fail --retry 30 --retry-delay 1 --retry-connrefused \
  http://127.0.0.1:8088/api/health >/dev/null
REMOTE

echo "rolled back $host to $sha; S3 data was not deleted"

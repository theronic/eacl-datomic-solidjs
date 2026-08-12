#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
jar="${3:-server/target/eacl-datahike-demo.jar}"
checksum_file="$jar.sha256"

test -f "$ssh_key"
test -f "$jar"
test -f "$checksum_file"
(cd "$(dirname "$jar")" && shasum -a 256 -c "$(basename "$checksum_file")")
sha="$(awk '{print $1}' "$checksum_file")"
[[ "$sha" =~ ^[0-9a-f]{64}$ ]]

ssh_options=(-i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new)
scp "${ssh_options[@]}" "$jar" "ubuntu@$host:/tmp/eacl-datahike-demo-$sha.jar"
ssh "${ssh_options[@]}" "ubuntu@$host" bash -s -- "$sha" <<'REMOTE'
set -euo pipefail
sha="$1"
release="/opt/eacl-datahike-demo/releases/$sha.jar"
sudo install -o root -g root -m 0644 "/tmp/eacl-datahike-demo-$sha.jar" "$release"
echo "$sha  $release" | sha256sum --check
sudo ln -sfn "releases/$sha.jar" /opt/eacl-datahike-demo/current
sudo systemctl enable eacl-datahike-demo
sudo systemctl restart eacl-datahike-demo
for attempt in $(seq 1 300); do
  if curl --fail --silent http://127.0.0.1:8088/api/health >/dev/null; then
    exit 0
  fi
  if [ "$attempt" -eq 300 ]; then
    sudo journalctl -u eacl-datahike-demo -n 100 --no-pager
    exit 1
  fi
  sleep 1
done
REMOTE

echo "deployed $sha to $host"

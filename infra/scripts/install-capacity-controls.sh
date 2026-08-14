#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
test -f "$ssh_key"
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ssh_options=(-i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new)

agent_version=3.3.4851.0
agent_package_version=3.3.4851.0-1
agent_url="https://s3.us-east-1.amazonaws.com/amazon-ssm-us-east-1/${agent_version}/debian_arm64/amazon-ssm-agent.deb"
agent_sha256=74764098aca15d877c5852b1bc3993bc6461a4013afa70de5fa81dbc400df3ed
package="$(mktemp /tmp/amazon-ssm-agent.XXXXXX)"
cleanup() { rm -f -- "$package"; }
trap cleanup EXIT

curl --fail --location --silent --show-error --output "$package" "$agent_url"
echo "$agent_sha256  $package" | shasum --algorithm 256 --check

tar --create --gzip --directory "$repo_root" infra | \
  ssh "${ssh_options[@]}" "ubuntu@$host" \
    'install -d -m 0700 /home/ubuntu/eacl-deploy && tar --extract --gzip --directory /home/ubuntu/eacl-deploy'
scp "${ssh_options[@]}" "$package" "ubuntu@$host:/tmp/amazon-ssm-agent.deb"

ssh "${ssh_options[@]}" "ubuntu@$host" bash -s -- \
  "$agent_sha256" "$agent_package_version" <<'REMOTE'
set -euo pipefail
agent_sha256="$1"
agent_package_version="$2"
package=/tmp/amazon-ssm-agent.deb
cleanup() { rm -f -- "$package"; }
trap cleanup EXIT
echo "$agent_sha256  $package" | sha256sum --check
actual_version="$(dpkg-deb --field "$package" Version)"
test "$actual_version" = "$agent_package_version"
installed_version="$(dpkg-query --show --showformat='${Version}' amazon-ssm-agent 2>/dev/null || true)"
if [ "$installed_version" != "$agent_package_version" ]; then
  if snap list amazon-ssm-agent >/dev/null 2>&1; then
    sudo snap stop amazon-ssm-agent
    sudo snap remove amazon-ssm-agent
  fi
  sudo dpkg --install "$package"
fi

infra=/home/ubuntu/eacl-deploy/infra
sudo install -d -o root -g eacl-datahike-demo -m 0750 /etc/eacl-datahike-demo
sudo install -o root -g root -m 0644 "$infra/caddy/Caddyfile.https" \
  /etc/eacl-datahike-demo/Caddyfile.https
sudo install -o root -g root -m 0644 "$infra/caddy/Caddyfile.capacity" \
  /etc/eacl-datahike-demo/Caddyfile.capacity
sudo install -o root -g root -m 0644 "$infra/caddy/Caddyfile.maintenance" \
  /etc/eacl-datahike-demo/Caddyfile.maintenance
sudo install -o root -g root -m 0755 "$infra/scripts/eacl-capacity-suspend" \
  /usr/local/sbin/eacl-capacity-suspend
sudo install -o root -g root -m 0755 "$infra/scripts/eacl-capacity-resume" \
  /usr/local/sbin/eacl-capacity-resume
sudo install -o root -g root -m 0755 "$infra/scripts/eacl-maintenance-start" \
  /usr/local/sbin/eacl-maintenance-start
sudo install -o root -g root -m 0755 "$infra/scripts/eacl-maintenance-end" \
  /usr/local/sbin/eacl-maintenance-end

site_address="$(systemctl show caddy --property=Environment --value | tr ' ' '\n' | sed -n 's/^EACL_SITE_ADDRESS=//p' | head -n 1)"
[[ "$site_address" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]
sudo env EACL_SITE_ADDRESS="$site_address" caddy validate \
  --config /etc/eacl-datahike-demo/Caddyfile.https
sudo env EACL_SITE_ADDRESS="$site_address" caddy validate \
  --config /etc/eacl-datahike-demo/Caddyfile.capacity
sudo env EACL_SITE_ADDRESS="$site_address" caddy validate \
  --config /etc/eacl-datahike-demo/Caddyfile.maintenance
sudo systemctl enable --now amazon-ssm-agent
sudo systemctl is-active amazon-ssm-agent
amazon-ssm-agent -version
REMOTE

echo "capacity controls and pinned SSM agent installed"

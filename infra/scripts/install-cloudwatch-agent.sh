#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
test -f "$ssh_key"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
config="$repo_root/infra/cloudwatch-agent/config.json"
test -f "$config"

agent_version=1.300071.0b1720
agent_sha256=4f03402798eb4b7492150e80e3f27113c40bbc09cb4b4931c1c4f0fa1b17ff9c
agent_url="https://amazoncloudwatch-agent-us-east-1.s3.us-east-1.amazonaws.com/ubuntu/arm64/${agent_version}/amazon-cloudwatch-agent.deb"
ssh_options=(-i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new)
package="$(mktemp /tmp/amazon-cloudwatch-agent.XXXXXX.deb)"
cleanup() { rm -f -- "$package"; }
trap cleanup EXIT

curl --fail --location --silent --show-error --output "$package" "$agent_url"
echo "$agent_sha256  $package" | shasum -a 256 --check
scp "${ssh_options[@]}" "$config" "ubuntu@$host:/tmp/eacl-cloudwatch-agent.json"
scp "${ssh_options[@]}" "$package" "ubuntu@$host:/tmp/amazon-cloudwatch-agent.deb"
ssh "${ssh_options[@]}" "ubuntu@$host" bash -s -- "$agent_sha256" <<'REMOTE'
set -euo pipefail
agent_sha256="$1"
package=/tmp/amazon-cloudwatch-agent.deb
cleanup() { rm -f -- "$package" /tmp/eacl-cloudwatch-agent.json; }
trap cleanup EXIT

echo "$agent_sha256  $package" | sha256sum --check
sudo dpkg --install "$package"
sudo install -o root -g root -m 0644 /tmp/eacl-cloudwatch-agent.json \
  /opt/aws/amazon-cloudwatch-agent/etc/eacl-datahike.json
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/eacl-datahike.json
sudo systemctl enable amazon-cloudwatch-agent
sudo systemctl is-active amazon-cloudwatch-agent
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent --version
REMOTE

echo "CloudWatch agent installed without application credentials"

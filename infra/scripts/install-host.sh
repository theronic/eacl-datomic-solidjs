#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
bucket="${3:?bucket name required}"
store_id="${4:?stable store UUID required}"
region="${5:?AWS region required}"
test -f "$ssh_key"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]
[[ "$store_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
[[ "$region" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]]
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ssh_options=(-i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new)

ssh "${ssh_options[@]}" "ubuntu@$host" 'cloud-init status --wait'

tar --create --gzip --directory "$repo_root" infra | \
  ssh "${ssh_options[@]}" "ubuntu@$host" 'install -d -m 0700 /home/ubuntu/eacl-deploy && tar --extract --gzip --directory /home/ubuntu/eacl-deploy'
ssh "${ssh_options[@]}" "ubuntu@$host" \
  'sudo bash /home/ubuntu/eacl-deploy/infra/scripts/bootstrap-instance.sh'
ssh "${ssh_options[@]}" "ubuntu@$host" \
  "sudo bash /home/ubuntu/eacl-deploy/infra/scripts/configure-production-env.sh '$bucket' '$store_id' '$region'"

echo "host runtime and stable secret environment installed; application not started"

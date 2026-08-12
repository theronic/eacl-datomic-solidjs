#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
require_stack_config
require_account

find "$repo_root/infra" -type f -name '*.sh' -print0 | while IFS= read -r -d '' file; do
  bash -n "$file"
done

if rg -n --glob '!**/validate-plan-read-only.sh' \
  'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16}|aws_secret_access_key' \
  "$repo_root/infra"; then
  echo "secret or private-key material found" >&2
  exit 1
fi

test "$(rg -c 'Default: t4g.medium' "$repo_root/infra/cloudformation.yaml")" = 1
rg -q 'CPUCredits: standard' "$repo_root/infra/cloudformation.yaml"
rg -q 'Default: 20' "$repo_root/infra/cloudformation.yaml"
if rg -n '/Users/|[0-9]{12}_|EACL_AWS_ACCOUNT=' \
  "$repo_root/infra" --glob '!deployment.env.example' \
  --glob '!scripts/validate-plan-read-only.sh'; then
  echo "environment-specific deployment identifier found" >&2
  exit 1
fi

aws_eacl cloudformation validate-template \
  --template-body "file://$repo_root/infra/cloudformation.yaml" >/dev/null
aws_eacl cloudformation get-template-summary \
  --template-body "file://$repo_root/infra/cloudformation.yaml" \
  --query '{ResourceTypes:ResourceTypes,Parameters:Parameters[].ParameterKey}'

ami="$(aws_eacl ssm get-parameter \
  --name /aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id \
  --query Parameter.Value --output text)"
test "$(aws_eacl ec2 describe-images --image-ids "$ami" --query 'Images[0].Architecture' --output text)" = arm64

bucket_http="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "https://$EACL_BUCKET_NAME.s3.$EACL_AWS_REGION.amazonaws.com/")"
test "$bucket_http" = 404 || {
  echo "bucket name is not presently available (HTTP $bucket_http)" >&2
  exit 1
}

docker run --rm --env EACL_BACKEND=127.0.0.1:8088 \
  --volume "$repo_root/infra/caddy/Caddyfile.http:/etc/caddy/Caddyfile:ro" \
  caddy:2.11.4-alpine caddy validate --config /etc/caddy/Caddyfile

echo "read-only plan validation passed; no AWS resources were created or modified"

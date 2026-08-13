#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_account

secret_id="${1:?Secrets Manager secret ARN or name required}"
aws_eacl secretsmanager put-secret-value \
  --secret-id "$secret_id" \
  --secret-string file:///dev/stdin \
  --query '{ARN:ARN,VersionId:VersionId,VersionStages:VersionStages}'

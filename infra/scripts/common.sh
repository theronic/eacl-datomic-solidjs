#!/usr/bin/env bash
set -euo pipefail

export AWS_PAGER=""

require_value() {
  local name="$1"
  test -n "${!name:-}" || {
    echo "$name must be set (see infra/deployment.env.example)" >&2
    exit 1
  }
}

require_aws_config() {
  require_value EACL_AWS_ACCOUNT
  require_value EACL_AWS_PROFILE
  require_value EACL_AWS_REGION
}

require_stack_config() {
  require_aws_config
  require_value EACL_STACK_NAME
  require_value EACL_BUCKET_NAME
  require_value EACL_STORE_ID
  require_value EACL_AVAILABILITY_ZONE
  require_value EACL_INSTANCE_TYPE
  require_value EACL_SSH_PUBLIC_KEY
}

require_dns_config() {
  require_aws_config
  require_value EACL_HOSTED_ZONE_ID
  require_value EACL_DNS_NAME
  require_value EACL_PREVIOUS_A
}

aws_eacl() {
  aws --profile "$EACL_AWS_PROFILE" --region "$EACL_AWS_REGION" "$@"
}

require_account() {
  require_aws_config
  local account
  account="$(aws_eacl sts get-caller-identity --query Account --output text)"
  test "$account" = "$EACL_AWS_ACCOUNT" || {
    echo "wrong AWS account: $account" >&2
    exit 1
  }
}

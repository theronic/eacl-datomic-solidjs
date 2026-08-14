#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
require_account
require_value EACL_STACK_NAME

cpu_credits="${1:?standard or unlimited required}"
test "$cpu_credits" = standard || test "$cpu_credits" = unlimited

aws_eacl cloudformation deploy \
  --stack-name "$EACL_STACK_NAME" \
  --template-file "$repo_root/infra/cloudformation.yaml" \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides "CpuCredits=$cpu_credits" \
  --tags "Project=$EACL_STACK_NAME"

instance_id="$(aws_eacl cloudformation describe-stacks \
  --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].Outputs[?OutputKey==`InstanceId`].OutputValue | [0]' \
  --output text)"
test "$(aws_eacl ec2 describe-instance-credit-specifications \
  --instance-id "$instance_id" \
  --query 'InstanceCreditSpecifications[0].CpuCredits' --output text)" = \
  "$cpu_credits"
echo "CloudFormation and EC2 report $cpu_credits CPU credits for $instance_id"

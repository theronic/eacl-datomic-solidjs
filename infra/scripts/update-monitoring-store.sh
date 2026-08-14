#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
require_account

monitoring_stack="${EACL_MONITORING_STACK_NAME:-demo-eacl-datahike-monitoring}"
bucket="${1:?active bucket required}"
capacity_actions="${2:?capacity actions true or false required}"
test "$capacity_actions" = true || test "$capacity_actions" = false
aws_eacl s3api head-bucket --bucket "$bucket"

aws_eacl cloudformation deploy \
  --stack-name "$monitoring_stack" \
  --template-file "$repo_root/infra/monitoring-cloudformation.yaml" \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    "DataBucketName=$bucket" \
    "AlarmActionsEnabled=true" \
    "CapacityActionsEnabled=$capacity_actions"

test "$(aws_eacl cloudformation describe-stacks --stack-name "$monitoring_stack" \
  --query 'Stacks[0].Parameters[?ParameterKey==`DataBucketName`].ParameterValue | [0]' \
  --output text)" = "$bucket"
test "$(aws_eacl cloudformation describe-stacks --stack-name "$monitoring_stack" \
  --query 'Stacks[0].Parameters[?ParameterKey==`CapacityActionsEnabled`].ParameterValue | [0]' \
  --output text)" = "$capacity_actions"

old_dimensions="$(aws_eacl cloudwatch describe-alarms --alarm-name-prefix demo-eacl-datahike \
  --query "MetricAlarms[?contains(to_string(Dimensions), 'BucketName') && !contains(to_string(Dimensions), '$bucket')].AlarmName" \
  --output text)"
test -z "$old_dimensions" || {
  echo "bucket alarms still target an outdated dimension: $old_dimensions" >&2
  exit 1
}
echo "monitoring stack targets $bucket; capacity actions=$capacity_actions"

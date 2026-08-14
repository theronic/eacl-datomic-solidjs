#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_account
require_value EACL_STACK_NAME

old_bucket="${1:?exact old bucket required}"
active_bucket="${2:?exact active bucket required}"
monitoring_stack="${EACL_MONITORING_STACK_NAME:-demo-eacl-datahike-monitoring}"
test "$old_bucket" != "$active_bucket"
test "${EACL_APPROVED_BUCKET_RETIREMENT:-}" = "$old_bucket" || {
  echo "set EACL_APPROVED_BUCKET_RETIREMENT to the exact old bucket" >&2
  exit 1
}
test "$(aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].Outputs[?OutputKey==`BucketName`].OutputValue | [0]' --output text)" = \
  "$active_bucket"
aws_eacl s3api head-bucket --bucket "$old_bucket"
aws_eacl s3api head-bucket --bucket "$active_bucket"

test "$(aws_eacl cloudformation describe-stacks --stack-name "$monitoring_stack" \
  --query 'Stacks[0].Parameters[?ParameterKey==`DataBucketName`].ParameterValue | [0]' \
  --output text)" = "$active_bucket"
old_alarm_refs="$(aws_eacl cloudwatch describe-alarms \
  --alarm-name-prefix demo-eacl-datahike \
  --query "MetricAlarms[?contains(to_string(Dimensions), '$old_bucket')].AlarmName" \
  --output text)"
test -z "$old_alarm_refs" || {
  echo "refusing retirement: alarms still target $old_bucket: $old_alarm_refs" >&2
  exit 1
}
while IFS= read -r dashboard; do
  test -n "$dashboard" || continue
  body="$(aws_eacl cloudwatch get-dashboard --dashboard-name "$dashboard" \
    --query DashboardBody --output text)"
  if grep -Fq -- "$old_bucket" <<<"$body"; then
    echo "refusing retirement: dashboard $dashboard still targets $old_bucket" >&2
    exit 1
  fi
done < <(aws_eacl cloudwatch list-dashboards \
  --dashboard-name-prefix demo-eacl-datahike \
  --query 'DashboardEntries[].DashboardName' --output text | tr '\t' '\n')

aws_eacl s3api delete-bucket-metrics-configuration \
  --bucket "$old_bucket" --id DatahikeStore 2>/dev/null || true
deleted=0
while true; do
  page="$(aws_eacl s3api list-object-versions --bucket "$old_bucket" --max-keys 1000)"
  objects="$(jq -c '{Objects: (([.Versions[]? | {Key,VersionId}] + [.DeleteMarkers[]? | {Key,VersionId}])), Quiet: true}' <<<"$page")"
  count="$(jq '.Objects | length' <<<"$objects")"
  test "$count" -gt 0 || break
  aws_eacl s3api delete-objects --bucket "$old_bucket" --delete "$objects" >/dev/null
  deleted=$((deleted + count))
  printf 'retired versions/delete-markers=%s\n' "$deleted"
done
test "$(aws_eacl s3api list-object-versions --bucket "$old_bucket" \
  --query 'length(Versions || `[]`) + length(DeleteMarkers || `[]`)' --output text)" = 0
aws_eacl s3api delete-bucket --bucket "$old_bucket"
if aws_eacl s3api head-bucket --bucket "$old_bucket" 2>/dev/null; then
  echo "old bucket still exists after delete request: $old_bucket" >&2
  exit 1
fi
echo "permanently deleted $old_bucket after removing $deleted versions/delete markers; no alarm or dashboard targeted it"

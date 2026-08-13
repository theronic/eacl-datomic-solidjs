#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_account

stack="${1:-demo-eacl-datahike-monitoring}"
outputs="$(aws_eacl cloudformation describe-stacks --stack-name "$stack" \
  --query 'Stacks[0].Outputs' --output json)"
topic="$(jq -r '.[] | select(.OutputKey == "AlarmTopicArn") | .OutputValue' <<<"$outputs")"
function_name="$(jq -r '.[] | select(.OutputKey == "NotifierFunctionName") | .OutputValue' <<<"$outputs")"
test -n "$topic"
test -n "$function_name"

instance_id="$(aws_eacl cloudformation describe-stacks --stack-name "$stack" \
  --query 'Stacks[0].Parameters[?ParameterKey==`DemoInstanceId`].ParameterValue | [0]' \
  --output text)"
metric_seen=false
for _ in $(seq 1 20); do
  datapoints="$(aws_eacl cloudwatch get-metric-statistics \
    --namespace CWAgent --metric-name mem_available_percent \
    --dimensions "Name=InstanceId,Value=$instance_id" \
    --start-time "$(date -u -v-10M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '10 minutes ago' +%Y-%m-%dT%H:%M:%SZ)" \
    --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --period 60 --statistics Average --query 'length(Datapoints)' --output text)"
  if [ "$datapoints" -gt 0 ]; then
    metric_seen=true
    break
  fi
  sleep 15
done
test "$metric_seen" = true || { echo "memory metric did not arrive" >&2; exit 1; }

alarm_name=demo-eacl-datahike-high-cpu
reason="Operator-approved end-to-end Telegram alarm test"
delivery_start_ms="$(($(date +%s) * 1000))"
aws_eacl cloudwatch set-alarm-state --alarm-name "$alarm_name" \
  --state-value ALARM --state-reason "$reason"

alarm_delivered=false
for _ in $(seq 1 20); do
  deliveries="$(aws_eacl logs filter-log-events \
    --log-group-name "/aws/lambda/$function_name" \
    --start-time "$delivery_start_ms" \
    --no-paginate \
    --filter-pattern '"delivered telegram notification"' \
    --query 'length(events)' --output text)"
  if [ "$deliveries" -ge 1 ]; then
    alarm_delivered=true
    break
  fi
  sleep 5
done
test "$alarm_delivered" = true || {
  echo "Telegram notifier did not confirm ALARM delivery" >&2
  exit 1
}
aws_eacl cloudwatch set-alarm-state --alarm-name "$alarm_name" \
  --state-value OK --state-reason "End-to-end test completed"

recovery_delivered=false
for _ in $(seq 1 20); do
  deliveries="$(aws_eacl logs filter-log-events \
    --log-group-name "/aws/lambda/$function_name" \
    --start-time "$delivery_start_ms" \
    --no-paginate \
    --filter-pattern '"delivered telegram notification"' \
    --query 'length(events)' --output text)"
  if [ "$deliveries" -ge 2 ]; then
    recovery_delivered=true
    break
  fi
  sleep 5
done
test "$recovery_delivered" = true || {
  echo "Telegram notifier did not confirm recovery delivery" >&2
  exit 1
}

aws_eacl cloudwatch describe-alarms \
  --alarm-name-prefix demo-eacl-datahike- \
  --query 'MetricAlarms[].{Alarm:AlarmName,State:StateValue,Actions:ActionsEnabled}'
echo "memory telemetry plus ALARM and recovery Telegram delivery verified"

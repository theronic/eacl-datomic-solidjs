#!/usr/bin/env bash
set -euo pipefail

new_ip="${1:?new Elastic IP required}"
[[ "$new_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_dns_config
require_account
test "${EACL_DNS_MUTATION_APPROVED:-}" = "${EACL_DNS_NAME}:${new_ip}" || {
  echo "explicit DNS publication approval flag is missing" >&2
  exit 1
}
current_a="$(aws route53 list-resource-record-sets --profile "$EACL_AWS_PROFILE" \
  --hosted-zone-id "$EACL_HOSTED_ZONE_ID" \
  --query "ResourceRecordSets[?Name=='$EACL_DNS_NAME' && Type=='A'].ResourceRecords[].Value" \
  --output text)"
test "$current_a" = "$EACL_PREVIOUS_A" || {
  echo "current A record $current_a differs from captured value $EACL_PREVIOUS_A" >&2
  exit 1
}

change_batch="$(mktemp)"
cleanup() { rm -f -- "$change_batch"; }
trap cleanup EXIT
jq -n --arg name "$EACL_DNS_NAME" --arg value "$new_ip" \
  '{Comment:"Publish EACL Datahike after pre-cutover acceptance",
    Changes:[{Action:"UPSERT",ResourceRecordSet:{Name:$name,Type:"A",TTL:300,
      ResourceRecords:[{Value:$value}]}}]}' >"$change_batch"
aws route53 change-resource-record-sets --profile "$EACL_AWS_PROFILE" \
  --hosted-zone-id "$EACL_HOSTED_ZONE_ID" \
  --change-batch "file://$change_batch"

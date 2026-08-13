#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_aws_config
require_account

echo "identity"
aws_eacl sts get-caller-identity
echo "$EACL_AWS_REGION VPCs"
aws_eacl ec2 describe-vpcs --query 'Vpcs[].{VpcId:VpcId,Cidr:CidrBlock,Default:IsDefault}'
echo "$EACL_AWS_REGION instances"
aws_eacl ec2 describe-instances --filters Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].{Id:InstanceId,Type:InstanceType,State:State.Name,Name:Tags[?Key==`Name`]|[0].Value}'
echo "existing key pairs"
aws_eacl ec2 describe-key-pairs --query 'KeyPairs[].{Name:KeyName,Fingerprint:KeyFingerprint}'
if test -n "${EACL_HOSTED_ZONE_ID:-}" && test -n "${EACL_DNS_NAME:-}"; then
  echo "current DNS"
  aws route53 list-resource-record-sets --profile "$EACL_AWS_PROFILE" \
    --hosted-zone-id "$EACL_HOSTED_ZONE_ID" \
    --query "ResourceRecordSets[?Name=='$EACL_DNS_NAME']"
fi

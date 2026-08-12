#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
require_stack_config
require_account
[[ "$EACL_AVAILABILITY_ZONE" == "$EACL_AWS_REGION"? ]]

test "${EACL_AWS_MUTATION_APPROVED:-}" = \
  "${EACL_AWS_ACCOUNT}:${EACL_AWS_REGION}:${EACL_STACK_NAME}" || {
  echo "explicit AWS mutation approval flag is missing" >&2
  exit 1
}

operator_ip="$(curl --fail --silent --show-error https://checkip.amazonaws.com | tr -d '[:space:]')"
[[ "$operator_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
operator_cidr="$operator_ip/32"
test "${EACL_APPROVED_OPERATOR_CIDR:-}" = "$operator_cidr" || {
  echo "current operator CIDR $operator_cidr differs from the approved CIDR" >&2
  exit 1
}
test -f "$EACL_SSH_PUBLIC_KEY"
public_key="$(< "$EACL_SSH_PUBLIC_KEY")"
case "$public_key" in
  ssh-rsa\ *) key_type=rsa ;;
  ssh-ed25519\ *) key_type=ed25519 ;;
  *) echo "SSH public key must be RSA or Ed25519 OpenSSH format" >&2; exit 1 ;;
esac
key_fingerprint="$(ssh-keygen -lf "$EACL_SSH_PUBLIC_KEY" | awk '{print $2}')"
test "${EACL_APPROVED_SSH_FINGERPRINT:-}" = "$key_fingerprint" || {
  echo "SSH key fingerprint differs from the approved fingerprint" >&2
  exit 1
}
ami="$(aws_eacl ssm get-parameter \
  --name /aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id \
  --query Parameter.Value --output text)"
test "${EACL_APPROVED_AMI:-}" = "$ami" || {
  echo "current Ubuntu AMI $ami differs from the approved AMI" >&2
  exit 1
}
template_sha="$(shasum -a 256 "$repo_root/infra/cloudformation.yaml" | awk '{print $1}')"
test "${EACL_APPROVED_TEMPLATE_SHA256:-}" = "$template_sha" || {
  echo "CloudFormation template differs from the approved template" >&2
  exit 1
}
bucket_http="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "https://$EACL_BUCKET_NAME.s3.$EACL_AWS_REGION.amazonaws.com/")"
test "$bucket_http" = 404 || {
  echo "bucket name is no longer available (HTTP $bucket_http)" >&2
  exit 1
}
parameters="$(mktemp /tmp/eacl-stack-parameters.XXXXXX.json)"
cleanup() { rm -f -- "$parameters"; }
trap cleanup EXIT

jq -n --arg cidr "$operator_cidr" --arg key "$public_key" \
  --arg keyType "$key_type" --arg ami "$ami" \
  --arg storeId "$EACL_STORE_ID" --arg bucket "$EACL_BUCKET_NAME" \
  --arg instanceType "$EACL_INSTANCE_TYPE" \
  --arg availabilityZone "$EACL_AVAILABILITY_ZONE" \
  '[{ParameterKey:"OperatorCidr",ParameterValue:$cidr},
    {ParameterKey:"SshPublicKey",ParameterValue:$key},
    {ParameterKey:"SshKeyType",ParameterValue:$keyType},
    {ParameterKey:"UbuntuAmi",ParameterValue:$ami},
    {ParameterKey:"StoreId",ParameterValue:$storeId},
    {ParameterKey:"BucketName",ParameterValue:$bucket},
    {ParameterKey:"InstanceType",ParameterValue:$instanceType},
    {ParameterKey:"AvailabilityZone",ParameterValue:$availabilityZone}]' >"$parameters"

aws_eacl cloudformation create-stack \
  --stack-name "$EACL_STACK_NAME" \
  --template-body "file://$repo_root/infra/cloudformation.yaml" \
  --parameters "file://$parameters" \
  --capabilities CAPABILITY_IAM \
  --on-failure DO_NOTHING \
  --tags "Key=Project,Value=$EACL_STACK_NAME"
aws_eacl cloudformation wait stack-create-complete --stack-name "$EACL_STACK_NAME"
aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].{Status:StackStatus,Outputs:Outputs}'

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
repo_root="$(cd -- "$script_dir/../.." && pwd)"
require_stack_config
require_account

old_bucket="${EACL_RETIRED_BUCKET_NAME:?exact retired bucket name required}"
test "$old_bucket" != "$EACL_BUCKET_NAME"
test "$EACL_CPU_CREDITS" = unlimited || test "$EACL_CPU_CREDITS" = standard
current_bucket="$(aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].Outputs[?OutputKey==`BucketName`].OutputValue | [0]' --output text)"
test "$current_bucket" = "$old_bucket" || {
  echo "stack bucket is $current_bucket, expected exact retired bucket $old_bucket" >&2
  exit 1
}
if aws_eacl s3api head-bucket --bucket "$EACL_BUCKET_NAME" >/dev/null 2>&1; then
  echo "replacement bucket already exists; refusing an ambiguous replacement" >&2
  exit 1
fi

aws_eacl cloudformation deploy \
  --stack-name "$EACL_STACK_NAME" \
  --template-file "$repo_root/infra/cloudformation.yaml" \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    "BucketName=$EACL_BUCKET_NAME" \
    "StoreId=$EACL_STORE_ID" \
    "InstanceType=$EACL_INSTANCE_TYPE" \
    "CpuCredits=$EACL_CPU_CREDITS" \
  --tags "Project=$EACL_STACK_NAME"

test "$(aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].Outputs[?OutputKey==`BucketName`].OutputValue | [0]' --output text)" = \
  "$EACL_BUCKET_NAME"
test "$(aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].Outputs[?OutputKey==`StoreId`].OutputValue | [0]' --output text)" = \
  "$EACL_STORE_ID"
test "$(aws_eacl s3api get-bucket-versioning --bucket "$EACL_BUCKET_NAME" \
  --query 'Status' --output text)" = None
aws_eacl s3api head-bucket --bucket "$old_bucket"
echo "replacement bucket active in stack; exact retired bucket remains retained"

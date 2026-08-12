#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_value EACL_STACK_NAME
require_account
aws_eacl cloudformation describe-stacks --stack-name "$EACL_STACK_NAME" \
  --query 'Stacks[0].{Status:StackStatus,Outputs:Outputs,Parameters:Parameters}'
aws_eacl cloudformation list-stack-resources --stack-name "$EACL_STACK_NAME" \
  --query 'StackResourceSummaries[].{Type:ResourceType,Logical:LogicalResourceId,Physical:PhysicalResourceId,Status:ResourceStatus}'

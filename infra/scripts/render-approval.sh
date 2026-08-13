#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_stack_config
require_dns_config
require_value EACL_PRICING_LOCATION
require_value EACL_PUBLIC_IPV4_USAGE_TYPE
require_account
repo_root="$(cd -- "$script_dir/../.." && pwd)"
pricing="$($script_dir/pricing-plan.sh)"
fixed_monthly="$(jq -r '.monthly.fixedSubtotal | (.*100 | round)/100' <<<"$pricing")"
steady_monthly="$(jq -r '.monthly.measuredSubtotal | (.*100 | round)/100' <<<"$pricing")"
first_month="$(jq -r '.firstMonthMeasured | (.*100 | round)/100' <<<"$pricing")"
operator_ip="$(curl --fail --silent --show-error https://checkip.amazonaws.com | tr -d '[:space:]')"
test -f "$EACL_SSH_PUBLIC_KEY"
key_fingerprint="$(ssh-keygen -lf "$EACL_SSH_PUBLIC_KEY" | awk '{print $2}')"
ami="$(aws_eacl ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id --query Parameter.Value --output text)"
template_sha="$(shasum -a 256 "$repo_root/infra/cloudformation.yaml" | awk '{print $1}')"
current_a="$(aws route53 list-resource-record-sets --profile "$EACL_AWS_PROFILE" \
  --hosted-zone-id "$EACL_HOSTED_ZONE_ID" \
  --query "ResourceRecordSets[?Name=='$EACL_DNS_NAME' && Type=='A'].ResourceRecords[].Value" --output text)"

cat <<EOF
# AWS approval package

- Account/profile/region: $EACL_AWS_ACCOUNT / $EACL_AWS_PROFILE / $EACL_AWS_REGION
- Stack: $EACL_STACK_NAME
- Instance: $EACL_INSTANCE_TYPE arm64, standard credits
- Approved AMI candidate: $ami
- CloudFormation template SHA-256: $template_sha
- Root: encrypted 20 GiB gp3
- VPC/subnet: isolated 10.80.0.0/16 / public 10.80.1.0/24 in $EACL_AVAILABILITY_ZONE
- Bucket: $EACL_BUCKET_NAME (private, SSE-S3, versioned, seven-day noncurrent expiry, retained)
- Store ID: $EACL_STORE_ID
- IAM: dedicated-bucket ListBucket; object access only ${EACL_STORE_ID}_* (including ${EACL_STORE_ID}_.konserve-metadata)
- SSH: approved CIDR ${EACL_OPERATOR_CIDR:-${operator_ip}/32} using $EACL_SSH_PUBLIC_KEY ($key_fingerprint)
- Public ports: 80/443; SSH 22 from approved CIDR ${EACL_OPERATOR_CIDR:-${operator_ip}/32}; 8088/7888 have no ingress
- Route53: $EACL_DNS_NAME currently $current_a
- Fixed monthly subtotal: \$$fixed_monthly
- Measured steady monthly subtotal including extrapolated S3: \$$steady_monthly
- Measured first month including one-million seed PUTs: \$$first_month
- Variable exclusions: DNS queries, actual GET/LIST requests, internet egress, tax, and deviations from linear storage amplification
- TLS: same-instance Caddy with free public ACME; no ALB and no paid ACM exportable certificate
- Permanent-size gate: clean-JVM post-seed reads on t4g.medium; any permanent or temporary resize returns for explicit approval
- Smaller alternative: t4g.small, 2 GiB, rejected because it cannot safely host the configured 3 GiB JVM plus the OS
- Larger alternatives: none pre-approved; any temporary loading or permanent read resize requires a newly rendered cost and explicit operator approval

Template: $repo_root/infra/cloudformation.yaml
EOF

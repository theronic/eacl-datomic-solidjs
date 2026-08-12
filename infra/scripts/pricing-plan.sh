#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_aws_config
require_value EACL_INSTANCE_TYPE
require_value EACL_PRICING_LOCATION
require_value EACL_PUBLIC_IPV4_USAGE_TYPE
require_account

ec2_hourly="$(aws_eacl pricing get-products --service-code AmazonEC2 \
  --filters Type=TERM_MATCH,Field=instanceType,Value="$EACL_INSTANCE_TYPE" \
  Type=TERM_MATCH,Field=location,Value="$EACL_PRICING_LOCATION" \
  Type=TERM_MATCH,Field=operatingSystem,Value=Linux \
  Type=TERM_MATCH,Field=tenancy,Value=Shared \
  Type=TERM_MATCH,Field=preInstalledSw,Value=NA \
  Type=TERM_MATCH,Field=capacitystatus,Value=Used --max-results 100 \
  --output json | jq -r '[.PriceList[]|fromjson|.terms.OnDemand[]?.priceDimensions[]?|select(.unit=="Hrs")|.pricePerUnit.USD|tonumber]|unique|.[0]')"
gp3_gb_month="$(aws_eacl pricing get-products --service-code AmazonEC2 \
  --filters Type=TERM_MATCH,Field=location,Value="$EACL_PRICING_LOCATION" \
  Type=TERM_MATCH,Field=volumeApiName,Value=gp3 --max-results 100 \
  --output json | jq -r '[.PriceList[]|fromjson|.terms.OnDemand[]?.priceDimensions[]?|select(.unit=="GB-Mo")|.pricePerUnit.USD|tonumber]|unique|.[0]')"
public_ipv4_hourly="$(aws_eacl pricing get-products --service-code AmazonVPC \
  --filters Type=TERM_MATCH,Field=location,Value="$EACL_PRICING_LOCATION" \
  Type=TERM_MATCH,Field=group,Value=VPCPublicIPv4Address \
  Type=TERM_MATCH,Field=usagetype,Value="$EACL_PUBLIC_IPV4_USAGE_TYPE" \
  --max-results 100 --output json | jq -r \
  '[.PriceList[]|fromjson|.terms.OnDemand[]?.priceDimensions[]?|select(.unit=="Hrs")|.pricePerUnit.USD|tonumber]|unique|.[0]')"
s3_gb_month="$(aws_eacl pricing get-products --service-code AmazonS3 \
  --filters Type=TERM_MATCH,Field=location,Value="$EACL_PRICING_LOCATION" \
  Type=TERM_MATCH,Field=volumeType,Value=Standard \
  --max-results 100 --output json | jq -r \
  '[.PriceList[]|fromjson|.terms.OnDemand[]?.priceDimensions[]?|select(.unit=="GB-Mo" and .beginRange=="0")|.pricePerUnit.USD|tonumber]|unique|.[0]')"
s3_put_per_thousand="$(aws_eacl pricing get-products --service-code AmazonS3 \
  --filters Type=TERM_MATCH,Field=location,Value="$EACL_PRICING_LOCATION" \
  Type=TERM_MATCH,Field=group,Value=S3-API-Tier1 \
  --max-results 100 --output json | jq -r \
  '([.PriceList[]|fromjson|.terms.OnDemand[]?.priceDimensions[]?|select(.unit=="Requests")|.pricePerUnit.USD|tonumber]|unique|.[0]) * 1000')"

for rate in "$ec2_hourly" "$gp3_gb_month" "$public_ipv4_hourly" \
  "$s3_gb_month" "$s3_put_per_thousand"; do
  test "$rate" != null
  test -n "$rate"
done

current_bytes="${EACL_S3_CURRENT_BYTES:-14602949290}"
noncurrent_bytes="${EACL_S3_NONCURRENT_BYTES:-257945173}"
seed_puts="${EACL_SEED_PUTS:-1093050}"
[[ "$current_bytes" =~ ^[0-9]+$ ]]
[[ "$noncurrent_bytes" =~ ^[0-9]+$ ]]
[[ "$seed_puts" =~ ^[0-9]+$ ]]

jq -n \
  --argjson hours 730 \
  --argjson ec2Hourly "$ec2_hourly" \
  --argjson gp3GbMonth "$gp3_gb_month" \
  --argjson publicIpv4Hourly "$public_ipv4_hourly" \
  --argjson ebsGiB 20 \
  --argjson s3GbMonth "$s3_gb_month" \
  --argjson s3PutPerThousand "$s3_put_per_thousand" \
  --argjson currentBytes "$current_bytes" \
  --argjson noncurrentBytes "$noncurrent_bytes" \
  --argjson seedPuts "$seed_puts" \
  '{rates:{ec2Hourly:$ec2Hourly,gp3GbMonth:$gp3GbMonth,
            publicIpv4Hourly:$publicIpv4Hourly,s3GbMonth:$s3GbMonth,
            s3PutPerThousand:$s3PutPerThousand},
    assumptions:{hours:$hours,ebsGiB:$ebsGiB,currentBytes:$currentBytes,
                 sevenDayNoncurrentBytes:$noncurrentBytes,seedPuts:$seedPuts},
    monthly:{ec2:($ec2Hourly*$hours),ebs:($gp3GbMonth*$ebsGiB),
             publicIpv4:($publicIpv4Hourly*$hours),
             measuredS3Storage:((($currentBytes+$noncurrentBytes)/1000000000)*$s3GbMonth)},
    once:{measuredMillionSeedPuts:(($seedPuts/1000)*$s3PutPerThousand)}}
   | .monthly.fixedSubtotal=(.monthly.ec2+.monthly.ebs+.monthly.publicIpv4)
   | .monthly.measuredSubtotal=(.monthly.fixedSubtotal+.monthly.measuredS3Storage)
   | .firstMonthMeasured=(.monthly.measuredSubtotal+.once.measuredMillionSeedPuts)'

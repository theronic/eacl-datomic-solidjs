#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/common.sh"
require_account

bucket="${1:?bucket required}"
prefix="${2:?exact Datahike store prefix required}"
test -n "$prefix"
aws_eacl s3api head-bucket --bucket "$bucket" >/dev/null

read -r objects bytes < <(
  aws_eacl s3 ls "s3://$bucket/$prefix" --recursive |
    awk '{objects += 1; bytes += $3} END {printf "%.0f %.0f\n", objects, bytes}'
)
pages=$(((objects + 999) / 1000))
printf '{"bucket":"%s","prefix":"%s","objects":%s,"bytes":%s,"listRequests":%s}\n' \
  "$bucket" "$prefix" "$objects" "$bytes" "$pages"

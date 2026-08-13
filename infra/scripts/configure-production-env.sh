#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
bucket="${1:?bucket name required}"
store_id="${2:?stable store UUID required}"
region="${3:?AWS region required}"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]
[[ "$store_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
[[ "$region" =~ ^[a-z]{2}(-gov)?-[a-z]+-[0-9]+$ ]]

security_key="$(openssl rand -hex 32)"
admin_token="$(openssl rand -hex 32)"
target=/etc/eacl-datahike-demo/eacl-datahike-demo.env
if [ -e "$target" ]; then
  echo "$target already exists; refusing to rotate stable secrets" >&2
  exit 1
fi
temporary="$(mktemp /etc/eacl-datahike-demo/environment.XXXXXX)"
cleanup() { rm -f -- "$temporary"; }
trap cleanup EXIT

{
  echo EACL_DATAHIKE_DEMO_MODE=production
  echo EACL_DATAHIKE_DEMO_HOST=127.0.0.1
  echo EACL_DATAHIKE_DEMO_PORT=8088
  echo EACL_DATAHIKE_DEMO_STORE_BACKEND=s3
  echo "EACL_DATAHIKE_DEMO_STORE_ID=$store_id"
  echo "EACL_DATAHIKE_DEMO_S3_BUCKET=$bucket"
  echo "EACL_DATAHIKE_DEMO_S3_REGION=$region"
  echo EACL_DATAHIKE_DEMO_S3_PATH_STYLE_ACCESS=false
  echo EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=8192
  echo EACL_DATAHIKE_DEMO_DATAHIKE_SEARCH_CACHE_SIZE=0
  echo "EACL_DATAHIKE_DEMO_SECURITY_KEY=$security_key"
  echo "EACL_DATAHIKE_DEMO_ADMIN_TOKEN=$admin_token"
  echo EACL_DATAHIKE_DEMO_NREPL_PORT=7888
  echo EACL_DATAHIKE_DEMO_REQUEST_TIMEOUT_MS=30000
  echo EACL_DATAHIKE_DEMO_MAX_BODY_BYTES=65536
  echo EACL_DATAHIKE_DEMO_MAX_SEED_SERVERS=1000000
  echo EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=250
  echo EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=0
  echo EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=4
  echo EACL_DATAHIKE_DEMO_MAX_COUNT_LIMIT=1000000
  echo EACL_DATAHIKE_DEMO_MAX_EACL_CONCURRENCY=4
} >"$temporary"
install -o root -g eacl-datahike-demo -m 0600 "$temporary" "$target"

echo "environment installed at $target; secret values were not printed"

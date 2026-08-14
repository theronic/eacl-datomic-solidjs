#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
expected_old_bucket="${1:?expected old bucket required}"
new_bucket="${2:?new bucket required}"
new_store_id="${3:?new store UUID required}"
target=/etc/eacl-datahike-demo/eacl-datahike-demo.env
[[ "$new_bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]
[[ "$new_store_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]
test -f "$target"
test "$(sed -n 's/^EACL_DATAHIKE_DEMO_S3_BUCKET=//p' "$target")" = "$expected_old_bucket"

temporary="$(mktemp /etc/eacl-datahike-demo/environment.XXXXXX)"
cleanup() { rm -f -- "$temporary"; }
trap cleanup EXIT
awk -v bucket="$new_bucket" -v store_id="$new_store_id" '
  /^EACL_DATAHIKE_DEMO_STORE_BACKEND=/ {$0="EACL_DATAHIKE_DEMO_STORE_BACKEND=s3"}
  /^EACL_DATAHIKE_DEMO_STORE_ID=/ {$0="EACL_DATAHIKE_DEMO_STORE_ID=" store_id}
  /^EACL_DATAHIKE_DEMO_S3_BUCKET=/ {$0="EACL_DATAHIKE_DEMO_S3_BUCKET=" bucket}
  /^EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=/ {$0="EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=1000"}
  /^EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=/ {$0="EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=1000"}
  /^EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=/ {$0="EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=0"}
  /^EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=/ {$0="EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=2"}
  /^EACL_DATAHIKE_DEMO_LEGACY_SERVER_COUNT=/ {next}
  {print}
' "$target" >"$temporary"

grep -q '^EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=1000 >>"$temporary"
grep -q '^EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=1000 >>"$temporary"
grep -q '^EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=0 >>"$temporary"
grep -q '^EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=2 >>"$temporary"
grep -q '^EACL_DATAHIKE_DEMO_LMDB_PATH=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_LMDB_PATH=/var/lib/eacl-datahike-demo/lmdb >>"$temporary"
grep -q '^EACL_DATAHIKE_DEMO_LMDB_MAP_SIZE=' "$temporary" || \
  echo EACL_DATAHIKE_DEMO_LMDB_MAP_SIZE=8589934592 >>"$temporary"
install -o root -g eacl-datahike-demo -m 0600 "$temporary" "$target"
echo "production environment switched to the direct-S3 seed profile; secrets were preserved and not printed"

#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
target=/etc/eacl-datahike-demo/eacl-datahike-demo.env
lmdb_path=/var/lib/eacl-datahike-demo/lmdb
test -f "$target"
test "$(sed -n 's/^EACL_DATAHIKE_DEMO_STORE_BACKEND=//p' "$target")" = s3
if test -d "$lmdb_path"; then
  test -z "$(find "$lmdb_path" -mindepth 1 -maxdepth 1 -print -quit)" || {
    echo "$lmdb_path is not empty; refusing to overwrite an existing LMDB tier" >&2
    exit 1
  }
else
  install -d -o eacl-datahike-demo -g eacl-datahike-demo -m 0750 "$lmdb_path"
fi

temporary="$(mktemp /etc/eacl-datahike-demo/environment.XXXXXX)"
cleanup() { rm -f -- "$temporary"; }
trap cleanup EXIT
awk '
  /^EACL_DATAHIKE_DEMO_STORE_BACKEND=/ {$0="EACL_DATAHIKE_DEMO_STORE_BACKEND=s3-lmdb"}
  /^EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=/ {$0="EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=8192"}
  {print}
' "$target" >"$temporary"
install -o root -g eacl-datahike-demo -m 0600 "$temporary" "$target"
echo "production environment switched to tiered LMDB/S3 serving; secrets were not printed"

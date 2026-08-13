#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://127.0.0.1:18080}"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/")"
test "$status" = 308
test "$(curl --silent --output /dev/null --write-out '%{redirect_url}' "$base_url/")" = "$base_url/datahike/"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/datahike")"
test "$status" = 308

curl --fail --silent --show-error "$base_url/datahike/" >"$work_dir/index.html"
rg -q '<div id="root"></div>' "$work_dir/index.html"
asset="$(sed -n 's/.*src="\/datahike\/\([^"]*\)".*/\1/p' "$work_dir/index.html")"
test -n "$asset"
curl --fail --silent --show-error --output /dev/null "$base_url/datahike/$asset"

curl --fail --silent --show-error "$base_url/datahike/api/health" >"$work_dir/health.json"
test "$(jq -r '.data.status' "$work_dir/health.json")" = ready

curl --fail --silent --show-error "$base_url/datahike/a/deep/spa/route" >"$work_dir/spa.html"
rg -q '<div id="root"></div>' "$work_dir/spa.html"

status="$(curl --silent --output "$work_dir/unknown.json" --write-out '%{http_code}' "$base_url/datahike/api/not-real")"
test "$status" = 404
test "$(jq -r '.error.code' "$work_dir/unknown.json")" = api-not-found

test "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base_url/api/health")" = 404
test "$(curl --silent --output /dev/null --write-out '%{http_code}' -X PUT "$base_url/datahike/api/schema")" = 403
test "$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "$base_url/datahike/api/seed")" = 403
test "$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST "$base_url/datahike/api/cache/evict")" = 403

# Alternate spellings must never turn into a successful public mutation. The
# application bearer token remains a second line of defense if proxy routing is
# ever relaxed.
for path in \
  /datahike/api/%73eed \
  /datahike/api/seed/ \
  /datahike//api/seed \
  /datahike/api/SEED; do
  status="$(curl --path-as-is --silent --output /dev/null --write-out '%{http_code}' \
    -X POST "$base_url$path")"
  test "$status" -ge 400
done

echo "reverse proxy contract passed for $base_url"

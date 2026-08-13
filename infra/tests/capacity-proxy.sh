#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-https://demo.eacl.dev}"
page_headers="$(mktemp)"
page_body="$(mktemp)"
api_headers="$(mktemp)"
api_body="$(mktemp)"
cleanup() { rm -f -- "$page_headers" "$page_body" "$api_headers" "$api_body"; }
trap cleanup EXIT

page_status="$(curl --silent --show-error --dump-header "$page_headers" \
  --output "$page_body" --write-out '%{http_code}' "$base_url/datahike/")"
test "$page_status" = 503
grep -Fiq 'content-type: text/html' "$page_headers"
grep -Fiq 'x-eacl-capacity-mode: true' "$page_headers"
grep -Fq 'EACL Demo capacity exceeded' "$page_body"

api_status="$(curl --silent --show-error --dump-header "$api_headers" \
  --output "$api_body" --write-out '%{http_code}' "$base_url/datahike/api/health")"
test "$api_status" = 503
grep -Fiq 'content-type: application/json' "$api_headers"
grep -Fiq 'x-eacl-capacity-mode: true' "$api_headers"
grep -Fq '"code":"capacity-exceeded"' "$api_body"

echo "capacity proxy checks passed"

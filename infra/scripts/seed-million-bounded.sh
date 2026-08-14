#!/usr/bin/env bash
set -euo pipefail

target_total="${1:-1000000}"
ssh_host="${EACL_SSH_HOST:?SSH user and host required}"
ssh_key="${EACL_SSH_KEY:?SSH private-key path required}"
poll_seconds="${EACL_SEED_POLL_SECONDS:-20}"
max_rss_kib="${EACL_SEED_MAX_RSS_KIB:-6500000}"
min_available_kib="${EACL_SEED_MIN_AVAILABLE_KIB:-700000}"

if ! [[ "$target_total" =~ ^[0-9]+$ ]] || (( target_total != 1000000 )); then
  echo "the production target must be exactly 1,000,000 servers" >&2
  exit 1
fi
if ! [[ "$poll_seconds" =~ ^[0-9]+$ ]] || (( poll_seconds < 5 )); then
  echo "poll interval must be at least five seconds" >&2
  exit 1
fi
test -r "$ssh_key"

ssh_eacl() {
  ssh -i "$ssh_key" -o BatchMode=yes -o ConnectTimeout=10 "$ssh_host" "$@"
}

seed_state() {
  ssh_eacl 'curl --max-time 10 --fail --silent --show-error http://127.0.0.1:8088/api/seed'
}

host_probe() {
  ssh_eacl '
    app_pid=$(systemctl show -p MainPID --value eacl-datahike-demo)
    app_rss=$(ps -o rss= -p "$app_pid" | tr -d " ")
    available_kib=$(awk "/MemAvailable:/ {print \$2}" /proc/meminfo)
    app_state=$(systemctl is-active eacl-datahike-demo)
    printf "%s\t%s\t%s\t%s" "$app_pid" "$app_rss" "$available_kib" "$app_state"
  '
}

start_seed() {
  local count="$1"
  ssh_eacl "sudo /bin/bash -c '
    set -a
    source /etc/eacl-datahike-demo/eacl-datahike-demo.env
    curl --fail --silent --show-error -X POST http://127.0.0.1:8088/api/seed \\
      -H \"Content-Type: application/json\" \\
      -H \"Authorization: Bearer \${EACL_DATAHIKE_DEMO_ADMIN_TOKEN}\" \\
      --data \"{\\\"serverCount\\\":$count}\"
  '"
}

safe_halt() {
  local reason="$1"
  echo "seed safety threshold reached: $reason" >&2
  ssh_eacl 'sudo systemctl stop eacl-datahike-demo'
  exit 2
}

trap 'echo "local monitor interrupted; the remote seed was not cancelled" >&2; exit 130' INT TERM

initial_payload="$(seed_state)"
test "$(jq -r '.data.status' <<<"$initial_payload")" = ready
current_total="$(jq -r '.data.totalServers' <<<"$initial_payload")"
test "$current_total" -le "$target_total"
if test "$current_total" = "$target_total"; then
  echo "exact seed target already reached: total=$current_total"
  exit 0
fi

remaining=$((target_total - current_total))
seed_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
start_response="$(start_seed "$remaining")"
test "$(jq -r '.data.status' <<<"$start_response")" = seeding
seed_pid=""

while true; do
  sleep "$poll_seconds"
  sample_stamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  sample_payload="$(seed_state)"
  sample_state="$(jq -r '.data.status' <<<"$sample_payload")"
  completed="$(jq -r '.data.serversCompleted' <<<"$sample_payload")"
  observed_total="$(jq -r '.data.totalServers' <<<"$sample_payload")"
  label="$(jq -r '.data.label // ""' <<<"$sample_payload")"
  IFS=$'\t' read -r app_pid app_rss available_kib app_state <<<"$(host_probe)"
  printf '%s\t%s\t%s/%s\ttotal=%s\tpid=%s\trss_kib=%s\tavailable_kib=%s\t%s\n' \
    "$sample_stamp" "$sample_state" "$completed" "$remaining" "$observed_total" \
    "$app_pid" "$app_rss" "$available_kib" "$label"

  test "$app_state" = active
  if test -z "$seed_pid"; then seed_pid="$app_pid"; else test "$app_pid" = "$seed_pid"; fi
  if (( app_rss > max_rss_kib )); then
    safe_halt "RSS ${app_rss} KiB exceeds ${max_rss_kib} KiB"
  fi
  if (( available_kib < min_available_kib )); then
    safe_halt "available memory ${available_kib} KiB is below ${min_available_kib} KiB"
  fi
  if test "$sample_state" = error; then
    jq -r '.data.error' <<<"$sample_payload" >&2
    exit 1
  fi
  test "$sample_state" = seeding || break
done

test "$sample_state" = ready
test "$observed_total" = "$target_total"
seed_journal="$(ssh_eacl \
  "sudo journalctl -u eacl-datahike-demo --since '$seed_started_at' --no-pager")"
if rg -q 'OutOfMemoryError|oom-kill|Killed process|Background seed job failed|Failed with result' \
  <<<"$seed_journal"; then
  echo "failure signature found in application journal" >&2
  exit 1
fi

echo "exact seed target reached without public probes or forced restarts: total=$observed_total"

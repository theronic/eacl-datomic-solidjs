#!/usr/bin/env bash
set -euo pipefail

target_total="${1:-1000048}"
batch_size="${2:-20000}"
public_base="${EACL_PUBLIC_BASE:?public application base URL required}"
ssh_host="${EACL_SSH_HOST:?SSH user and host required}"
ssh_key="${EACL_SSH_KEY:?SSH private-key path required}"
nrepl_port="${EACL_NREPL_PORT:-17888}"
poll_seconds="${EACL_SEED_POLL_SECONDS:-20}"
max_rss_kib="${EACL_SEED_MAX_RSS_KIB:-3000000}"
min_available_kib="${EACL_SEED_MIN_AVAILABLE_KIB:-700000}"

if ! [[ "$target_total" =~ ^[0-9]+$ ]] || (( target_total < 1000000 )); then
  echo "target total must be an integer of at least 1,000,000" >&2
  exit 1
fi
if ! [[ "$batch_size" =~ ^[0-9]+$ ]] || \
  (( batch_size <= 0 || batch_size > 1000000 )); then
  echo "batch size must be an integer between 1 and 1,000,000" >&2
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
  curl --max-time 10 --fail --silent --show-error "$public_base/api/seed"
}

public_read_probe() {
  curl --max-time 10 --silent --show-error --output /dev/null \
    --write-out '%{http_code}\t%{time_total}' \
    "$public_base/api/bootstrap"
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

wait_for_health() {
  for health_attempt in $(seq 1 90); do
    if ssh_eacl 'curl --fail --silent http://127.0.0.1:8088/api/health >/dev/null'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

restart_and_collect_jvm() {
  ssh_eacl 'sudo systemctl restart eacl-datahike-demo'
  wait_for_health
  clj-nrepl-eval -p "$nrepl_port" \
    '(do (System/gc) (Thread/sleep 3000) :compacted)' >/dev/null
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
  ssh_eacl 'sudo systemctl start eacl-datahike-demo'
  wait_for_health
  exit 2
}

trap 'echo "local monitor interrupted; remote seed state was not changed" >&2; exit 130' INT TERM

initial_payload="$(seed_state)"
initial_status="$(jq -r '.data.status' <<<"$initial_payload")"
test "$initial_status" = ready
current_total="$(jq -r '.data.totalServers' <<<"$initial_payload")"

while (( current_total < target_total )); do
  remaining=$((target_total - current_total))
  if (( remaining < batch_size )); then
    next_batch="$remaining"
  else
    next_batch="$batch_size"
  fi
  expected_total=$((current_total + next_batch))
  batch_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  start_response="$(start_seed "$next_batch")"
  test "$(jq -r '.data.status' <<<"$start_response")" = seeding
  batch_pid=""
  batch_busy_count=0
  batch_timeout_count=0

  while true; do
    sleep "$poll_seconds"
    sample_stamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    sample_payload="$(seed_state)"
    sample_state="$(jq -r '.data.status' <<<"$sample_payload")"
    completed="$(jq -r '.data.serversCompleted' <<<"$sample_payload")"
    observed_total="$(jq -r '.data.totalServers' <<<"$sample_payload")"
    IFS=$'\t' read -r app_pid app_rss available_kib app_state <<<"$(host_probe)"
    IFS=$'\t' read -r read_code read_seconds <<<"$(public_read_probe)"
    printf '%s\t%s\t%s/%s\ttotal=%s\tpid=%s\trss_kib=%s\tavailable_kib=%s\tread=%s/%ss\n' \
      "$sample_stamp" "$sample_state" "$completed" "$next_batch" "$observed_total" \
      "$app_pid" "$app_rss" "$available_kib" "$read_code" "$read_seconds"

    test "$app_state" = active
    if test "$read_code" = 503; then
      batch_busy_count=$((batch_busy_count + 1))
    elif test "$read_code" = 504; then
      batch_timeout_count=$((batch_timeout_count + 1))
    else
      test "$read_code" = 200
    fi
    if test -z "$batch_pid"; then
      batch_pid="$app_pid"
    else
      test "$app_pid" = "$batch_pid"
    fi
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
  test "$observed_total" = "$expected_total"
  for recovery_attempt in $(seq 1 10); do
    recovery_code="$(public_read_probe | cut -f1)"
    test "$recovery_code" = 200 && break
    sleep 2
  done
  test "$recovery_code" = 200
  batch_journal="$(ssh_eacl \
    "sudo journalctl -u eacl-datahike-demo --since '$batch_started_at' --no-pager")"
  if rg -q \
    'OutOfMemoryError|oom-kill|Killed process|Background seed job failed|Failed with result' \
    <<<"$batch_journal"; then
    echo "failure signature found in application journal" >&2
    exit 1
  fi

  restart_and_collect_jvm
  persisted_payload="$(seed_state)"
  persisted_state="$(jq -r '.data.status' <<<"$persisted_payload")"
  persisted_total="$(jq -r '.data.totalServers' <<<"$persisted_payload")"
  test "$persisted_state" = ready
  test "$persisted_total" = "$expected_total"
  current_total="$persisted_total"
  echo "bounded batch persisted: total=$current_total server_busy=$batch_busy_count timeouts=$batch_timeout_count"
done

echo "bounded seed target reached: total=$current_total"

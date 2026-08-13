#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
test "$(uname -m)" = aarch64 || { echo "expected aarch64" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install --yes --no-install-recommends ca-certificates curl openssl

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
work_dir="$(mktemp -d /tmp/eacl-bootstrap.XXXXXX)"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

java_version=26.0.2+10
java_archive=OpenJDK26U-jre_aarch64_linux_hotspot_26.0.2_10.tar.gz
java_url=https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2%2B10/OpenJDK26U-jre_aarch64_linux_hotspot_26.0.2_10.tar.gz
java_sha256=3c689572d2ea7aa3e19db5e5bc4ee41e90b557593d15eefcec179a9b8abfff0e

caddy_version=2.11.4
caddy_archive=caddy_2.11.4_linux_arm64.deb
caddy_url=https://github.com/caddyserver/caddy/releases/download/v2.11.4/caddy_2.11.4_linux_arm64.deb
caddy_sha256=aeab2e38bf77a0162611a1703a5e16c09475b000d41f7edaa9337734d16642fd

curl --fail --location --silent --show-error --output "$work_dir/$java_archive" "$java_url"
echo "$java_sha256  $work_dir/$java_archive" | sha256sum --check
install -d -m 0755 /opt/java
if [ ! -d "/opt/java/temurin-$java_version" ]; then
  tar --extract --gzip --file "$work_dir/$java_archive" --directory "$work_dir"
  extracted="$(find "$work_dir" -mindepth 1 -maxdepth 1 -type d -name 'jdk-*' -print -quit)"
  test -n "$extracted"
  mv "$extracted" "/opt/java/temurin-$java_version"
fi
ln -sfn "temurin-$java_version" /opt/java/temurin-26
/opt/java/temurin-26/bin/java -version

curl --fail --location --silent --show-error --output "$work_dir/$caddy_archive" "$caddy_url"
echo "$caddy_sha256  $work_dir/$caddy_archive" | sha256sum --check
dpkg --install "$work_dir/$caddy_archive"
caddy version | grep -F "$caddy_version"

if ! getent group eacl-datahike-demo >/dev/null; then
  groupadd --system eacl-datahike-demo
fi
if ! id eacl-datahike-demo >/dev/null 2>&1; then
  useradd --system --gid eacl-datahike-demo --home-dir /var/lib/eacl-datahike-demo \
    --shell /usr/sbin/nologin eacl-datahike-demo
fi

install -d -o root -g root -m 0755 /opt/eacl-datahike-demo/releases
install -d -o root -g eacl-datahike-demo -m 0750 /etc/eacl-datahike-demo
install -d -o eacl-datahike-demo -g eacl-datahike-demo -m 0750 /var/lib/eacl-datahike-demo
install -o root -g root -m 0644 "$infra_dir/systemd/eacl-datahike-demo.service" \
  /etc/systemd/system/eacl-datahike-demo.service
install -o root -g root -m 0644 "$infra_dir/ssh/60-eacl-hardening.conf" \
  /etc/ssh/sshd_config.d/60-eacl-hardening.conf
/usr/sbin/sshd -t
systemctl reload ssh
install -o root -g eacl-datahike-demo -m 0640 \
  "$infra_dir/systemd/eacl-datahike-demo.env.example" \
  /etc/eacl-datahike-demo/eacl-datahike-demo.env.example
install -o root -g root -m 0644 "$infra_dir/caddy/Caddyfile.https" \
  /etc/eacl-datahike-demo/Caddyfile.https
install -o root -g root -m 0644 "$infra_dir/caddy/Caddyfile.capacity" \
  /etc/eacl-datahike-demo/Caddyfile.capacity
install -o root -g root -m 0755 "$infra_dir/scripts/eacl-capacity-suspend" \
  /usr/local/sbin/eacl-capacity-suspend
install -o root -g root -m 0755 "$infra_dir/scripts/eacl-capacity-resume" \
  /usr/local/sbin/eacl-capacity-resume
install -o root -g root -m 0644 "$infra_dir/caddy/Caddyfile.http" /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable caddy
systemctl restart caddy

echo "bootstrap complete; create the root-owned environment before starting the app"

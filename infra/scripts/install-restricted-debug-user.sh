#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "run as root" >&2; exit 1; }
read -r public_key
[[ "$public_key" =~ ^ssh-rsa\ [A-Za-z0-9+/]+={0,3}$ ]] || {
  echo "expected one RSA public key on standard input" >&2
  exit 64
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"

if ! id christian >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash christian
fi
passwd --lock christian >/dev/null
install -d -o christian -g christian -m 0700 /home/christian/.ssh
key_file="$(mktemp)"
sudoers_file=""
cleanup() { rm -f -- "$key_file" ${sudoers_file:+"$sudoers_file"}; }
trap cleanup EXIT
printf '%s\n' "$public_key" >"$key_file"
install -o christian -g christian -m 0600 "$key_file" /home/christian/.ssh/authorized_keys
chmod 0700 /home/ubuntu

install -o root -g root -m 0644 "$infra_dir/ssh/70-eacl-debug-user.conf" \
  /etc/ssh/sshd_config.d/70-eacl-debug-user.conf
install -o root -g root -m 0755 "$script_dir/eacl-memory-report" \
  /usr/local/sbin/eacl-memory-report
install -o root -g root -m 0755 "$script_dir/eacl-debug-user-firewall" \
  /usr/local/sbin/eacl-debug-user-firewall
install -o root -g root -m 0644 "$infra_dir/systemd/eacl-debug-user-firewall.service" \
  /etc/systemd/system/eacl-debug-user-firewall.service

sudoers_file="$(mktemp)"
printf '%s\n' \
  'christian ALL=(root) NOPASSWD: /usr/local/sbin/eacl-memory-report' \
  >"$sudoers_file"
install -o root -g root -m 0440 "$sudoers_file" /etc/sudoers.d/eacl-debug-user
visudo --check --file=/etc/sudoers.d/eacl-debug-user
/usr/sbin/sshd -t
systemctl reload ssh
systemctl daemon-reload
systemctl enable --now eacl-debug-user-firewall.service

echo "restricted diagnostic account installed"

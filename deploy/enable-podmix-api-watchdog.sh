#!/usr/bin/env bash
# Install or update only the Podmix API/Bose relay watchdog.
set -euo pipefail

project_dir=/home/debian/projects/podmix
install -d -m 0755 /usr/local/lib/podmix
install -m 0755 "$project_dir/scripts/podmix-api-watchdog.sh" /usr/local/lib/podmix/podmix-api-watchdog.sh
install -m 0644 "$project_dir/deploy/podmix-api-watchdog.service" /etc/systemd/system/podmix-api-watchdog.service
systemctl daemon-reload
systemctl enable --now podmix-api-watchdog.service
systemctl --no-pager --full status podmix-api-watchdog.service

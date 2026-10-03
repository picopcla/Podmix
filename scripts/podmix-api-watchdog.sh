#!/usr/bin/env bash
# Keeps the Podmix relay available through reboot, network loss and DHCP
# renewal.  This script is installed as a root-owned systemd service.
set -euo pipefail

project_dir=/home/debian/projects/podmix
lan_interface=${PODMIX_LAN_INTERFACE:-enp3s0}
interval_seconds=${PODMIX_WATCHDOG_INTERVAL:-10}
state_file=/run/podmix-api-watchdog.ip

lan_ip() {
  ip -o -4 addr show dev "$lan_interface" scope global \
    | awk 'NR == 1 { split($4, address, "/"); print address[1] }'
}

api_is_healthy() {
  curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8099/health >/dev/null
}

reconcile() {
  local current_ip previous_ip
  current_ip=$(lan_ip)
  if [[ -z "$current_ip" ]]; then
    echo "Podmix watchdog: $lan_interface has no IPv4 address yet" >&2
    return
  fi
  previous_ip=$(cat "$state_file" 2>/dev/null || true)
  if [[ "$current_ip" == "$previous_ip" ]] && api_is_healthy; then
    return
  fi

  echo "Podmix watchdog: starting relay on $current_ip" >&2
  export PODMIX_LAN_IP="$current_ip"
  export PODMIX_CAST_LAN_BASE="http://$current_ip:8099/v1/cast"
  docker compose --project-directory "$project_dir" up -d --force-recreate api

  for _ in {1..12}; do
    if api_is_healthy; then
      printf '%s\n' "$current_ip" > "$state_file"
      return
    fi
    sleep 2
  done
  echo "Podmix watchdog: relay did not become healthy" >&2
  return 1
}

while true; do
  reconcile || true
  sleep "$interval_seconds"
done

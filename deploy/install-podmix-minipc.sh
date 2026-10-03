#!/usr/bin/env bash
set -euo pipefail

project_dir=/home/debian/projects/podmix
deploy_dir="$project_dir/deploy"

apt-get update
apt-get install -y docker-compose

cd "$project_dir"
PODMIX_ALLOWED_ORIGINS='https://podmix.mb4.fr,capacitor://localhost' \
PODMIX_CAST_PUBLIC_BASE='https://podmix.mb4.fr/podmix-cast/v1/cast' \
docker compose up -d --build

install -d -m 0755 /usr/local/lib/podmix
install -m 0755 "$project_dir/scripts/podmix-api-watchdog.sh" /usr/local/lib/podmix/podmix-api-watchdog.sh
install -m 0644 "$deploy_dir/podmix-api-watchdog.service" /etc/systemd/system/podmix-api-watchdog.service
systemctl daemon-reload
systemctl enable --now podmix-api-watchdog.service

install -d -m 0755 /var/www/podmix
cp -a \
  "$deploy_dir/download.html" \
  "$deploy_dir/index.html" \
  "$deploy_dir/update.json" \
  "$deploy_dir"/podmix-*.apk \
  "$deploy_dir"/podmix-*.apk.idsig \
  /var/www/podmix/
install -m 0644 "$deploy_dir/podmix-minipc.caddy" /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl reload caddy

install -m 0600 "$deploy_dir/podmix-minipc-cloudflared.yml" /home/debian/.cloudflared/podmix-minipc.yml
chown debian:debian /home/debian/.cloudflared/podmix-minipc.yml
install -m 0644 "$deploy_dir/podmix-minipc-tunnel.service" /etc/systemd/system/podmix-minipc-tunnel.service
systemctl daemon-reload
sudo -u debian cloudflared tunnel route dns --overwrite-dns podmix-minipc podmix.mb4.fr
systemctl enable --now podmix-minipc-tunnel.service

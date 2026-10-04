#!/usr/bin/env bash
# Installe la synchronisation Podmix : le mini PC vient chercher le code serveur
# sur GitHub (lecture seule, aucun port ouvert), sauvegarde, redémarre, teste,
# et revient en arrière si le test échoue.  A lancer avec : sudo bash <ce fichier>
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Lancez avec sudo."; exit 1; }

PROJECT_DIR="${PODMIX_PROJECT_DIR:-/home/debian/projects/podmix}"
BRANCH="${PODMIX_BRANCH:-fix/resume-position-id-normalize}"
REPO_URL="${PODMIX_REPO_URL:-https://github.com/picopcla/Podmix.git}"

command -v docker >/dev/null || { echo "docker introuvable : mode service non géré par ce script."; exit 1; }
[ -f "$PROJECT_DIR/compose.yaml" ] || { echo "$PROJECT_DIR/compose.yaml introuvable. Relancez avec PODMIX_PROJECT_DIR=/le/bon/dossier"; exit 1; }
command -v git >/dev/null || apt-get install -y git
command -v rsync >/dev/null || apt-get install -y rsync

install -d -m 0755 /usr/local/lib/podmix /var/lib/podmix-sync /var/backups/podmix

cat > /usr/local/lib/podmix/podmix-sync.sh <<'EOF'
#!/usr/bin/env bash
set -uo pipefail
PROJECT_DIR="__PROJECT_DIR__"
BRANCH="__BRANCH__"
REPO_URL="__REPO_URL__"
STATE=/var/lib/podmix-sync
CLONE=$STATE/repo
LOG=/var/log/podmix-sync.log
KEEP_BACKUPS=5

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }
health() { curl --fail --silent --max-time 3 http://127.0.0.1:8099/health >/dev/null; }

exec 9>"$STATE/lock"; flock -n 9 || exit 0

if [ ! -d "$CLONE/.git" ]; then
  rm -rf "$CLONE"
  git clone --quiet --depth 50 --branch "$BRANCH" "$REPO_URL" "$CLONE" >>"$LOG" 2>&1 || { log "clone impossible"; exit 0; }
fi
cd "$CLONE" || exit 0
git fetch --quiet origin "$BRANCH" >>"$LOG" 2>&1 || { log "fetch impossible (réseau ?)"; exit 0; }
NEW=$(git rev-parse FETCH_HEAD)
OLD=$(cat "$STATE/deployed" 2>/dev/null || true)
BAD=$(cat "$STATE/bad" 2>/dev/null || true)
[ "$NEW" = "$OLD" ] && exit 0
[ "$NEW" = "$BAD" ] && exit 0          # ce commit a déjà échoué : on ne boucle pas
git checkout --quiet --force "$NEW" >>"$LOG" 2>&1

# Premier passage : on enregistre simplement l'état actuel sans rien redéployer.
if [ -z "$OLD" ]; then echo "$NEW" > "$STATE/deployed"; log "initialisé sur $NEW (rien déployé)"; exit 0; fi

# On ne redéploie que si le serveur ou son image a changé.
if git diff --quiet "$OLD" "$NEW" -- server Dockerfile 2>/dev/null; then
  echo "$NEW" > "$STATE/deployed"; log "$NEW : pas de changement serveur"; exit 0
fi

STAMP=$(date +%Y%m%d-%H%M%S); BK=/var/backups/podmix/$STAMP; mkdir -p "$BK"
log "déploiement $OLD -> $NEW (sauvegarde $BK)"

# Sauvegarde : code serveur actuel + base de données (SQLite).
tar czf "$BK/server-code.tgz" -C "$PROJECT_DIR" --exclude='server/data' server Dockerfile 2>>"$LOG"
CID=$(docker compose --project-directory "$PROJECT_DIR" ps -q api 2>/dev/null | head -n1)
if [ -n "$CID" ]; then
  docker run --rm --volumes-from "$CID" -v "$BK":/backup alpine tar czf /backup/data.tgz -C /app/server data >>"$LOG" 2>&1 \
    || { log "sauvegarde des données impossible : annulé"; exit 0; }
else
  log "conteneur api introuvable : annulé"; exit 0
fi

rebuild() {
  [ -f /run/podmix-api-watchdog.ip ] && export PODMIX_LAN_IP="$(cat /run/podmix-api-watchdog.ip)" \
    && export PODMIX_CAST_LAN_BASE="http://$PODMIX_LAN_IP:8099/v1/cast"
  docker compose --project-directory "$PROJECT_DIR" up -d --build api >>"$LOG" 2>&1
}
wait_healthy() { for _ in $(seq 1 30); do health && return 0; sleep 3; done; return 1; }

rsync -a --delete --exclude='data/' --exclude='__pycache__/' "$CLONE/server/" "$PROJECT_DIR/server/" >>"$LOG" 2>&1
cp -f "$CLONE/Dockerfile" "$PROJECT_DIR/Dockerfile"

if rebuild && wait_healthy; then
  echo "$NEW" > "$STATE/deployed"; log "OK : $NEW en service"
else
  log "ÉCHEC du test santé : retour arrière"
  tar xzf "$BK/server-code.tgz" -C "$PROJECT_DIR" >>"$LOG" 2>&1
  rebuild; wait_healthy && log "retour arrière OK" || log "ATTENTION : retour arrière sans /health, vérifier à la main"
  echo "$NEW" > "$STATE/bad"
fi
ls -1dt /var/backups/podmix/*/ 2>/dev/null | tail -n +$((KEEP_BACKUPS+1)) | xargs -r rm -rf
exit 0
EOF
sed -i "s|__PROJECT_DIR__|$PROJECT_DIR|; s|__BRANCH__|$BRANCH|; s|__REPO_URL__|$REPO_URL|" /usr/local/lib/podmix/podmix-sync.sh
chmod 0755 /usr/local/lib/podmix/podmix-sync.sh

cat > /etc/systemd/system/podmix-sync.service <<'EOF'
[Unit]
Description=Synchronise le serveur Podmix depuis GitHub
After=network-online.target docker.service
[Service]
Type=oneshot
ExecStart=/usr/local/lib/podmix/podmix-sync.sh
EOF
cat > /etc/systemd/system/podmix-sync.timer <<'EOF'
[Unit]
Description=Vérifie GitHub toutes les 5 minutes
[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now podmix-sync.timer
systemctl start podmix-sync.service
echo "Installé. Journal : tail -f /var/log/podmix-sync.log"

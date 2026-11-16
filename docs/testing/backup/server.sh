#!/usr/bin/env bash
# A throwaway hoard-server for the phone backup's emulator run (docs/testing/backup.md): temporary data and backup
# folders, the synthetic NAS folder mounted read-only on another mount, port 127.0.0.1:$PORT (10.0.2.2:$PORT from the
# emulator). Never the owner's deployed server.
#
#   server.sh up <image> <work dir>      # starts it, sets the admin password, adds the NAS folder, prints a pairing link
#   server.sh down <work dir>            # removes the container and the work dir
set -euo pipefail
NAME=hoard-backup-a1-srv
PORT=${PORT:-3994}
PASSWORD=backup-a1-test-password
cmd=$1
case "$cmd" in
up)
  image=$2 work=$3
  mkdir -p "$work/data" "$work/backup"
  chmod 777 "$work/data" "$work/backup"
  docker run -d --name $NAME --cpus 2 --memory 1536m -p 127.0.0.1:$PORT:3071 \
    -v "$work/data:/data" -v "$work/backup:/mnt/backup" -v "$work/files/nas:/mnt/nas:ro" \
    -e LOG_FORMAT=json -e BACKUP_ROOT=/mnt/backup -e NAS_INTERVAL=1m "$image" >/dev/null
  base=http://127.0.0.1:$PORT
  for _ in $(seq 1 100); do curl -fs $base/v1/health >/dev/null 2>&1 && break; sleep 0.3; done
  code=$(docker logs $NAME 2>&1 | grep -o '"setupCode":"[^"]*"' | head -1 | cut -d'"' -f4)
  curl -fs -c "$work/cookies" -H 'X-Hoard-Admin: 1' -H 'Content-Type: application/json' \
    -d "{\"code\":\"$code\",\"password\":\"$PASSWORD\"}" $base/admin/api/setup >/dev/null
  curl -fs -b "$work/cookies" -H 'X-Hoard-Admin: 1' -H 'Content-Type: application/json' \
    -d '{"path":"/mnt/nas"}' $base/admin/api/nas
  echo
  curl -fs -b "$work/cookies" -H 'X-Hoard-Admin: 1' -H 'Content-Type: application/json' \
    -d "{\"url\":\"http://10.0.2.2:$PORT\"}" $base/admin/api/pairings
  echo
  ;;
down)
  docker rm -f $NAME >/dev/null 2>&1 || true
  rm -rf "${2:?}"
  ;;
esac

#!/usr/bin/env bash
# Hand-off burst stress test (docs/testing/handoff-burst.md): a test client app opens hundreds of Hoard's hand-off
# links at once (SMB links on a Samba container, local FileProvider links) while this script watches system_server.
#
# Needs: an emulator (never a phone with Hoard: the debug build replaces the app), Hoard's debug APK and its
# androidTest APK (:native), the :core:smb androidTest APK (the client), docker.
#
#   ANDROID_SERIAL=emulator-5554 docs/testing/tools/handoff-burst.sh <label> [scenario...]
#
# Scenarios: smb (200 opens at once, read + close), smb-hold (200 at once, every descriptor held 5 s),
# smb-retry (smb, retrying busy: as Reveal does), local (200 FileProvider opens), local-hold.
# Results: docs/testing/handoff-burst/<label>-*.txt. BURST_OPENS (200) sets the opens per burst (step up: 50, 100, 200). BURST_COLD=1 deletes Hoard's small-file copies before each run.
set -euo pipefail

LABEL=${1:?label}
shift
SCENARIOS=${*:-smb smb-hold smb-retry local local-hold}
SERIAL=${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator}
OPENS=${BURST_OPENS:-200}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=$ROOT/docs/testing/handoff-burst
WORK=${BURST_WORK:-$(mktemp -d)}
PORT=${BURST_PORT:-4458}
CONTAINER=hoard-smb-burst
APP=dev.hfps.hoard
CLIENT=dev.hfps.hoard.core.smb.test
NATIVE_APKS=$ROOT/android/native/build/outputs/apk
CLIENT_APK=$ROOT/android/core/smb/build/outputs/apk/androidTest/debug/smb-debug-androidTest.apk
mkdir -p "$OUT"

ADB_BIN=$(command -v adb)
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" -s "$SERIAL" "$@"; }

echo "== Samba container on port $PORT"
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  docker build -q -t "$CONTAINER" "$ROOT/docs/testing/xmp-sidecars/samba" >/dev/null
  docker run -d --name "$CONTAINER" --memory 256m --cpus 1 -p "$PORT:445" "$CONTAINER" >/dev/null
  python3 "$ROOT/docs/testing/tools/burst-files.py" "$WORK/files"
  docker exec "$CONTAINER" mkdir -p /srv/photos/burst
  docker cp "$WORK/files/." "$CONTAINER:/srv/photos/burst/"
  docker exec "$CONTAINER" chown -R hoard /srv/photos/burst
fi
FILES=$(docker exec "$CONTAINER" ls /srv/photos/burst | sed 's#^#burst/#' | paste -sd, -)

echo "== Install"
ADB_TIMEOUT=300 adb install -r -t "${HOARD_APK:-$NATIVE_APKS/debug/native-debug.apk}" >/dev/null
ADB_TIMEOUT=300 adb install -r -t "$NATIVE_APKS/androidTest/debug/native-debug-androidTest.apk" >/dev/null
ADB_TIMEOUT=300 adb install -r -t "$CLIENT_APK" >/dev/null

echo "== Register and grant the links"
ADB_TIMEOUT=180 adb shell am instrument -w -r -e hoardHandoffBurst true -e host 10.0.2.2 -e port "$PORT" \
  -e share photos -e files "$FILES" -e client "$CLIENT" -e localCount 60 -e localBytes 1048576 \
  -e class dev.hfps.hoard.app.HandoffBurstSetup "$APP.test/androidx.test.runner.AndroidJUnitRunner" > "$WORK/setup.txt"
SMB_URIS=$(grep -o 'smb[0-9]*=content://[^ ]*' "$WORK/setup.txt" | cut -d= -f2- | tr -d '\r' | paste -sd, -)
LOCAL_URIS=$(grep -o 'local[0-9]*=content://[^ ]*' "$WORK/setup.txt" | cut -d= -f2- | tr -d '\r' | paste -sd, -)
[ -n "$SMB_URIS" ] || { cat "$WORK/setup.txt"; echo "setup failed"; exit 1; }

monitor() { # dumpsys latency every 0.5 s, until the flag file disappears
  local log=$1 flag=$2
  while [ -f "$flag" ]; do
    local t0 t1 rc=0
    t0=$(date +%s%3N)
    ADB_TIMEOUT=90 adb shell dumpsys mount >/dev/null 2>&1 || rc=$?
    t1=$(date +%s%3N)
    echo "$t0 $((t1 - t0)) $rc" >> "$log"
    sleep 0.5
  done
}

run() {
  local name=$1 uris=$2 extra=$3
  local file=$OUT/$LABEL-$name.txt
  echo "== $name"
  adb shell am force-stop "$APP" # a cold start, as when Reveal reopens links after a restart
  # BURST_COLD=1: no small-file copies yet (the first burst after an install or "Revoke all").
  [ "${BURST_COLD:-0}" = 1 ] && adb shell run-as "$APP" rm -rf cache/handoff
  adb logcat -c
  local ss_before; ss_before=$(adb shell pidof system_server | tr -d '\r')
  local vold_before; vold_before=$(adb shell pidof vold | tr -d '\r')
  local flag=$WORK/monitor.$name; touch "$flag"; : > "$WORK/dumpsys.$name"
  monitor "$WORK/dumpsys.$name" "$flag" &
  local mon=$!
  sleep 2
  # shellcheck disable=SC2086
  ADB_TIMEOUT=900 adb shell am instrument -w -r -e hoardHandoffBurstClient true -e label "$LABEL-$name" -e uris "$uris" \
    $extra -e class dev.hfps.hoard.smb.handoff.HandoffBurstClient "$CLIENT/androidx.test.runner.AndroidJUnitRunner" > "$WORK/client.$name" || true
  sleep 5
  rm -f "$flag"; wait "$mon" || true
  local ss_after; ss_after=$(adb shell pidof system_server | tr -d '\r' || true)
  local vold_after; vold_after=$(adb shell pidof vold | tr -d '\r' || true)
  ADB_TIMEOUT=60 adb logcat -d > "$WORK/logcat.$name" || true
  {
    echo "label: $LABEL  scenario: $name  date: $(date -Iseconds)"
    grep -o 'summary=.*' "$WORK/client.$name" | head -1 || echo "client: no summary"; grep -h 'example failure' "$WORK/logcat.$name" | sed 's/^.*HoardBurst: /  /' | head -5 || true
    echo "system_server pid: $ss_before -> $ss_after  vold pid: $vold_before -> $vold_after"
    awk '{n++; s+=$2; if ($2>m) m=$2; if ($3!=0) f++} END {printf "dumpsys mount: %d samples, mean %.0f ms, max %d ms, failed %d\n", n, s/n, m, f}' "$WORK/dumpsys.$name"
    echo "system_server appfuse opens (Watchdog '#openFile might be slow' pauses): $(grep -c 'openFile might be slow' "$WORK/logcat.$name" || true)"
    echo "watchdog kills / blocked checks: $(grep -cE 'WATCHDOG KILLING|Blocked in (handler|monitor)' "$WORK/logcat.$name" || true)"
    echo "Hoard ANRs: $(grep -c 'ANR in dev.hfps.hoard$' "$WORK/logcat.$name" || true)  Hoard crashes: $(grep -c 'AndroidRuntime: Process: dev.hfps.hoard, ' "$WORK/logcat.$name" || true)  $(grep -o 'ForegroundServiceDidNotStartInTimeException' "$WORK/logcat.$name" | head -1)"
    echo "hoard threads after: $(adb shell 'ls /proc/$(pidof '"$APP"')/task 2>/dev/null | wc -l' | tr -d '\r')"
    echo "hoard log lines (HoardHandoff): $(grep -c 'HoardHandoff' "$WORK/logcat.$name" || true)"
    grep -h 'HoardHandoff' "$WORK/logcat.$name" | grep -E 'open x|not readable' | sed 's/^.*HoardHandoff: /  /' | head -8 || true
  } | tee "$file"
}

for s in $SCENARIOS; do
  case $s in
    smb) run smb "$SMB_URIS" "-e opens $OPENS" ;;
    smb-hold) run smb-hold "$SMB_URIS" "-e opens $OPENS -e hold 5000" ;;
    smb-retry) run smb-retry "$SMB_URIS" "-e opens $OPENS -e retry true" ;;
    local) run local "$LOCAL_URIS" "-e opens $OPENS" ;;
    local-hold) run local-hold "$LOCAL_URIS" "-e opens $OPENS -e hold 5000" ;;
    *) echo "unknown scenario $s"; exit 1 ;;
  esac
done
echo "Logs: $WORK (remove the container with: docker rm -f $CONTAINER)"

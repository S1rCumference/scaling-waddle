#!/usr/bin/env bash
# setup_new_device.sh — factory-reset phone to working recorder, in one run.
#
#   ./scripts/setup_new_device.sh                 flip phone (Razr): cover-screen UI
#   ./scripts/setup_new_device.sh --no-cover-ui   normal phone: no cover-screen UI
#   ./scripts/setup_new_device.sh --apk path.apk  install a specific APK
#   ./scripts/setup_new_device.sh --serial R5CX   pick a device when several are attached
#
# Assumes: developer options on, USB debugging accepted, phone unlocked.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLAVOUR="razr"
APK=""
SERIAL="${SERIAL:-}"
SKIP_MODELS=0

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --no-cover-ui) FLAVOUR="standard"; shift ;;
    --apk) shift; APK="${1:-}"; [ -n "$APK" ] || die "--apk needs a path"; shift ;;
    --serial) shift; SERIAL="${1:-}"; [ -n "$SERIAL" ] || die "--serial needs a value"; shift ;;
    --skip-models) SKIP_MODELS=1; shift ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

# The standard flavour carries a different applicationId so both builds can coexist.
if [ "$FLAVOUR" = "standard" ]; then
  PKG="com.recorder.app.standard"
else
  PKG="com.recorder.app"
fi
export PKG SERIAL

adb_cmd() {
  if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

command -v adb >/dev/null 2>&1 || die "adb not found on PATH"
adb_cmd get-state >/dev/null 2>&1 || die "no device visible to adb"

echo "== Target =="
echo "device:  $(adb_cmd shell getprop ro.product.model | tr -d '\r')"
echo "flavour: $FLAVOUR  ($PKG)"
echo

# --- 1. APK ---------------------------------------------------------------
if [ -z "$APK" ]; then
  APK="$(ls -t "$REPO_ROOT/app/build/outputs/apk/$FLAVOUR/debug/"*.apk 2>/dev/null | head -1 || true)"
fi
if [ -z "$APK" ]; then
  echo "No local APK found. Either build one:"
  echo "  ./gradlew assemble${FLAVOUR^}Debug"
  echo "or download the '$( [ "$FLAVOUR" = razr ] && echo recorder-razr-debug || echo recorder-standard-debug )'"
  echo "artifact from the repo's GitHub Actions run and pass it with --apk."
  die "nothing to install"
fi

echo "== Installing $(basename "$APK") =="
adb_cmd install -r -g "$APK" || die "install failed"

# -g grants manifest permissions up front, but the mic prompt still needs to have
# been accepted at least once on some builds; force it here so recording can start
# before anyone opens the app.
adb_cmd shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
adb_cmd shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

# --- 2. Lock the phone down ----------------------------------------------
echo
"$REPO_ROOT/scripts/provision.sh" || die "provisioning failed"

# --- 3. Models ------------------------------------------------------------
if [ "$SKIP_MODELS" = 0 ]; then
  echo
  echo "== Models =="
  echo "Push the on-device models with:"
  echo "  PKG=$PKG ./scripts/fetch_models.sh --vad --asr"
  echo "Without them the app records and stores audio segments but produces no text."
fi

# --- 4. Start it ----------------------------------------------------------
echo
echo "== Starting recorder =="
adb_cmd shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
if adb_cmd shell dumpsys activity services "$PKG" | grep -q RecordingService; then
  echo "  RecordingService is running"
else
  echo "  RecordingService not visible yet — open the app once and grant the mic prompt"
fi

echo
echo "Done. $PKG is installed, exempted from Doze, and recording."
echo
echo "Heavy tier (Claude/OpenAI/Gemini) is OFF by default on every device."
echo "Whoever owns this phone can turn it on in Settings and enter their own API key;"
echo "nothing is sent anywhere until they do. See README.md, 'Provisioning other people'."

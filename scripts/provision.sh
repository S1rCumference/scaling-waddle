#!/usr/bin/env bash
# provision.sh — lock a phone down around the recorder.
#
# Run once, after the APK is installed, with the phone connected over ADB.
# Everything here is reversible: see UNDO notes at the bottom.
set -uo pipefail

PKG="${PKG:-com.recorder.app}"
SERIAL="${SERIAL:-}"

adb_cmd() {
  if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH" >&2
  exit 1
fi

if ! adb_cmd get-state >/dev/null 2>&1; then
  echo "No device visible to adb. Enable USB debugging and accept the prompt." >&2
  exit 1
fi

if ! adb_cmd shell pm list packages | grep -q "^package:${PKG}$"; then
  echo "Package $PKG is not installed. Install the APK first." >&2
  exit 1
fi

echo "== Device =="
echo "model:    $(adb_cmd shell getprop ro.product.model | tr -d '\r')"
echo "platform: $(adb_cmd shell getprop ro.board.platform | tr -d '\r')"
echo "ram:      $(adb_cmd shell cat /proc/meminfo | grep MemTotal | tr -d '\r')"
echo

# --- Telephony ------------------------------------------------------------
# No SIM ever goes in this phone, so the whole telephony stack is dead weight
# that still wakes up on cell-broadcast and IMS timers.
echo "== Disabling telephony =="
for p in com.android.dialer \
         com.android.mms \
         com.android.phone \
         com.android.cellbroadcastreceiver \
         com.android.cellbroadcastreceiver.module \
         com.motorola.telephony.ims \
         com.motorola.android.providers.chat; do
  if adb_cmd shell pm list packages | grep -q "^package:${p}$"; then
    adb_cmd shell pm disable-user --user 0 "$p" >/dev/null 2>&1 &&
      echo "  disabled $p" || echo "  skipped  $p (protected)"
  fi
done

# --- Store ----------------------------------------------------------------
# Everything is sideloaded; the Play Store only costs battery and surprise updates.
echo "== Freezing Play Store =="
adb_cmd shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 &&
  echo "  disabled com.android.vending" || echo "  skipped com.android.vending"

# --- Recorder exemptions --------------------------------------------------
echo "== Exempting $PKG from power management =="
adb_cmd shell dumpsys deviceidle whitelist "+$PKG" >/dev/null && echo "  doze whitelist ok"
adb_cmd shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null && echo "  background ops allowed"
adb_cmd shell am set-standby-bucket "$PKG" active >/dev/null 2>&1 && echo "  standby bucket: active"

# Motorola's own battery manager will otherwise re-restrict the app after a few days.
adb_cmd shell cmd appops set "$PKG" START_FOREGROUND allow >/dev/null 2>&1

# --- Everything else ------------------------------------------------------
echo "== Restricting other user apps =="
for p in $(adb_cmd shell pm list packages -3 | sed 's/package://' | tr -d '\r'); do
  if [ "$p" != "$PKG" ]; then
    adb_cmd shell am set-standby-bucket "$p" restricted >/dev/null 2>&1 &&
      echo "  restricted $p"
  fi
done

echo
echo "Provisioning complete for $PKG"
echo
echo "Verify with:"
echo "  adb shell dumpsys deviceidle whitelist | grep $PKG"
echo "  adb shell am get-standby-bucket $PKG"
echo "  adb shell top -o %CPU -n 1        # only the recorder should be busy when idle"
echo
echo "UNDO: adb shell pm enable <package>  /  adb shell am set-standby-bucket <pkg> active"

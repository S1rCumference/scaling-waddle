#!/usr/bin/env bash
# build_install_page.sh — render web/index.html into _site for GitHub Pages.
#
# Two modes:
#   with a dist directory (a release build): real file sizes and real SHA-256 digests;
#   without one: the page still builds, pointing at releases/latest/download/… so the links
#   keep working for every future release.
#
# There is one app and one release line. The page offers exactly one download per phone
# shape, and the device-owner provisioning QR targets that same razr APK — never a second,
# separately-named build, which is what it used to do.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

OWNER="${GITHUB_REPOSITORY_OWNER:-unknown}"
REPO="${GITHUB_REPOSITORY##*/}"
REPO="${REPO:-scaling-waddle}"
COMMIT="${GITHUB_SHA:-local}"
VERSION="${VERSION_NAME:-latest}"
DIST="${1:-}"

OUT="_site"
rm -rf "$OUT" && mkdir -p "$OUT"

# Asset base name and package id, both read from the build rather than typed here, so the
# page, CI and the in-app updater cannot drift apart.
PREFIX="$(grep '^recorder.releaseAssetPrefix=' gradle.properties | cut -d= -f2)"
APP_ID="$(grep -oE 'applicationId = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"

# Always releases/latest, never the version this run happens to be building.
#
# This used to pin to $VERSION whenever it looked like a tag, on the reasoning that the page
# should describe the build whose digests it is showing. That is wrong for the provisioning QR
# in a way that only shows up on a phone: the page is rebuilt on every push, and a push whose
# release step is skipped (because that version is already published) or fails leaves the QR
# pointing at a tag that holds an older build — or at a tag that does not exist at all, which
# fails silently half way through device-owner setup with the phone already wiped.
#
# releases/latest/download always resolves to the newest published non-prerelease release, so
# the QR and the download buttons agree with each other and with what is actually installable.
# The digests below say which version they were computed from rather than being implied by a
# pinned URL.
BASE="https://github.com/$OWNER/$REPO/releases/latest/download"
RAZR_URL="$BASE/$PREFIX-razr-release.apk"
STANDARD_URL="$BASE/$PREFIX-standard-release.apk"
CHECKSUMS_URL="$BASE/SHA256SUMS"

human_size() {
  local bytes="$1"
  awk -v b="$bytes" 'BEGIN { printf "%.0f MB", b / 1048576 }'
}

RAZR_SIZE="about 35 MB"
STANDARD_SIZE="about 35 MB"
RAZR_SHA="Published in SHA256SUMS with each release."
STANDARD_SHA="Published in SHA256SUMS with each release."
DIGEST_NOTE="Digests are published in SHA256SUMS beside each release."
QR_SECTION=""

if [ -n "$DIST" ] && [ -d "$DIST" ]; then
  echo "Rendering with release artifacts from $DIST"

  razr_apk="$DIST/$PREFIX-razr.apk"
  std_apk="$DIST/$PREFIX-standard.apk"

  [ -f "$razr_apk" ] && RAZR_SIZE="$(human_size "$(stat -c%s "$razr_apk")")"
  [ -f "$std_apk" ] && STANDARD_SIZE="$(human_size "$(stat -c%s "$std_apk")")"
  [ -f "$razr_apk" ] && RAZR_SHA="$(sha256sum "$razr_apk" | cut -d' ' -f1)"
  [ -f "$std_apk" ] && STANDARD_SHA="$(sha256sum "$std_apk" | cut -d' ' -f1)"
  # Named, because the links point at releases/latest and these were computed from the build
  # this page was rendered by. If the two ever differ, the digest is the one to distrust.
  DIGEST_NOTE="Digests computed from the $VERSION build. The links above always fetch the
    newest published release; check them against that release's SHA256SUMS."
fi

# --- Device-owner provisioning QR ---------------------------------------------------------
#
# Targets the razr APK this page is offering ($RAZR_URL) and this app's real package id, both
# derived above rather than typed. The bug this replaced baked in whichever separately-named
# build happened to be building, which on a second release line would have provisioned the
# wrong app as the phone's permanent device owner.
#
# The signing certificate is read from the committed keystore rather than from a built APK, so
# it is correct whether or not $DIST exists on this run — every APK this repository publishes
# is signed with that one key (see app/build.gradle.kts, "Signing").
#
# The checksum must be the SHA-256 of the signing certificate, base64url without padding. Hex,
# standard base64, or a digest of the APK instead of the certificate all fail silently during
# setup, which is impossible to debug on a phone mid-provisioning, so it is computed here
# rather than typed by hand.
KEYSTORE="signing/recorder.keystore"
KEYSTORE_PASSWORD="$(grep -oE 'keystorePassword = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"
KEYSTORE_ALIAS="$(grep -oE 'keystoreAlias = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"

CERT_B64URL=""
if [ -f "$KEYSTORE" ] && command -v keytool >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
  CERT_B64URL="$(
    keytool -exportcert -alias "$KEYSTORE_ALIAS" -keystore "$KEYSTORE" \
        -storepass "$KEYSTORE_PASSWORD" -rfc 2>/dev/null |
      openssl x509 -outform DER 2>/dev/null |
      python3 -c "import base64, hashlib, sys; print(base64.urlsafe_b64encode(hashlib.sha256(sys.stdin.buffer.read()).digest()).decode().rstrip(chr(61)))"
  )"
fi

if [ -n "$CERT_B64URL" ]; then
  cat > "$OUT/provisioning.json" <<JSON
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "$APP_ID/com.recorder.app.admin.RecorderDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "$RAZR_URL",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "$CERT_B64URL",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false
}
JSON

  python3 -m pip install --quiet 'qrcode[pil]' >/dev/null 2>&1 || true
  if python3 - "$OUT/provisioning.json" "$OUT/provisioning-qr.png" <<'PY'
import json, sys
try:
    import qrcode
except ImportError:
    sys.exit(1)
payload = json.dumps(json.load(open(sys.argv[1])), separators=(",", ":"))
img = qrcode.make(payload, box_size=8, border=2)
img.save(sys.argv[2])
PY
  then
    QR_SECTION="$(cat <<'HTML'
<h2>Fresh phone setup (most reliable)</h2>
<p>
  This path makes Recorder the phone's <em>device owner</em>, which is the only way Android
  lets recording restart by itself after a reboot. Without it, you tap a notification once
  after each restart. It installs the same build this page offers above.
</p>
<p>It only works on a phone with no accounts signed in, so it means factory resetting first.</p>
<ol>
  <li>On the phone: <span class="tap">Settings → System → Reset → Erase all data</span>.</li>
  <li>On the welcome screen after it restarts, tap the <strong>same spot six times</strong>.
    A QR scanner opens.</li>
  <li>Connect to Wi-Fi when asked.</li>
  <li>Scan the code below from another screen -- a laptop, a tablet, or someone else's phone
    showing this page.</li>
  <li>The phone downloads and installs the app itself, then runs the setup wizard.</li>
</ol>
<div class="qr"><img src="provisioning-qr.png" alt="Device owner provisioning QR code"></div>
<p class="lede">
  Device owner is reversible: Settings → Surviving a reboot → Remove device owner, with no
  second factory reset. The exact contents of this code are in
  <a href="provisioning.json">provisioning.json</a>.
</p>
HTML
)"
  else
    echo "QR library unavailable; skipping the QR section"
  fi
fi

if [ -z "$QR_SECTION" ]; then
  QR_SECTION="$(cat <<HTML
<h2>Fresh phone setup</h2>
<p class="lede">
  The device-owner QR code needs keytool and openssl available where this page is built.
  Once they are, this section becomes the factory-reset setup path.
</p>
HTML
)"
fi

python3 - "$OUT/index.html" <<PY
import pathlib
html = pathlib.Path("web/index.html").read_text()
replacements = {
    "__RAZR_URL__": """$RAZR_URL""",
    "__STANDARD_URL__": """$STANDARD_URL""",
    "__CHECKSUMS_URL__": """$CHECKSUMS_URL""",
    "__RAZR_SIZE__": """$RAZR_SIZE""",
    "__STANDARD_SIZE__": """$STANDARD_SIZE""",
    "__RAZR_SHA__": """$RAZR_SHA""",
    "__STANDARD_SHA__": """$STANDARD_SHA""",
    "__VERSION__": """$VERSION""",
    "__OWNER__": """$OWNER""",
    "__REPO__": """$REPO""",
    "__COMMIT__": """${COMMIT:0:12}""",
    "__QR_SECTION__": '''$QR_SECTION''',
    "__ASSET_PREFIX__": """$PREFIX""",
    "__DIGEST_NOTE__": """$DIGEST_NOTE""",
}
for key, value in replacements.items():
    html = html.replace(key, value)

missing = [line for line in html.splitlines() if "__" in line and "_site" not in line]
if any("__" + t + "__" in html for t in ["RAZR_URL", "STANDARD_URL", "QR_SECTION", "VERSION", "ASSET_PREFIX"]):
    raise SystemExit("a placeholder was left unreplaced")

pathlib.Path("$OUT/index.html").write_text(html)
print("wrote $OUT/index.html")
PY

# A .nojekyll file stops Pages trying to process this as a Jekyll site.
touch "$OUT/.nojekyll"
ls -la "$OUT"

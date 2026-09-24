#!/usr/bin/env bash
# build_install_page.sh — render web/index.html into _site for GitHub Pages.
#
# Two modes:
#   with a dist directory (a release build): real file sizes and real SHA-256 digests for
#   whichever line of the app (stable or 2.1) triggered this run;
#   without one: the page still builds, pointing at releases/latest/download/… so the links
#   keep working for every future release.
#
# The device-owner provisioning QR is independent of both: it always targets the stable
# release, never whichever tag triggered this run. See "Device-owner provisioning QR" below.
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

# This line of the app (2.1) and its asset names, shared with CI and the in-app updater.
PREFIX="$(grep '^recorder.releaseAssetPrefix=' gradle.properties | cut -d= -f2)"

# Links are pinned to tags, not releases/latest: two lines of the app publish to the same
# repository, and "latest" can only ever point at one of them.
case "$VERSION" in
  v[0-9]*) BASE="https://github.com/$OWNER/$REPO/releases/download/$VERSION" ;;
  *)       BASE="https://github.com/$OWNER/$REPO/releases/latest/download" ;;
esac
RAZR_URL="$BASE/$PREFIX-razr.apk"
STANDARD_URL="$BASE/$PREFIX-standard.apk"
CHECKSUMS_URL="$BASE/SHA256SUMS"

# The stable build stays on offer beside 2.1, from its own release. Its package id is
# hardcoded rather than read from app/build.gradle.kts: that file describes whichever line of
# the app is currently checked out (2.1 changed applicationId to com.recorder.app.v21), which
# is never the stable release's own id once 2.1 exists. v0.2.0 (tag a3eeb11) shipped as
# com.recorder.app with no suffix on the razr flavor -- that is a fact about a release that
# already happened and will not change, so it is a constant here, not a derivation.
STABLE_APP_ID="com.recorder.app"
STABLE_TAG="${STABLE_TAG:-v0.2.0}"
STABLE_BASE="https://github.com/$OWNER/$REPO/releases/download/$STABLE_TAG"
STABLE_RAZR_URL="$STABLE_BASE/recorder-razr-release.apk"
STABLE_STANDARD_URL="$STABLE_BASE/recorder-standard-release.apk"
STABLE_RAZR_SHA="$(curl -fsSL "$STABLE_BASE/SHA256SUMS" 2>/dev/null | awk '/recorder-razr-release.apk/ {print $1}' || true)"
# Whether the stable release is actually reachable -- the signal that gates the QR below,
# since a QR that cannot download is worse than no QR.
STABLE_RELEASE_PUBLISHED=1
if [ -z "$STABLE_RAZR_SHA" ]; then
  STABLE_RELEASE_PUBLISHED=0
  STABLE_RAZR_SHA="See SHA256SUMS on the $STABLE_TAG release."
fi

human_size() {
  local bytes="$1"
  awk -v b="$bytes" 'BEGIN { printf "%.0f MB", b / 1048576 }'
}

RAZR_SIZE="about 35 MB"
STANDARD_SIZE="about 35 MB"
RAZR_SHA="Published in SHA256SUMS with each release."
STANDARD_SHA="Published in SHA256SUMS with each release."
QR_SECTION=""

if [ -n "$DIST" ] && [ -d "$DIST" ]; then
  echo "Rendering with release artifacts from $DIST"

  razr_apk="$DIST/$PREFIX-razr.apk"
  std_apk="$DIST/$PREFIX-standard.apk"

  [ -f "$razr_apk" ] && RAZR_SIZE="$(human_size "$(stat -c%s "$razr_apk")")"
  [ -f "$std_apk" ] && STANDARD_SIZE="$(human_size "$(stat -c%s "$std_apk")")"
  [ -f "$razr_apk" ] && RAZR_SHA="$(sha256sum "$razr_apk" | cut -d' ' -f1)"
  [ -f "$std_apk" ] && STANDARD_SHA="$(sha256sum "$std_apk" | cut -d' ' -f1)"
fi

# --- Device-owner provisioning QR -----------------------------------------------------
#
# This must always provision the *stable* app, never whichever line of the app (stable or
# 2.1) triggered the current run of this script. A QR code baked from the tag that happens
# to be building would, on a 2.1 release, factory-provision the .v21 test build as the
# phone's permanent device owner instead of the stable app people actually rely on. So
# nothing below is read from $DIST or from the current checkout's build.gradle.kts: the
# component name is $STABLE_APP_ID (a constant, see above), the download location is
# $STABLE_RAZR_URL, and the signing certificate is read directly from the committed keystore
# rather than from any built APK.
#
# That keystore is the same file every variant -- stable and 2.1 alike -- is signed with
# (see app/build.gradle.kts, "Signing"), so its certificate is the one every APK this
# repository has ever published actually carries, independent of which build produced $DIST
# on this run.
#
# The signature checksum must be the SHA-256 of the signing certificate, encoded base64url
# without padding. Hex, standard base64, or a digest of the APK instead of the certificate
# all fail silently during setup, which is impossible to debug on a phone mid-provisioning,
# so it is computed here rather than typed by hand.
KEYSTORE="signing/recorder.keystore"
KEYSTORE_PASSWORD="$(grep -oE 'keystorePassword = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"
KEYSTORE_ALIAS="$(grep -oE 'keystoreAlias = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"

CERT_B64URL=""
if [ "$STABLE_RELEASE_PUBLISHED" = "1" ] && [ -f "$KEYSTORE" ] \
  && command -v keytool >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
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
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "$STABLE_APP_ID/com.recorder.app.admin.RecorderDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "$STABLE_RAZR_URL",
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
  This path makes the <strong>stable</strong> Recorder the phone's <em>device owner</em>,
  which is the only way Android lets recording restart by itself after a reboot. Without it,
  you tap a notification once after each restart. It always sets up the stable app, even
  when 2.1 is the newest thing on this page -- install 2.1 afterward if you want it too.
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
  The device-owner QR code needs the stable release ($STABLE_TAG) to be published and this
  script to have keytool and openssl available. Once both are true, this section becomes the
  factory-reset setup path.
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
    "__STABLE_RAZR_URL__": """$STABLE_RAZR_URL""",
    "__STABLE_STANDARD_URL__": """$STABLE_STANDARD_URL""",
    "__STABLE_VERSION__": """$STABLE_TAG""",
    "__STABLE_RAZR_SHA__": """$STABLE_RAZR_SHA""",
}
for key, value in replacements.items():
    html = html.replace(key, value)

missing = [line for line in html.splitlines() if "__" in line and "_site" not in line]
if any("__" + t + "__" in html for t in ["RAZR_URL", "STANDARD_URL", "QR_SECTION", "VERSION", "STABLE_RAZR_URL", "ASSET_PREFIX"]):
    raise SystemExit("a placeholder was left unreplaced")

pathlib.Path("$OUT/index.html").write_text(html)
print("wrote $OUT/index.html")
PY

# A .nojekyll file stops Pages trying to process this as a Jekyll site.
touch "$OUT/.nojekyll"
ls -la "$OUT"

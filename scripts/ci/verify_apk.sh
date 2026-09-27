#!/usr/bin/env bash
# verify_apk.sh — assert an APK can actually transcribe once installed.
#
# A green build is not proof the runtime survived packaging. The ABI excludes, the native-lib
# merge and the packaging mode all sit after compilation, and getting any of them wrong produces
# an APK that installs cleanly and then writes nothing down — no build error, no crash, and no
# log reachable from a phone with no computer attached.
#
# What used to be here as well: a check that llama.cpp's CPU kernels were present and extractable,
# because it loaded them by scanning the on-disk native library directory and an APK that did not
# extract them registered no backend and failed to load every model. There is no llama.cpp any
# more, so that check has nothing to assert.
set -euo pipefail

APK="${1:?usage: verify_apk.sh <path-to-apk>}"
[ -f "$APK" ] || { echo "ERROR: no APK at $APK" >&2; exit 1; }

echo "--- verifying $APK"

libs="$(unzip -Z1 "$APK" 'lib/*' 2>/dev/null || true)"
if [ -z "$libs" ]; then
  echo "ERROR: $APK contains no native libraries at all" >&2
  exit 1
fi
echo "$libs" | sed 's/^/    /'

# An error, not a warning. Without this the app records and produces no text, which is the
# failure this whole script exists to catch before a phone does.
if ! echo "$libs" | grep -q 'libsherpa-onnx-jni\.so$'; then
  echo "ERROR: no sherpa-onnx JNI library; this build cannot transcribe." >&2
  exit 1
fi

# onnxruntime is what Silero runs on. Without it there is no voice detection either.
if ! echo "$libs" | grep -q 'libonnxruntime\.so$'; then
  echo "ERROR: no ONNX Runtime; voice detection cannot load." >&2
  exit 1
fi

echo "--- $APK looks runnable"

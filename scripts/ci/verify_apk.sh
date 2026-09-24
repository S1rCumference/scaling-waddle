#!/usr/bin/env bash
# verify_apk.sh — assert an APK can actually run the local AI models.
#
# This exists because of a packaging fault that produced no build error, no crash and no
# log the phone could show: llama.cpp is built with GGML_BACKEND_DL=ON, so its CPU kernels
# are separate libggml-cpu-*.so files that it dlopen()s at start-up by scanning
# ApplicationInfo.nativeLibraryDir. If the APK does not extract its native libraries at
# install time, that directory is empty, no backend registers, and every model fails to
# load. The APK sets useLegacyPackaging=true; this checks that it stayed that way.
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

# Only meaningful when the llama.cpp AAR was actually bundled into this build.
if [ -f core-llm/libs/llama-release.aar ]; then
  if ! echo "$libs" | grep -q 'libggml-cpu.*\.so$'; then
    echo "ERROR: no libggml-cpu-*.so in the APK." >&2
    echo "       llama.cpp cannot register a CPU backend and every model will fail to load." >&2
    exit 1
  fi

  # Stored (method 0) means useLegacyPackaging=false: the libraries stay inside the APK and
  # are never written to nativeLibraryDir, which is the failure this check is here for.
  if unzip -v "$APK" | grep -E 'lib/.*libggml-cpu.*\.so' | grep -qw Stored; then
    echo "ERROR: native libraries are Stored, not Deflated." >&2
    echo "       That means extractNativeLibs=false, so llama.cpp will find an empty" >&2
    echo "       nativeLibraryDir and load no CPU backend. Set:" >&2
    echo "         android { packaging { jniLibs { useLegacyPackaging = true } } }" >&2
    exit 1
  fi
  echo "    llama.cpp CPU kernels present and extractable"
fi

if ! echo "$libs" | grep -q 'libsherpa-onnx-jni\.so$'; then
  echo "WARNING: no sherpa-onnx JNI library; this build cannot transcribe." >&2
fi

echo "--- $APK looks runnable"

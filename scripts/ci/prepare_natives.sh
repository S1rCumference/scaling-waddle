#!/usr/bin/env bash
# prepare_natives.sh — put the native runtime in place before Gradle configures.
#
# Must run BEFORE ./gradlew, because core-asr decides at configuration time whether its
# optional source set compiles, based on what is in its libs/ directory.
#
# Idempotent: if an artifact is already present (restored from cache, or fetched by a
# previous run) it is left alone.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# --- Pinned versions -----------------------------------------------------------
# sherpa-onnx: the static-link variant bundles ONNX Runtime inside its own .so, so the
# arm64 slice carries no libonnxruntime.so and cannot clash with the Maven
# onnxruntime-android that core-audio uses for Silero VAD. Verified against v1.13.8:
#   jni/arm64-v8a/ contains only libsherpa-onnx-jni.so
SHERPA_VERSION="${SHERPA_VERSION:-1.13.8}"
SHERPA_AAR="sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${SHERPA_AAR}"

ABI="arm64-v8a"
SHERPA_DEST="core-asr/libs/${SHERPA_AAR}"

log() { printf '\n== %s\n' "$*"; }

# --- sherpa-onnx ---------------------------------------------------------------
if [ -f "$SHERPA_DEST" ]; then
  log "sherpa-onnx ${SHERPA_VERSION} already present"
else
  log "Downloading sherpa-onnx ${SHERPA_VERSION}"
  mkdir -p core-asr/libs
  curl -fL --retry 3 -o "${SHERPA_DEST}.part" "$SHERPA_URL"
  mv "${SHERPA_DEST}.part" "$SHERPA_DEST"
fi

# Fail loudly rather than shipping an APK with two copies of libonnxruntime.so.
if unzip -l "$SHERPA_DEST" | grep -q "jni/${ABI}/libonnxruntime.so"; then
  echo "ERROR: ${SHERPA_AAR} carries its own libonnxruntime.so for ${ABI}." >&2
  echo "That will clash with onnxruntime-android. Use the static-link variant." >&2
  exit 1
fi
echo "sherpa-onnx: $(du -h "$SHERPA_DEST" | cut -f1) at $SHERPA_DEST"

# There is no llama.cpp here any more. It used to be built from source at a pinned commit —
# a full NDK build, several minutes of every CI run — for a language model that ran at about
# eight tokens a second and could not do the job. Summaries come from a hosted endpoint now, so
# this script has one runtime to place.

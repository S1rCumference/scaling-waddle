#!/usr/bin/env bash
# prepare_natives.sh — put both native runtimes in place before Gradle configures.
#
# Must run BEFORE ./gradlew, because core-asr and core-llm decide at configuration time
# whether their optional source sets compile, based on what is in their libs/ directories.
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

# llama.cpp's Android library lives in examples/llama.android and is not published
# anywhere, so we build it. Pinned because that example was rewritten wholesale once
# already (it is now ARM's "AiChat" wrapper) and its Kotlin API moves with it.
LLAMA_COMMIT="${LLAMA_COMMIT:-fee39dd92673ba0c08c8da96040ce53368b35188}"
LLAMA_REPO="https://github.com/ggml-org/llama.cpp"

ABI="arm64-v8a"
SHERPA_DEST="core-asr/libs/${SHERPA_AAR}"
LLAMA_DEST="core-llm/libs/llama-release.aar"

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

# --- llama.cpp -----------------------------------------------------------------
if [ -f "$LLAMA_DEST" ]; then
  log "llama.cpp AAR already present (cached)"
  echo "llama.cpp: $(du -h "$LLAMA_DEST" | cut -f1) at $LLAMA_DEST"
  exit 0
fi

: "${ANDROID_HOME:?ANDROID_HOME must be set to build the llama.cpp AAR}"
# The runner installs cmdline-tools under a version directory, not always "latest".
SDKMANAGER="$(
  ls -d "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1
)"
[ -x "${SDKMANAGER:-}" ] || SDKMANAGER="$(command -v sdkmanager || true)"
[ -x "${SDKMANAGER:-}" ] || { echo "ERROR: sdkmanager not found under $ANDROID_HOME" >&2; exit 1; }
echo "sdkmanager: $SDKMANAGER"

log "Installing NDK and CMake pinned by llama.android"
# Versions come from lib/build.gradle.kts at the pinned commit; if the exact CMake is not
# offered by this SDK channel we drop the pin below and let AGP pick what is installed.
yes | "$SDKMANAGER" --install "platforms;android-36" "ndk;29.0.13113456" >/dev/null || {
  echo "ERROR: could not install the NDK llama.android pins." >&2
  exit 1
}
CMAKE_PINNED=1
yes | "$SDKMANAGER" --install "cmake;3.31.6" >/dev/null 2>&1 || CMAKE_PINNED=0

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log "Fetching llama.cpp @ ${LLAMA_COMMIT:0:12}"
git -C "$WORK" init -q
git -C "$WORK" remote add origin "$LLAMA_REPO"
git -C "$WORK" fetch -q --depth 1 origin "$LLAMA_COMMIT"
git -C "$WORK" checkout -q FETCH_HEAD

ANDROID_DIR="$WORK/examples/llama.android"
[ -d "$ANDROID_DIR" ] || { echo "ERROR: examples/llama.android missing at this commit" >&2; exit 1; }

LIB_GRADLE="$ANDROID_DIR/lib/build.gradle.kts"

# Patch 1: minSdk. Upstream sets 33, which would force this whole app to Android 13+.
# Nothing in that thin Kotlin wrapper needs it, and the app targets minSdk 26.
sed -i 's/^\( *\)minSdk = 33$/\1minSdk = 26/' "$LIB_GRADLE"

# Patch 2: drop x86_64. We ship arm64 only, and the native build is the slow part.
sed -i "s/abiFilters += listOf(\"arm64-v8a\", \"x86_64\")/abiFilters += listOf(\"$ABI\")/" "$LIB_GRADLE"

# Patch 3: only if the pinned CMake was unavailable, let AGP choose.
if [ "$CMAKE_PINNED" = 0 ]; then
  echo "Pinned CMake unavailable; removing the version pin"
  sed -i '/version = "3.31.6"/d' "$LIB_GRADLE"
fi

echo "--- patched lib/build.gradle.kts ---"
grep -nE 'minSdk|abiFilters|version = "3' "$LIB_GRADLE" || true

# Verify the patches actually applied; a silent sed miss would mean a 33-minSdk AAR that
# fails much later with a confusing manifest-merger error.
grep -q "minSdk = 26" "$LIB_GRADLE" || { echo "ERROR: minSdk patch did not apply" >&2; exit 1; }
grep -q "abiFilters += listOf(\"$ABI\")" "$LIB_GRADLE" ||
  { echo "ERROR: abiFilters patch did not apply" >&2; exit 1; }

log "Building :lib:assembleRelease (native build, this is the slow step)"
( cd "$ANDROID_DIR" && chmod +x gradlew && ./gradlew --no-daemon :lib:assembleRelease )

BUILT="$ANDROID_DIR/lib/build/outputs/aar/lib-release.aar"
[ -f "$BUILT" ] || { echo "ERROR: expected AAR at $BUILT" >&2; ls -R "$ANDROID_DIR/lib/build/outputs" >&2 || true; exit 1; }

mkdir -p core-llm/libs
cp "$BUILT" "$LLAMA_DEST"
echo "llama.cpp: $(du -h "$LLAMA_DEST" | cut -f1) at $LLAMA_DEST"
unzip -l "$LLAMA_DEST" | grep -E '\.so$' || true

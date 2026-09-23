#!/usr/bin/env bash
# fetch_models.sh — download the on-device models and push them to the phone.
#
# Nothing here is bundled in the APK: the models are large, they have their own
# licences, and they change faster than this app does. Everything lands in the app's
# private storage, so the phone is the only place any of it lives.
#
#   ./scripts/fetch_models.sh --vad            Silero VAD (small, always worth having)
#   ./scripts/fetch_models.sh --asr            Parakeet-TDT INT8 for sherpa-onnx
#   ./scripts/fetch_models.sh --sherpa         sherpa-onnx Android AAR into core-asr/libs
#   ./scripts/fetch_models.sh --llama          llama.cpp Android AAR into core-llm/libs
#   ./scripts/fetch_models.sh --llm <file.gguf>  push a GGUF you already downloaded
#   ./scripts/fetch_models.sh --all
#
# URLs are overridable, because release filenames move:
#   SILERO_URL=... SHERPA_AAR_URL=... PARAKEET_URL=... ./scripts/fetch_models.sh --all
set -uo pipefail

# run-as only works against a debuggable build, so this defaults to the debug applicationId.
# For the signed release build, models are downloaded by the in-app wizard instead.
PKG="${PKG:-com.recorder.app.debug}"
WORK_DIR="${WORK_DIR:-$(cd "$(dirname "$0")/.." && pwd)/.models}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SILERO_URL="${SILERO_URL:-https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx}"
SHERPA_VERSION="${SHERPA_VERSION:-1.10.32}"
SHERPA_AAR_URL="${SHERPA_AAR_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar}"
PARAKEET_URL="${PARAKEET_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2}"

mkdir -p "$WORK_DIR"

die() { echo "error: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"; }

# The app's files dir is only reachable through run-as, which works on the debug
# builds this project is meant to be sideloaded as.
push_private() {
  local local_path="$1" remote_rel="$2"
  need adb
  adb get-state >/dev/null 2>&1 || die "no device visible to adb"

  local remote_dir
  remote_dir="$(dirname "$remote_rel")"
  local tmp="/data/local/tmp/$(basename "$local_path")"

  echo "  pushing $(basename "$local_path")…"
  adb push "$local_path" "$tmp" >/dev/null || die "adb push failed"
  adb shell "run-as $PKG mkdir -p files/$remote_dir" ||
    die "run-as failed — is this a debug build of $PKG?"
  adb shell "run-as $PKG cp $tmp files/$remote_rel" || die "copy into app storage failed"
  adb shell "rm -f $tmp" >/dev/null
  echo "  installed at files/$remote_rel"
}

fetch() {
  local url="$1" dest="$2"
  [ -f "$dest" ] && { echo "  cached $(basename "$dest")"; return 0; }
  need curl
  echo "  downloading $(basename "$dest")…"
  curl -fL --retry 3 -o "$dest.part" "$url" || die "download failed: $url"
  mv "$dest.part" "$dest"
}

do_vad() {
  echo "== Silero VAD =="
  fetch "$SILERO_URL" "$WORK_DIR/silero_vad.onnx"
  push_private "$WORK_DIR/silero_vad.onnx" "models/silero_vad.onnx"
}

do_sherpa() {
  echo "== sherpa-onnx AAR =="
  fetch "$SHERPA_AAR_URL" "$WORK_DIR/sherpa-onnx-${SHERPA_VERSION}.aar"
  cp "$WORK_DIR/sherpa-onnx-${SHERPA_VERSION}.aar" "$REPO_ROOT/core-asr/libs/"
  echo "  copied into core-asr/libs — rebuild the APK to compile the Parakeet engine in"
}

do_asr() {
  echo "== Parakeet-TDT (INT8) =="
  need tar
  fetch "$PARAKEET_URL" "$WORK_DIR/parakeet.tar.bz2"
  rm -rf "$WORK_DIR/parakeet" && mkdir -p "$WORK_DIR/parakeet"
  tar xjf "$WORK_DIR/parakeet.tar.bz2" -C "$WORK_DIR/parakeet" --strip-components=1 ||
    die "could not unpack the model archive"

  local found=0
  for f in "$WORK_DIR"/parakeet/{encoder,decoder,joiner}*.onnx "$WORK_DIR"/parakeet/tokens.txt; do
    [ -f "$f" ] || continue
    push_private "$f" "models/asr/$(basename "$f")"
    found=1
  done
  [ "$found" = 1 ] || die "archive did not contain encoder/decoder/joiner/tokens files"
}

do_llama() {
  echo "== llama.cpp Android AAR =="
  cat <<'NOTE'
  CI already builds this and bundles it into the released APK, so you normally need
  nothing here. To build it locally (needs the Android SDK, NDK and CMake):

    ./scripts/ci/prepare_natives.sh

  That fetches the pinned sherpa-onnx AAR and builds llama.cpp's Android library
  (examples/llama.android, module :lib) at the pinned commit into core-llm/libs/.
NOTE
}

do_llm() {
  local gguf="$1"
  [ -f "$gguf" ] || die "no such file: $gguf"
  echo "== GGUF: $(basename "$gguf") =="
  echo "  the app looks for these exact names, largest first:"
  echo "    phi-4-mini-q4.gguf, qwen3-1.7b-q4.gguf, gemma-3-1b-q4.gguf   (small tier)"
  echo "    gemma-3-4b-q4.gguf (12GB), qwen3-8b-q4.gguf (16GB+)          (heavy tier)"
  push_private "$gguf" "models/llm/$(basename "$gguf")"
}

[ $# -gt 0 ] || { grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -20; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --vad) do_vad; shift ;;
    --asr) do_asr; shift ;;
    --sherpa) do_sherpa; shift ;;
    --llama) do_llama; shift ;;
    --llm) shift; [ $# -gt 0 ] || die "--llm needs a path to a .gguf"; do_llm "$1"; shift ;;
    --all) do_sherpa; do_vad; do_asr; do_llama; shift ;;
    *) die "unknown option: $1" ;;
  esac
done

echo
echo "Done. Restart the recorder to pick up new models:"
echo "  adb shell am force-stop $PKG && adb shell monkey -p $PKG 1"

# Native ASR runtime drop-in

The build looks for `sherpa-onnx*.aar` in this directory.

- **Absent** (default, and what CI builds): the app compiles and runs, but
  `AsrEngineFactory` returns a no-op engine — audio is captured and segmented, no text
  is produced.
- **Present**: `SherpaAsrEngine` (in `core-asr/src/sherpa/java`) is compiled in and used
  automatically, with no other code change.

Fetch it with `scripts/fetch_models.sh --sherpa`, or download the Android AAR from
<https://github.com/k2-fsa/sherpa-onnx/releases> and copy it here.

The AAR is deliberately not committed: it is tens of megabytes of prebuilt native code
and belongs to the sherpa-onnx release stream, not to this repository's history.

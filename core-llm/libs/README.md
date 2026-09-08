# Local model runtime drop-in

The build looks for `llama*.aar` in this directory.

- **Absent** (default, and what CI builds): `LocalModelRuntime.load()` returns null, the
  on-device chat answers with a clear "local model runtime not installed" message, and
  the heavy tier falls back to whichever cloud provider is configured.
- **Present**: the llama.cpp-backed engine in `core-llm/src/llama/java` is compiled in and
  used for both the Phase 2 small model and the Phase 4 local heavy model.

Build it from llama.cpp's `examples/llama.android` Gradle project and copy the resulting
AAR here, or run `scripts/fetch_models.sh --llama`.

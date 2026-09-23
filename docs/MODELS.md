# Models

Nothing is bundled in the APK. The phone downloads what it needs once, over Wi-Fi, from the
first-run wizard, and then runs offline forever. `app/src/main/assets/models.json` is the
manifest that drives it.

## What ships in the manifest today

| Model | Role | Size | Digest verified |
|---|---|---|---|
| Silero VAD v6.2.3 | voice activity detection | 2.2 MB | yes |
| Parakeet TDT 0.6B v2 INT8 | speech recognition | 460 MB | yes |

"Digest verified" means the SHA-256 in the manifest was produced by downloading that exact
URL and hashing the bytes — not copied from a README. Both were verified this way:

```
silero_vad.onnx      2327524 bytes  1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3
parakeet …int8.tar.bz2 482468385 bytes  157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad
```

## Why there are no chat models yet

The on-device chat model and the local heavy tier are absent from the manifest. Every
practical GGUF build lives on huggingface.co, which the build environment's network policy
blocks, so neither the URLs nor the digests could be verified.

The two bad options were shipping invented digests (every install fails verification) or
shipping no digests (no integrity checking on the largest downloads, over phone Wi-Fi, where
truncation is the most likely failure). Neither is acceptable, so those entries wait until
the URLs can be checked.

**Nothing else is blocked by this.** Recording, voice activity detection, transcription,
keyword flagging and folder assignment all work. Settings → *This device* reports the local
AI as unavailable with the reason, and the wizard says the same on its final step.

To unblock: allow `huggingface.co` in the environment's network settings, then the manifest
gains verified entries for the candidates in Phase 6 (Phi-4-mini, Qwen3 1.7B/4B/8B,
Gemma 3 1B/4B), each with a measured size and digest.

## Adding a model by hand

```json
{
  "id": "qwen3-1.7b-q4",
  "role": "small_chat",
  "displayName": "Qwen 3 1.7B (Q4_K_M)",
  "fileName": "qwen3-1.7b-q4.gguf",
  "url": "https://…/Qwen3-1.7B-Q4_K_M.gguf",
  "revision": "main",
  "sizeBytes": 1120000000,
  "sha256": "…64 hex chars…",
  "license": "Apache-2.0",
  "minRamTier": "LOW_8GB",
  "required": false,
  "archive": null,
  "hashVerified": true,
  "notes": "Shown to the user on the download screen."
}
```

Get the size and digest the same way the shipped ones were:

```bash
curl -sIL "<url>" | grep -i content-length
curl -sL "<url>" | sha256sum
```

Fields that matter:

- `role` — `vad`, `asr`, `small_chat` or `heavy`. An unknown role is skipped, not fatal.
- `minRamTier` — the lowest tier allowed to offer it: `LOW_8GB`, `MID_12GB`, `HIGH_16GB_PLUS`.
  The wizard preselects the largest model a tier can host.
- `required` — the user cannot untick it, and setup is not "complete" without it.
- `archive` — `tar.bz2`, or null. Archives are unpacked into the role's directory, flattened,
  keeping only `*.onnx` and `tokens.txt`; entry paths are checked so a malformed archive
  cannot write outside the target directory.
- `sha256` — omit it rather than guessing. The installer then checks size only, and the UI
  tells the user the download was not checksum-verified.

`./gradlew :app:testRazrDebugUnitTest` validates the shipped manifest: well-formed digests,
https URLs, positive sizes, supported archive formats, and that a `hashVerified` entry
actually carries a hash.

## Where files land on the phone

| Role | Path (app-private) |
|---|---|
| `vad` | `files/models/silero_vad.onnx` |
| `asr` | `files/models/asr/` (encoder, decoder, joiner, tokens) |
| `small_chat`, `heavy` | `files/models/llm/*.gguf` |

App-private storage, so uninstalling the app removes the models, and nothing else on the
phone can read your transcripts or the models beside them.

## Developer fallback

`scripts/fetch_models.sh` still pushes models over ADB for development. It uses `run-as`,
which only works on a debuggable build, so point it at the debug applicationId:

```bash
PKG=com.recorder.app.debug ./scripts/fetch_models.sh --vad --asr
```

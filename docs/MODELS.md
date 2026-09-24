# Models

Nothing is bundled in the APK. The phone downloads what it needs once, over Wi-Fi, from the
first-run wizard, and then runs offline forever. `app/src/main/assets/models.json` is the
manifest that drives it.

## What ships in the manifest today

| Model | Role | Size | Tier | Licence | Digest verified |
|---|---|---|---|---|---|
| Silero VAD v6.2.3 | voice activity detection | 2.2 MB | all | MIT | yes |
| Parakeet TDT 0.6B v2 INT8 | speech recognition | 460 MB | all | CC-BY-4.0 | yes |
| Gemma 3 1B Instruct Q4_K_M | small chat | 769 MB | 8 GB+ | Gemma Terms | no |
| Qwen 3 1.7B Q4_K_M | small chat | 1.03 GB | 8 GB+ | Apache-2.0 | no |
| Qwen 3 4B Q4_K_M | heavy | 2.33 GB | 12 GB+ | Apache-2.0 | no |

Every URL and byte size above was checked against the source, not copied from a README:

```
silero_vad.onnx                   2,327,524  sha256 1a153a22…88e3
parakeet …v2-int8.tar.bz2       482,468,385  sha256 157c157b…e1ad
gemma-3-1b-it-Q4_K_M.gguf       806,058,272  size verified, no digest
Qwen3-1.7B-Q4_K_M.gguf        1,107,409,472  size verified, no digest
Qwen3-4B-Q4_K_M.gguf          2,497,280,256  size verified, no digest
Qwen3-8B-Q4_K_M.gguf          5,027,783,488  size verified, no digest
```

### Why the GGUF entries have no digest

Hugging Face stores these as LFS objects whose SHA-256 is the LFS `oid`, but that value is
not exposed by the Hub interface available here, and computing it means downloading between
0.8 and 5 GB per model. So those entries ship `sha256: null` and `hashVerified: false`.

What that costs, precisely:

- **Truncated or interrupted downloads are still caught.** The byte size is exact, and the
  installer refuses a file of the wrong length. This is the failure that actually happens on
  phone Wi-Fi.
- **A substituted file would not be caught.** Nothing here defends against a compromised
  mirror. The download is HTTPS from huggingface.co, and that is the whole of the guarantee.

The UI says so on the download screen rather than implying a verification that did not happen.
To add a digest later, download the file on a machine that can reach the Hub and run
`sha256sum`, then fill in `sha256` and set `hashVerified: true`.

### Which model a phone is offered

Tiering comes from `DeviceCapabilities.marketedRamGb`, which snaps the kernel's reported
memory to the size the phone is sold as — a 12 GB phone reports 10.5 to 11.6 GiB, and a raw
threshold used to drop it into the 8 GB tier.

| Phone | Small chat | Heavy |
|---|---|---|
| 8 GB | Qwen 3 1.7B | none — nothing heavy fits beside ASR |
| 12 GB (this Razr+) | Qwen 3 1.7B | Qwen 3 4B (charging only) |
| 16 GB+ | Qwen 3 1.7B | Qwen 3 4B (charging only) |

Whether a 12 GB phone can actually hold the 8B instead of the 4B is a question for the
on-device benchmark (Settings → Benchmark), not a guess made here.

`app/src/test/java/com/recorder/app/models/ModelCatalogTest.kt` asserts the manifest filenames
match the names `LocalModelSelector` looks for. Without that, a renamed file downloads
successfully and is then never found, and the only symptom is an assistant that stays
unavailable.

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
- `minRamTier` — the lowest tier allowed to offer it: `LOW_8GB` or `MID_12GB`. Nothing in
  this build needs more, so a 16 GB phone is offered exactly what a 12 GB one is.
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

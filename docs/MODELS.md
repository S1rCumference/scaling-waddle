# Models

Nothing is bundled in the APK. The phone downloads what it needs once, over Wi-Fi, from the
first-run wizard, and then runs offline forever. `app/src/main/assets/models.json` is the
manifest that drives it.

## What ships in the manifest today

| Model | Role | Size | Licence | Digest verified |
|---|---|---|---|---|
| Silero VAD v6.2.3 | voice activity detection | 2.2 MB | MIT | yes |
| Parakeet TDT 0.6B v2 INT8 | speech recognition | 460 MB | CC-BY-4.0 | yes |
| Gemma 3 1B Instruct Q4_K_M | transcript correction | 769 MB | Gemma Terms | no |

Three files, about 1.2 GB in total, all of them required. 3.0 has no RAM tiers and no
per-task model choice: Gemma corrects transcript lines and is asked to do nothing else. It
was chosen because it has no reasoning mode and so cannot emit think tokens — the failure
that made the previous model spend minutes and a thousand tokens on a one-line correction.

A phone below `DeviceCapabilities.MIN_RAM_GB` (6 GB) still records and transcribes; it is
simply not offered correction.

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

### Whether a phone is offered the model at all

One question, not a ladder: is there at least `DeviceCapabilities.MIN_RAM_GB` (6 GB) of
memory, and is there enough free right now for `OnDeviceModel.REQUIRED_FREE_MB` beside the
recorder. Both answers are reported rather than inferred — `OnDeviceModel.problem()` returns
the reason in words, and Settings shows it.

The measurement is still snapped by `DeviceCapabilities.marketedRamGb` to the size the phone
is sold as, because no Android API reports the RAM on the box: a 12 GB phone reports 10.5 to
11.6 GiB, and comparing that raw figure against a threshold is how a 12 GB phone used to be
treated as an 8 GB one.

`app/src/test/java/com/recorder/app/models/ModelCatalogTest.kt` asserts the manifest's chat
filename is the one `OnDeviceModel` loads. Without that, a renamed file downloads successfully
and is then never found, and the only symptom is correction that never runs.

## Adding a model by hand

```json
{
  "id": "some-model-q4",
  "role": "small_chat",
  "displayName": "Some Model (Q4_K_M)",
  "fileName": "some-model-q4.gguf",
  "url": "https://…/Some-Model-Q4_K_M.gguf",
  "revision": "main",
  "sizeBytes": 1120000000,
  "sha256": "…64 hex chars…",
  "license": "Apache-2.0",
  "required": true,
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

- `role` — `vad`, `asr` or `small_chat`. An unknown role is skipped, not fatal. Replacing the
  `small_chat` entry also means changing `OnDeviceModel.FILE_NAME`, which the test above
  checks, and its headroom figure.
- `required` — the user cannot untick it, and setup is not "complete" without it. Everything
  in 3.0's manifest is required.
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
| `small_chat` | `files/models/llm/*.gguf` |

App-private storage, so uninstalling the app removes the models, and nothing else on the
phone can read your transcripts or the models beside them.

## Developer fallback

`scripts/fetch_models.sh` still pushes models over ADB for development. It uses `run-as`,
which only works on a debuggable build, so point it at the debug applicationId:

```bash
PKG=com.recorder.app.debug ./scripts/fetch_models.sh --vad --asr
```

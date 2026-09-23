# Always-On Local Recorder

An Android app for a phone whose only job is to listen. It records continuously,
transcribes on-device, flags what you told it to care about, files everything into
folders, and lets you ask questions about your own day — with the radio off.

**Raw audio never leaves the device.** There is no cloud speech-to-text, no upload path
for PCM, and nothing outside `core-audio` / `core-asr` can even see the audio buffers.
The optional heavy tier sends *text only*, only when you switch it on, and only to the
provider you configured.

Built for a Motorola Razr 2025 (Dimensity 7400X, 8 GB RAM) with no SIM in it, but the
model tiers are chosen from the RAM actually present, so the same APK scales up on a
bigger phone without a rewrite.

---

## Release signing (one-time, needed before a tagged release can publish)

Every build must be signed with the *same* key, or installing a new version over an old
one fails with a signature mismatch. Run these on any computer with the JDK installed
(`keytool` ships with it), then paste the four values into the repo's secrets.

```bash
# 1. Generate the keystore. Use a long passphrase and keep this file safe:
#    lose it and you cannot update an installed app ever again, only uninstall and reinstall.
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias recorder \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=Local Recorder, O=Personal, C=US"

# 2. Print the base64 of the keystore (single line, no wrapping).
base64 -w0 release.keystore    # macOS: base64 -i release.keystore | tr -d '\n'
```

Then in GitHub: **Settings → Secrets and variables → Actions → New repository secret**, and
add four secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the single-line base64 from step 2 |
| `KEYSTORE_PASSWORD` | the keystore passphrase from step 1 |
| `KEY_ALIAS` | `recorder` |
| `KEY_PASSWORD` | the key passphrase (the same one, unless you set a separate one) |

Back up `release.keystore` somewhere off the computer. It is the only thing that lets a
future build update an installed app.

To cut a release once the secrets exist:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

That builds both flavours signed, verifies the signatures, writes `SHA256SUMS`, and
publishes them as a GitHub Release. `versionCode` is derived from the tag
(`0.2.0` → `200`), so the in-app updater can compare versions.

## Getting an APK on the phone

You do not need Android Studio or a local SDK. Every push builds installable APKs in CI.

1. Open the repo's **Actions** tab → the newest **Build APK** run.
2. Download the artifact:
   - `recorder-razr-debug` — flip phones, includes the cover-screen UI.
   - `recorder-standard-debug` — everything else, no cover-screen UI.
3. Unzip it and install:

```bash
adb install -r -g app-razr-debug.apk
```

Or build locally if you do have the SDK:

```bash
./gradlew assembleRazrDebug        # app/build/outputs/apk/razr/debug/
./gradlew installRazrDebug         # build and install in one step
```

## First run, in order

```bash
# 1. Confirm the phone is there and is what you think it is
adb devices
adb shell getprop ro.product.model
adb shell cat /proc/meminfo | grep MemTotal

# 2. Install, lock the phone down, and start recording — one command
./scripts/setup_new_device.sh --apk app-razr-debug.apk

# 3. Push the on-device models (see "Models" below)
./scripts/fetch_models.sh --sherpa --vad --asr
```

Open the app once and accept the microphone prompt. After that it survives reboots,
app updates, and having the app swiped away.

## Models

The APK ships with no model weights — they are large, they have their own licences, and
they move faster than this code does. What you get without them is an app that records,
segments speech, and stores nothing but empty transcripts. What you get with them is the
real thing.

| What | Where it goes | Fetched by |
|---|---|---|
| Silero VAD (`silero_vad.onnx`) | app storage `files/models/` | `fetch_models.sh --vad` |
| Parakeet-TDT INT8 (encoder/decoder/joiner/tokens) | `files/models/asr/` | `fetch_models.sh --asr` |
| sherpa-onnx Android AAR | `core-asr/libs/` (build time) | `fetch_models.sh --sherpa` |
| llama.cpp Android AAR | `core-llm/libs/` (build time) | `fetch_models.sh --llama` |
| GGUF chat models | `files/models/llm/` | `fetch_models.sh --llm x.gguf` |

Both AARs are build-time drop-ins: the modules detect them and compile the real engine in.
Without them the app still builds and runs — `AsrEngineFactory` hands back a no-op engine
and the local chat says so plainly, rather than pretending.

The app looks for these GGUF filenames, largest first, and picks the biggest one that
fits in memory *at that moment*:

- Small tier (Phase 2 chat): `phi-4-mini-q4.gguf`, `qwen3-1.7b-q4.gguf`, `gemma-3-1b-q4.gguf`
- Heavy tier: `gemma-3-4b-q4.gguf` (12 GB phones), `qwen3-8b-q4.gguf` (16 GB+)

On an 8 GB Razr the heavy local tier correctly reports itself unavailable instead of
loading something that will be OOM-killed next to the ASR pipeline. Settings → *This
device* shows the detected RAM tier and the exact reason.

## How it fits together

```
mic ─► AudioCapture ─► VAD ─► SpeechSegmenter ─► AsrEngine ─► Room
        (core-audio)                              (core-asr)   (core-storage)
                                                                  │
                              KeywordWatcher ◄────────────────────┤
                              FolderClassifier ◄──────────────────┤  local model
                              HeavySyncWorker ◄───────────────────┘  (WorkManager,
                                    │                                 charging + idle)
                                    ▼
                              LlmProvider ──► Claude / OpenAI / Gemini / local server
                                    │
                                    ▼
                              ConnectorToolGateway ──► drafts only ──► your approval
```

| Module | What lives there |
|---|---|
| `core-audio` | Continuous `AudioRecord`, Silero VAD (ONNX Runtime) with an energy-gate fallback, pre-roll/hangover segmenter |
| `core-asr` | `AsrEngine` interface; sherpa-onnx Parakeet engine compiled in when its AAR is present |
| `core-storage` | Room: `transcript_segments` (+ FTS mirror), `folders`, `flagged_items`, `pending_actions`; DataStore settings |
| `core-llm` | One `LlmProvider` interface over Claude / OpenAI-compatible / Gemini / on-device; RAM tiering; FTS-backed RAG |
| `core-connectors` | Gmail, Calendar, Drive; the draft-only gateway |
| `app` | `RecordingService`, boot receiver, keyword tagging, Compose UI, cover screen, `HeavySyncWorker` |

### The two rules the code enforces

1. **Audio stays put.** Only `core-audio` and `core-asr` touch sample buffers. Nothing
   downstream receives them; the network layer only ever sees strings.
2. **Nothing sends itself.** A connector tool marked `outbound = true` cannot be executed
   by the model at all. It writes a row to `pending_actions` and the model is told a draft
   was queued. `ConnectorToolGateway.approve()` is the only path to actually sending, and
   it is called from a button.

### Recording is decoupled from everything

`RecordingService` never learns about the hinge, the display, or which activity is
foreground. Fold state is read by the UI only (`DevicePosture`). Opening or closing the
phone cannot restart, pause, or interrupt recording — closing it just means a different
activity is drawing.

## Using it

- **Live** — transcript as it lands, newest first.
- **Ask** — questions answered from your own transcripts by the on-device model. Works
  in airplane mode. FTS pulls the relevant lines, the model reads only those.
- **Flagged** — every hit on a trigger phrase. Defaults: *business idea, remind me,
  follow up, email this, meeting*. Editable in Settings.
- **Drafts** — anything the heavy tier wants to send. Approve or discard.
- **Settings** — triggers, provider + key, connectors, and what this device can run.

On a Razr, close the phone and open **Recorder Cover** from the cover-screen app list:
live transcript plus the same offline chat, on the outer display.

## The heavy tier

Off by default, on every device. When you switch it on and enter a key, a `WorkManager`
job runs on charging + idle (or immediately via *Sync now*), batches transcripts the
tier hasn't seen, and gets back folder assignments and drafted actions.

Providers are one interface. Switching between Claude, OpenAI, Gemini, or your own
OpenAI-compatible server is a settings change, never a code change — point the endpoint
at `http://your-box:11434/v1/chat/completions` and it is a local server. Keys are stored
in Keystore-backed encrypted preferences, never in DataStore or plain prefs.

## Connectors

Gmail, Google Calendar and Google Drive, over REST with a refresh token held on the
device. Reads run immediately; sends and event creation always become drafts.

Adding your own connector is one interface and one registration call — see
[docs/CONNECTORS.md](docs/CONNECTORS.md).

## Provisioning other people

```bash
./scripts/setup_new_device.sh                 # flip phone, cover UI
./scripts/setup_new_device.sh --no-cover-ui   # normal phone, no cover UI
```

`provision.sh` (called by the above, or run alone) disables the telephony stack — no SIM
is ever going in these phones — freezes the Play Store, exempts the recorder from Doze,
and pushes every other user app into the restricted standby bucket. All of it is
reversible; the script prints how.

**Keys for other people:** the heavy tier ships **disabled** for everyone, including you,
and no key is baked into any build. Whoever holds the phone turns it on in Settings and
enters their own key. Nobody's transcripts pass through anyone else's account, and a
phone handed to your dad or an associate is fully functional — recording, transcription,
tagging, and offline chat all work — without ever enabling it.

## Verifying it behaves

```bash
adb shell dumpsys deviceidle whitelist | grep com.recorder.app   # exempt from Doze
adb shell am get-standby-bucket com.recorder.app                 # should be active
adb shell dumpsys activity services com.recorder.app             # RecordingService alive
adb shell top -o %CPU -n 1                                       # only the recorder busy
adb logcat -s RecordingService AsrEngineFactory LocalModelSelector
```

To confirm nothing is talking to the network, put the phone in airplane mode: recording,
transcription, tagging and the Ask tab all keep working.

## Tests

```bash
./gradlew test              # unit tests, all modules
./gradlew assembleDebug     # both flavours
```

CI runs both on every push and uploads the APKs.

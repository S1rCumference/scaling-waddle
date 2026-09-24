# Recorder

A phone whose only job is to listen. It records continuously, transcribes on the device,
flags the things you told it to care about, files them into folders, and answers questions
about your own day with the radio off.

**→ [Install it from the phone's browser](https://s1rcumference.github.io/scaling-waddle/)**

No computer is needed to install, set up, or update it. No Play Store, no account, no
subscription.

---

## The two rules the code enforces

1. **Recordings never leave the phone.** Speech becomes text using this phone's processor.
   Only `core-audio` and `core-asr` ever see audio samples; nothing downstream receives them,
   and the network layer only ever handles strings. There is no cloud transcription.
2. **Nothing outbound sends itself.** A connector tool marked `outbound` cannot be executed by
   a model at all — it writes a draft to `pending_actions` and the model is told a draft was
   queued. The only path to sending is a human tapping Approve.

The heavy tier (Claude, OpenAI, Gemini, or your own server) is **off by default**, sends text
only, and no API key is ever baked into a build.

## Target device

Motorola Razr+ 2025 — Snapdragon 8s Gen 3, 12 GB RAM, Android 15, 4,000 mAh, 4.0in
1272×1080 cover screen. No SIM, no Google account.

It also runs on other phones: 8 GB and 16 GB+ RAM tiers are supported and chosen at runtime,
and a `standard` build without the cover-screen UI exists for non-flip phones. Android 8 is
the floor for recording; the on-device assistant needs Android 11 (see Limitations).

## Installing, on the phone

1. Open **[the install page](https://s1rcumference.github.io/scaling-waddle/)** in the phone's
   browser and tap **Download for Razr**.
2. Tap **Open** when it finishes. Android will refuse to install from an unknown source: tap
   **Settings**, turn on **Allow from this source**, press back, tap **Install**.
3. Open the app. The wizard covers permissions, downloads the speech models over Wi-Fi
   (about 460 MB, once), walks you through the cover-screen setting, and ends with a test.

The most reliable setup — where recording restarts by itself after a reboot — is the
device-owner QR path on a factory-reset phone, also on the install page. It is optional.

### What you have to do yourself

| Thing | Why it is not automatic |
|---|---|
| Enable GitHub Pages | Settings → Pages → Source: **GitHub Actions**. Until then the install page is not published. |
| Run the benchmark and battery test | They need the phone. The tables below are empty for that reason. |

## What it does

- **Live** — the transcript as it lands.
- **Ask** — questions answered from your own transcripts by the on-device model. Works in
  airplane mode: an FTS search pulls the relevant lines and only those go to the model.
- **Flagged** — every hit on a trigger phrase. Defaults: *business idea, remind me, follow up,
  email this, meeting*. Editable.
- **Drafts** — anything the heavy tier wants to send. Approve or discard.
- **Settings** — setup status, models, benchmark, power report, provider and key, connectors,
  lockdown, and reboot behaviour.

Close the phone and the cover screen shows the live transcript and the same offline chat.

## Measured numbers

Both tables are empty because neither can be produced without the phone. Filling them in is
the last step of bring-up.

### Battery

Procedure: charge to 100%, unplug, use the phone normally for a full day with the recorder
running, then read Settings → **Power report**.

| Measurement | Target | Actual |
|---|---|---|
| Microphone uptime | ~16 h | |
| Speech as share of uptime | 5–15% typical | |
| Decoder CPU seconds per hour | | |
| Decoder duty cycle | < 5% | |
| Battery drain per hour | ~6%/h for a 16 h day | |
| Model loads / unloads | low; high means thrashing | |

### Local models

Procedure: install a model in the wizard, then Settings → **Run benchmark**. It loads the
model, runs the llama.cpp benchmark, and unloads.

| Model | Size | Load time | Prompt tok/s | Gen tok/s | Peak RSS |
|---|---|---|---|---|---|
| Qwen 3 1.7B Q4_K_M | 1.03 GB | | | | |
| Phi-4-mini Q4_K_M | 2.32 GB | | | | |
| Qwen 3 4B Q4_K_M | 2.33 GB | | | | |
| Qwen 3 8B Q4_K_M | 4.68 GB | | | | |

The open question worth answering first: **can this 12 GB phone hold the 8B instead of the
4B** with the ASR pipeline resident? The tier table assumes not.

## How it fits together

```
mic ─► AudioCapture ─► VAD ─► SpeechSegmenter ─► AsrEngine ─► Room
        (core-audio)                              (core-asr)   (core-storage)
                                                                  │
                              KeywordWatcher ◄────────────────────┤  substring only
                              FolderFilingWorker ◄────────────────┤  batched, charging+idle
                              HeavySyncWorker ◄───────────────────┘  charging+idle
                                    │
                                    ▼
                              LlmProvider ──► Claude / OpenAI / Gemini / local
                                    │
                                    ▼
                              ConnectorToolGateway ──► drafts only ──► your approval
```

| Module | What lives there |
|---|---|
| `core-audio` | Continuous `AudioRecord`, Silero VAD via ONNX Runtime with an energy fallback, pre-roll/hangover segmenter |
| `core-asr` | `AsrEngine` seam; sherpa-onnx Parakeet engine |
| `core-storage` | Room: transcripts (+FTS), folders, flagged items, pending actions; DataStore settings |
| `core-llm` | One `LlmProvider` over Claude / OpenAI-compatible / Gemini / on-device; RAM tiering; llama.cpp |
| `core-connectors` | Gmail, Calendar, Drive; the draft-only gateway |
| `app` | `RecordingService`, boot and watchdog recovery, cover screen, wizard, updater, lockdown |

Recording is decoupled from the UI by design: `RecordingService` never learns about the hinge
or which display is active. Fold state is read only by the UI.

## Troubleshooting

**Nothing is transcribed, but the notification says it is listening.**
Settings → *This device*. If speech recognition says "model not downloaded", re-run setup.
Recording without the model produces no text by design rather than failing loudly.

**Recording did not come back after a reboot.**
Expected unless the app is device owner. Android forbids starting a microphone service from
the background: `RECORD_AUDIO` is a while-in-use permission, so the system treats a
backgrounded app as not holding it, and **turning off battery optimisation does not change
this**. Tap the "Recording is paused" notification, or use the device-owner QR path.

**Closing the phone shows Motorola's home screen, not the transcript.**
Motorola decides what may appear on the cover display, and no app can read that setting.
Settings → Display → External display → App settings → Recorder → Allow on external display →
Auto transition. Failing that, open *Recorder Cover* from the cover screen's app list.

**A model download failed or stalled.**
Re-open setup and tap Try again — partial downloads resume rather than restarting. If it
fails immediately, check free space: the wizard needs the file size plus headroom.

**The assistant says it is unavailable.**
Settings → *This device* gives the reason: no model installed, not enough free memory right
now, or Android older than 11.

**Battery is draining faster than expected.**
Settings → *Power report*. A decoder duty cycle far above a few percent means the VAD is
opening on noise; raise the VAD threshold in Settings.

## Undoing everything

| To undo | How |
|---|---|
| The lockdown | Settings → *Lock down this phone* → **Undo lockdown**. Restores exactly the apps it suspended. |
| Device owner | Settings → *Surviving a reboot* → **Remove device owner**. No factory reset needed. |
| The adb provisioning | `adb shell pm enable <package>`, `adb shell am set-standby-bucket <pkg> active` |
| Models, transcripts, keys | Uninstall the app. Everything lives in app-private storage and goes with it. |
| Recording, temporarily | The toggle in the app's top bar. The watchdog respects a deliberate stop. |

## Signing

Every build has to be signed with the same key or updates cannot install over the top of an
earlier one. The key is in this repository: `signing/recorder.keystore`, alias `recorder`,
password `recorder123`. Nothing to configure, no GitHub secrets — tagging is all it takes:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

That builds both flavours signed, verifies the signatures, writes `SHA256SUMS`, publishes a
GitHub Release, and republishes the install page with that build's digests and QR code.

**This key is deliberately not private.** Anyone with the repository can produce an APK that
Android will accept as an update to this app. That is a fine trade for phones you own, and it
is why any checkout builds an installable update with zero setup. Replace it with a real
private key, kept out of the repository, before ever distributing this app to anyone outside
your own devices.

## Developing

```bash
./gradlew test                    # unit tests
./scripts/ci/prepare_natives.sh   # fetch sherpa-onnx, build the llama.cpp AAR
./gradlew :app:assembleRazrDebug
```

The debug build uses the `.debug` application id so it coexists with the release. Models can
be side-loaded over adb instead of downloaded:

```bash
PKG=com.recorder.app.debug ./scripts/fetch_models.sh --vad --asr
```

`scripts/provision.sh` and `scripts/setup_new_device.sh` still work from a computer and remain
the fallback for anything the in-app lockdown cannot do. See [docs/MODELS.md](docs/MODELS.md)
[docs/CONNECTORS.md](docs/CONNECTORS.md), and [docs/BRING_UP.md](docs/BRING_UP.md) for the tap-by-tap first-run test script.

### On-device verification

```bash
adb shell dumpsys deviceidle whitelist | grep com.recorder.app
adb shell am get-standby-bucket com.recorder.app
adb shell dumpsys activity services com.recorder.app
adb shell top -o %CPU -n 1
adb logcat -s RecordingService AsrEngineFactory LocalModelRuntime CoverPresenter

# Reboot behaviour, including the Android 14+ restriction
adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.recorder.app
adb reboot
adb shell am kill com.recorder.app     # watchdog should recover it within the hour
```

## Limitations

Stated plainly, because several matter.

**Nothing in this app has run on a phone.** It builds, its logic is unit tested, and CI
asserts both native runtimes are in the APK — but no line of it has executed on Android.
First-run bugs are likely.

**Device-specific behaviour is unverified.** Whether Motorola's cover-screen transition works
as described, what the Razr's display ids actually are, and whether `climanager` behaves as
the community reports — all need the phone. Settings dumps the real display list so those
answers can be read off the device rather than guessed.

**GGUF digests are missing.** Hugging Face stores the SHA-256 as the LFS `oid`, which the
available interface does not expose, so the five chat models ship size-checked only. That
catches truncated downloads, not substituted files. The VAD and ASR models do have verified
digests.

**Chat quality is capped by the binding.** ARM's llama.cpp wrapper exposes no thread count, no
context size, and no explicit chat template, and its Kotlin is built with a newer compiler than
this project, so `-Xskip-metadata-version-check` is in use. A purpose-built JNI wrapper would
remove all three; the `LocalLlm` seam exists so that swap touches one file.

**The assistant needs Android 11.** That wrapper's logging header uses an API 30 symbol. The
recorder itself runs on Android 8; on older phones the assistant reports itself unavailable.

**Shizuku is not integrated.** Its API has no public shell executor, so the lockdown needs
device owner. Without it, `provision.sh` from a computer is the path.

**32-bit phones are excluded.** The APK is arm64 only; two native runtimes at ~24 MB per ABI
made four-ABI builds indefensible.

**Battery and model numbers are unmeasured**, as above.

**Untested at scale.** Nothing has run for a full day, so database growth, FTS performance
after months of transcripts, and thermal behaviour during long decoding are unknown.

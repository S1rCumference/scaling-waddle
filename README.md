# Recorder

A phone whose only job is to listen. It records continuously, transcribes on the device, keeps
a log you can read by day and hour, flags the phrases you told it to care about, and exports
whatever slice of it you ask for.

**→ [Install it from the phone's browser](https://s1rcumference.github.io/scaling-waddle/)**

No computer is needed to install, set up, or update it. No Play Store, no account, no
subscription, no network permission that matters after the models are downloaded.

---

## The rule the code enforces

**Nothing derived from the microphone leaves the phone.** Speech becomes text using this
phone's processor. Only `core-audio` and `core-asr` ever see audio samples, and 3.0 has no
cloud path at all — the vendor providers, the API key store and the connector module were
deleted rather than switched off, so there is no code left that could send a transcript
anywhere. The only outbound traffic the app makes is fetching its own models and its own
updates from GitHub.

## What 3.0 is

3.0 is a deletion release. What is left:

- **Record** continuously, through folds, reboots and app kills.
- **Transcribe** on the device with Parakeet TDT 0.6B, gated by Silero voice detection.
- **Read** the result as a calendar: today by hour, earlier days by day.
- **Flag** lines matching your trigger phrases.
- **Correct** transcripts with one small model, twice a day at most.
- **Export** any slice of it, which is the part this release is actually about.

What went, and is not coming back in this line: asking questions about your transcripts,
summaries, action items, drafted follow-ups, Gmail/Calendar/Drive connectors, automatic
foldering, cloud models, RAM tiers and per-task model choices.

## Export

One screen, reachable from the Logs toolbar, from a group, or from a long-press selection.

| | |
|---|---|
| Date range | Today, Yesterday, Last 7 days, This month, All, or a custom pair of days, both inclusive |
| Time of day | An optional window applied to every day in the range. Crossing midnight is one window, not an empty one |
| Keywords | Include and exclude fields, comma separated, case insensitive, matching whole lines. Include is any-of or all-of; exclude wins |
| Source | Original, corrected, or both |
| Format | Markdown, plain text, CSV (`date,time,text,source,flagged`), JSON Lines |
| Grouping | By day, by hour, or flat |
| Destination | Share sheet, `Download/Recorder/`, or the clipboard |

A live match count and estimated size sit above the button, so nothing exports blind. Every
setting is remembered as the next export's default, with a Reset button. Files are named
`recorder_<YYYY-MM-DD>_to_<YYYY-MM-DD>.<ext>`; times inside follow the app's 12/24-hour
setting, except CSV and JSON Lines, which stay ISO and 24-hour so a spreadsheet does not have
to guess.

## The AI, such as it is

One model, Gemma 3 1B Q4, doing one job: re-reading a transcript line with the lines around it
and fixing misheard words. The original is never overwritten — corrections are appended beside
it and labelled with the pass and the model.

It was chosen for what it cannot do. It has no reasoning mode, so it cannot emit think tokens,
which is what made the previous model spend two minutes and a thousand tokens on a one-line
correction.

It runs in exactly two situations:

1. **Overnight**, once, while charging, with the screen off, above 30% battery, and not already
   hot. It corrects the day that just ended.
2. **On demand**, from "Correct this group" in Logs, with a Cancel button while it runs.

Every ceiling is enforced in code, not asked for: 1200 input tokens a batch, 256 output, 45
seconds a batch, ten minutes for the whole pass. Anything that runs past those is abandoned and
the reason recorded; nothing retries. The model is unloaded the moment the pass ends.

## Target device

Motorola Razr+ 2025 — Snapdragon 8s Gen 3, 12 GB RAM, 4.0in cover screen. No SIM, no Google
account. A phone with less than 6 GB of RAM still records and transcribes; it is told that
correction will not be offered.

## Models

Three files, about 1.2 GB, downloaded once over Wi-Fi from the setup wizard and then never
again. See [docs/MODELS.md](docs/MODELS.md).

| Model | Role | Size | Licence |
|---|---|---|---|
| Silero VAD v6.2.3 | voice detection | 2 MB | MIT |
| Parakeet TDT 0.6B v2 INT8 | speech recognition | 460 MB | CC-BY-4.0 |
| Gemma 3 1B Instruct Q4_K_M | transcript correction | 769 MB | Gemma Terms |

Nothing is bundled in the APK. "Installed" means the exact byte count from the manifest for a
plain file, or a complete install record for an unpacked archive — not "a file of that name
exists", which is true of a download that stopped one byte in and is what used to crash the app
on every launch.

## Modules

```
app           the UI, the recording service, correction scheduling, export
core-audio    microphone capture, voice detection, segmentation
core-asr      speech recognition (sherpa-onnx / Parakeet)
core-llm      the one on-device model, behind one interface
core-storage  Room, DataStore, diagnostics
```

## Building

```bash
./gradlew :app:assembleRazrRelease :app:assembleStandardRelease
./gradlew test
```

Both release variants are signed with the committed key, so every build installs over the
previous one. CI builds and publishes on every push to the development branch, deriving the
version from `recorder.versionName` in `gradle.properties`.

## When something looks wrong

The phone can answer for itself, without a computer:

- **Settings → Data → Diagnostics** — the last 300 things worth knowing about, and why.
- **Settings → Data → Self-diagnostic report** — one block of figures: what each AI pass cost,
  how much speech became text, how the detector is scoring, when the phone charged or got hot,
  and everything that failed. Built for pasting into a conversation with a model.
- **Settings → AI → Model → Check files** — whether what is on disk is actually complete.
- **Safe mode** — if start-up fails twice, the next launch starts nothing and loads no model, so
  the screen is reachable and the fault can be repaired from it.

## Honest limitations

- Transcription is English only, and it is a small model. Names, jargon and crosstalk are where
  it struggles; correction helps and does not fix it.
- The speaker markers in the log are a guess from loudness and zero-crossing rate. Nobody is
  identified, no voice is enrolled, and a noisy room will produce spurious dividers.
- Correction runs once a night. A day spent off the charger is corrected the following night.
- There is no way to ask questions about your transcripts in this release. Export and read them
  somewhere else.
- The models' licences are their own: Gemma is under Google's Gemma Terms, not a standard open
  licence.

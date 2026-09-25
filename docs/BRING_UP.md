# Bring-up test script

The tap-by-tap run-through for the first time this touches a real Razr+. Follow it in order;
each section says what should happen and what it means when it does not.

Nothing here has been executed on a phone, so treat a failure as expected information rather
than a surprise. Where something needs reporting back, it says so.

---

## 0. Before the phone

- [ ] Enable Pages: **Settings → Pages → Source: GitHub Actions**.
- [ ] Tag a release: `git tag v0.2.0 && git push origin v0.2.0`.
- [ ] Confirm the run published: Actions → the tag's run → `release` and `pages` both green.
- [ ] Open `https://s1rcumference.github.io/scaling-waddle/` on any device. The download
      buttons should show a real size and version, and the SHA-256 boxes real digests.

If `pages` failed with a permissions error, Pages is not set to GitHub Actions yet.

## 1. Install, phone only

On the Razr+, inner screen, connected to Wi-Fi.

1. Open the install page in Chrome. Tap **Download for Razr**.
2. Chrome warns about the file type → **Download anyway**.
3. Tap **Open** in the download notification.
4. "For your security…" → **Settings** → turn on **Allow from this source** → back.
5. **Install** → **Open**.

Expected: the app opens on the wizard's first screen, showing *12 GB of memory, tier MID_12GB*.

**If it says LOW_8GB**, the RAM snapping is wrong on this device — report the number shown in
Settings → *This device* under "RAM".

## 2. Permissions

1. **Next** to the permissions step → **Grant permissions**.
2. Microphone → **While using the app**. Notifications → **Allow**.

Expected: both rows show ✓, and a "Recorder / Listening" notification appears.

## 3. Models

1. **Next** to the models step.
2. It should preselect **Parakeet TDT** (required), **Silero VAD** (required) and
   **Qwen 3 1.7B** (all day) and **Qwen 3 4B** (charging only). Untick Qwen 3 4B for a
   faster first run; it is 2.5 GB and only used while plugged in.
3. **Download**.

Expected: progress per model, then "Installed" on each. About 460 MB for the required two.

- **"Waiting for Wi-Fi"** with Wi-Fi connected → the network is metered; tap *Use mobile data
  anyway* or change the Wi-Fi's metered setting.
- **Checksum did not match** → report it. Both required models have verified digests, so this
  means the download was corrupted or the source changed.
- **Stalls** → back out and re-enter the step; it resumes rather than restarting.

## 4. Transcription — the moment of truth

1. **Next** through the cover-screen step (section 6 covers it).
2. On the final step, say clearly: *"This is a test of the recorder, remind me to call the
   supplier tomorrow."*
3. Wait about five seconds.

Expected: **Recording running ✓**, **Transcription ready ✓**, and the words appear on the
**Live** tab within a few seconds of you stopping speaking.

If Live stays empty:

```bash
adb logcat -s RecordingService AsrEngineFactory SherpaAsrEnginePlugin
```

- `sherpa-onnx AAR not bundled` → wrong APK; the razr release build should contain it.
- `could not build recognizer` → the model files are wrong. Report the full log line; the
  likely cause is `modelType = "nemo_transducer"` not matching this Parakeet build.
- Nothing at all → the VAD never opened. Try speaking louder, then lower the VAD threshold in
  Settings.

Also check **Flagged**: "remind me" should have produced a flag.

## 5. Correction and export

1. Open a group in **Logs** → **Correct this group**.

Expected: a progress line with a Cancel button, then a count of corrected lines. Switch the
group to **Corrected** or **Both** to see them; the original is always kept.

2. **Logs → Export** → Today → Markdown → Copy to clipboard.

Expected: a match count and size above the button, and text on the clipboard with a heading per
day and a timestamp per line.

**Airplane mode on, then do both again.** They must still work — that is the whole point. There
is no cloud path in 3.0 at all, so a failure that only happens with the radio off would mean
something is reaching the network that should not be.

## 6. Cover screen

1. Settings → Display → **External display** → **App settings** → **Recorder** →
   **Allow on external display** → **Auto transition**.
2. Close the phone.
3. Wake the cover screen.

Expected: within about a second, the live transcript on black, with `● 0:0x` elapsed and a
battery percentage. Talk, and lines should appear.

- **Motorola's home screen instead** → report it, and include Settings → *This device* →
  *Displays* read both open and closed. That tells us the real display ids and states, which is
  what the detection is built on.
- **"Flip open to continue"** → the `climanager` whitelist is doing it; the persistent setting
  in step 1 is the fix, and if it does not stick, report that too.

Then **open the phone**: normal Android, recording never interrupted. Check the notification
never disappeared.

## 7. Surviving a reboot

```bash
adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.recorder.app
adb reboot
```

Expected without device owner: a high-priority **"Recording is paused"** notification. Tap it →
recording resumes. This is correct behaviour, not a bug: Android forbids starting a microphone
service from the background.

Expected with device owner: recording resumes with no notification and no tap.

Then:

```bash
adb shell am kill com.recorder.app
```

Expected: within the hour the watchdog notices and either restarts it (device owner) or posts
the resume notification. To avoid waiting, open the app — it starts immediately.

## 8. Battery, over a day

1. Charge to 100%, unplug, note the time.
2. Use the phone normally. Leave recording on.
3. At the end of the day: Settings → **Power report**.

Fill the README table from it. The numbers that matter most:

- **Decoder duty cycle** — above a few percent means the VAD is opening on noise.
- **Model loads / unloads** — a high count means the chat model is thrashing.
- **Speech as share of uptime** — sanity check; 5–15% is typical for a working day.

## 9. Benchmark

Settings → **Run benchmark**. Fill the README table. Then, if you want the bigger model on this
12 GB phone, check the charging-only model: plug the phone in, then Settings → Run benchmark
again. It should pick Qwen 3 4B while charging and the 1.7B on battery.

## 10. Lockdown (optional, device owner only)

1. Settings → **Lock down this phone** — read the plan first. It lists the dialer, messaging,
   the store, and a count of other apps.
2. **Lock down**.
3. Check the phone still works: launcher opens, Settings opens, the recorder runs.
4. **Undo lockdown** and confirm the apps come back.

Do step 4 at least once before relying on it.

---

## Reporting back

The three things most worth sending, whatever happens:

1. Settings → **This device** (whole text, open and closed).
2. Settings → **Power report** after a full day.
3. `adb logcat -s RecordingService AsrEngineFactory CoverPresenter LocalModelRuntime` for
   anything that misbehaved.

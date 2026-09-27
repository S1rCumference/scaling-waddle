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
2. There are two, both required: **Silero VAD** (2 MB) and **Parakeet TDT** (460 MB). Nothing to
   choose — there is no language model to download any more.
3. **Download**.

Expected: progress per model, then "Installed" on each. About 460 MB in total.

- **"Waiting for Wi-Fi"** with Wi-Fi connected → the network is metered; tap *Use mobile data
  anyway* or change the Wi-Fi's metered setting.
- **Checksum did not match** → report it. Both models have verified digests, so this
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

**Deleting a line.** Swipe one of those live lines sideways. Expected: a red band appears behind
it saying "Delete", then "Release to delete" once you are a third of the way across; letting go
removes the line and offers **Undo** beside the status message. Press Undo — the line comes back
in the same place. Then delete one and let the message time out: it is gone for good, and a
search for its text finds nothing.

In **Logs**, long-press a line to select it, tap two more, then **Delete 3**. Expected: one
confirmation naming the count, then the three lines gone.

## 5. Summaries and export

Summaries need a key. Skip to step 3 if you have not set one up — everything else in this
document works without it.

1. **Settings → Summaries.** Pick a provider, follow its link, paste a key, press **Save key**.
   Then **Summarise now**.

Expected: a progress line, then "Wrote n summary(ies)". If instead it says the provider rejected
the key, or refused the request, that is the provider talking — check the key and the model name
on the same screen. If it says rate limited, that is also the provider talking, and it will say
when it will try again.

2. Open an hour in **Logs → Summarise**.

Expected: a short name above the lines with a couple of sentences under it. Go back to
**All logs**: that hour's row now carries the name. Then open **The whole day** and press
**Summarise** — it summarises whichever hours have no name yet, then the day from those names, so
it takes longer the first time and is one short request after that.

Read the paragraph against what you actually said. The one failure that matters is a summary that
asserts something was decided or agreed when it was not; if you see that, it is a bug worth
reporting with the hour's transcript, not a rough edge.

3. **Logs → Export** → Today → Markdown → Copy to clipboard.

Expected: a match count and size above the button, and text on the clipboard with a heading per
day and a timestamp per line. Exporting one day should be one day of lines, not two.

**Airplane mode on.** Recording, transcription, the log, delete and export must all still work —
that is the point of the app. Summarising will not, and should say so rather than hanging: it is
the one thing that needs a network.

## 5a. Stop actually stopping

This was broken until 4.0 — opening the app turned recording back on a few seconds after Stop —
so it is worth proving rather than assuming.

1. **Live → Stop.** The status should go to "stopped" and the notification should go away.
2. Leave the app (home button), come back. It must still be stopped.
3. Fold the phone, look at the cover screen, unfold. Still stopped.
4. Lock the screen, wait a minute, unlock. Still stopped.
5. **Settings → Recording**, toggle recording back on. It should start, and the notification
   should return.

If it restarts on its own at any of steps 2 to 4, that is the old bug and worth reporting with
which step did it.

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
- **Speech as share of uptime** — sanity check; 5–15% is typical for a working day.

## 9. Summaries over a day (optional)

If you set a key up, leave it a day and then check **Settings → Data → Self-diagnostic report**.

The SUMMARIES section says which provider, whether the key is set, whether the schedule is on,
how many summaries are stored, how many spans are still waiting, and what the last requests cost.
"Spans waiting" going down over a day is the pass working. It going up while the number stored
stays put means something is refusing the requests, and the problem line says what.

Then open **Logs** and scroll: the rows should be carrying names. That is the whole feature.

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

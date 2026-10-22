# Phone smoke test (the one check that needs your hands)

The on-device AI has never run on a real phone (`docs/reports/recon-2026-10-22.md`, CLAUDE.md). The code is verified
by unit tests and by a local build, but the model has only ever run on a JVM.

## What you need

- A **64-bit** phone with about **4 GB of RAM** (the AI refuses 32-bit devices and phones Android marks as low-RAM).
- The debug APK from a green CI run: `gh run download -n living-world-debug-apk` (about 62 MB). It is built by the
  `Kotlin check (KMP + Android lint)` job's "Upload the debug APK" step.
- Free space: the model is **345 MB** and is downloaded on demand, plus the app's own data.

## The checks, in order

1. **Install and start.** `adb install -r androidApp-debug.apk`, or copy the APK to the phone and open it (allow
   installs from your file manager). The app starts on the dialogue tab.
2. **The AI is off by default.** Open the **AI** tab (the last tab, ✨). It must say the AI is off and that the model is
   not downloaded yet. Nothing may download before you ask.
3. **Turn it on.** Switch the AI on. The phone must be eligible (64-bit, enough RAM) - otherwise it must say why not.
4. **Download the model.** Start the download. It must show progress, finish in a few minutes, verify the file
   (size + sha256) and report "Downloaded and verified". Interrupt it (cancel) and check that a partial file is left
   behind only as `*.part`, and that a retry works.
5. **Speed test.** Run the speed test. It answers "a reply took N seconds" and gives a verdict (good / usable / too
   slow). Note the number: it decides whether the AI is worth offering on this phone.
6. **✨ lines.** Go to the dialogue tab and talk to the merchant. Some replies must be marked as AI-written. Each AI
   line must only state what that NPC believes (the grounding check drops anything else) - if a line mentions a name,
   a place or an item the merchant never heard about, that is a bug: report the exact sentence.
7. **The AI dies.** With the AI on, kill the AI process (`adb shell am force-stop com.livingworld.game.android` is too
   blunt - instead use the system "recent apps" swipe or `adb shell am kill <pid of the :ai process>`). The app must not
   crash; the AI must switch itself off with a notice, and the game must stay usable. Turn it back on and check that it
   works again.
8. **Leave and come back.** Leave the app (home), come back, and check that the AI state and the model survive, and that
   the app does not re-download the model.

## What to report back

- The phone model and the RAM the app reports.
- The speed-test verdict and the seconds it quoted.
- Whether the ✨ lines looked grounded (quote any that did not).
- Whether the AI turned itself off when you killed it, and whether the app survived.
- Anything that crashed, hung, or downloaded without being asked.

A short note in the PR (or as a new issue) is enough - the observations are what the plan needs for the W5 items
("verify the model digest on every load", "keep the model out of backups", "fail a pending request on release").

# BeachScore phase 2 — implementation plan (voice commands)

Spec: section "Phase 2 — detailed design" in
`docs/superpowers/specs/2026-11-11-beachscore-design.md` (source of truth).
Execution: inline in one session, test-first for the pure parts, small commits on `master`.

## Pinned versions (checked 2026-11-11)

- `com.alphacephei:vosk-android:0.3.75` (latest release on Maven Central; pulls `jna` 5.18.1).
- Models from alphacephei.com: `vosk-model-small-de-0.15` (46.5 MB zip) and
  `vosk-model-small-en-us-0.15` (41.2 MB zip), pinned by SHA-256.

## Model packaging decision

Models are **not committed**. A Gradle task `prepareVoskModels` downloads both zips once
into a git-ignored cache (`/.vosk-cache/`, survives `clean`), verifies SHA-256, unpacks them
into a generated assets directory (`assets/vosk/de`, `assets/vosk/en`) with a file manifest,
and is wired into every variant's assets. A clean clone builds with `./gradlew assembleDebug`
and no manual step (network is needed once, as for Maven dependencies). This keeps ~170 MB
of binaries out of git history.

## Tasks

1. **Plan** (this file).
2. **Build wiring** — Vosk dependency, ABI filters, `prepareVoskModels` task, `.gitignore`,
   `RECORD_AUDIO` in the manifest (requested at runtime only when the user turns voice on).
3. **CommandParser** (test-first) — `voice/VoskResult.kt` (minimal JSON reader for Vosk final
   results), `voice/CommandParser.kt`, `voice/VoiceSensitivity.kt` (thresholds in one place);
   `test/.../voice/CommandParserTest.kt` with hand-written and recorded result JSON.
4. **Echo guard** (test-first) — `voice/EchoGuard.kt` (TTS utterance start/done + 500 ms tail)
   and the per-buffer gate (feed / discard results / reset on guard end);
   `test/.../voice/EchoGuardTest.kt`. `TtsSpeaker` reports utterance start/done to it.
5. **Team-addressed command** (test-first) — `ScoreCommand.TeamPoint(team)`; view model
   handles voice through the same TapLock, blocks voice points while the set is won but lets
   "undo" through, and records the diagnostics outcome (accepted / unknown / low confidence /
   locked / echo guard / set won); start/stop listening from setting × foreground ×
   permission; `test/.../ui/ScoreboardViewModelVoiceTest.kt`.
6. **VoiceInput** — `voice/ModelStore.kt` (unpack assets on first use with progress),
   `voice/VoiceInput.kt` (own `AudioRecord` loop on `VOICE_RECOGNITION`, closed grammar,
   word confidences, echo gate, status flow).
7. **UI** — settings (voice commands with permission flow, sensitivity, show what was heard,
   model progress/errors), mic indicator + heard line at top centre, lifecycle hooks in
   `MainActivity`; German and English strings.
8. **Real audio demo** — instrumented test `VoiceRecognitionTest` feeding WAV speech
   (Windows SAPI voices for English, Android TTS synthesised German/English, plus attenuated
   and noisy variants) through the real Vosk recogniser with the bundled models and the
   production parser; negatives must not produce commands.
9. **Verify** — `./gradlew test assembleDebug`, instrumented tests on the emulator, APK size
   before/after, install on the phone with `install -r` (no data clear, no settings change).

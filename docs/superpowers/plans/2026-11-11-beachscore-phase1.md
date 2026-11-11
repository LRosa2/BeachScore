# BeachScore phase 1 — implementation plan

Spec: `docs/superpowers/specs/2026-11-11-beachscore-design.md` (source of truth).
Execution: implemented inline in one session, test-first for pure logic, small commits.

## Toolchain decisions

- JDK 21 on PATH → AGP 9.4.1 (built-in Kotlin, no `kotlin-android` plugin), Gradle 9.6.0
  wrapper, Kotlin 2.3.21 + Compose compiler plugin, Compose BOM 2026.09.00. Same versions as
  the sibling project `E:\AndroidProj\Singletrail`, which already builds on this machine.
- compileSdk 37 (latest installed platform, required by current AndroidX), targetSdk 36,
  minSdk 26.
- JVM tests: JUnit 6 (Jupiter) + kotlinx-coroutines-test. Compose smoke test:
  instrumented (`androidTest`), run on the `Pixel_7_API_34` emulator if it starts.

## Tasks

1. **Scaffold** — `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
   `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew(.bat)`, `.gitignore`,
   `.gitattributes`, `app/build.gradle.kts`, `AndroidManifest.xml` (landscape, single
   activity, no permissions), theme, launcher icon, empty `MainActivity`.
   Verify: `./gradlew assembleDebug`.
2. **ScoreEngine** (test-first) — `score/Team.kt`, `score/MatchState.kt`,
   `score/ScoreEngine.kt`; `test/.../score/ScoreEngineTest.kt`.
3. **Announcer** (test-first) — `score/Language.kt`, `score/NumberWords.kt`,
   `score/Announcer.kt`; `test/.../score/AnnouncerTest.kt`.
4. **TapLock + input seam** (test-first) — `input/Clock.kt`, `input/TapLock.kt`,
   `input/ScoreInput.kt` (`ScoreCommand`, `Side`); `test/.../input/TapLockTest.kt`.
5. **Persistence** — `data/Settings.kt`, `data/MatchCodec.kt` (string codec for
   `MatchState` incl. history, corrupt → null), `data/MatchRepository.kt`,
   `data/SettingsRepository.kt` (DataStore Preferences, behind small store interfaces);
   `test/.../data/MatchCodecTest.kt`.
6. **HardwareKeyInput + TtsSpeaker** — `input/HardwareKeyInput.kt` (vol up → left,
   vol down → right, repeat ignored, only when enabled), `speech/Speaker.kt` interface,
   `speech/TtsSpeaker.kt` (media stream, QUEUE_FLUSH, availability flag);
   `test/.../input/HardwareKeyInputTest.kt`.
7. **ScoreboardViewModel** (test-first) — `ui/ScoreboardUiState.kt`,
   `ui/ScoreboardViewModel.kt`; `test/.../ui/ScoreboardViewModelTest.kt` with fake
   stores, fake speaker and fake clock (lock across sides and sources, swap mapping,
   undo not locked, set-won blocks scoring, speech phrases).
8. **Compose UI + strings** — `ui/ScoreboardScreen.kt` (halves, auto-sized tabular
   numerals, set dots, lock dim/pulse, first-pointer-only tap), `ui/ControlStrip.kt`
   (undo, swap, hold-to-reset with progress, settings), `ui/SetWonOverlay.kt`,
   `ui/SettingsSheet.kt`, `MainActivity` (immersive, keep screen on, key routing, app
   locale), `values/strings.xml` + `values-de/strings.xml`, `locales_config.xml`;
   `androidTest/.../ScoreboardSmokeTest.kt`.
9. **Verify** — `./gradlew test assembleDebug`; boot emulator, install, exercise tap,
   lock, undo, set win, settings; run the instrumented smoke test.

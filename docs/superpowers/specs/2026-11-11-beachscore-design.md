# BeachScore — design spec

Date: 2026-11-11
Status: approved design; phase 1 is in scope for implementation, phase 2 is designed only.

## Purpose

A courtside scoreboard for recreational beach volleyball. One Android device (phone or
tablet) stands next to the court for the whole game. Players walk up and tap to add a
point, and the app speaks the score so nobody has to look. Later (phase 2) points are
added remotely by voice so nobody has to walk to the device.

Success for phase 1: during a real game, points are entered with one tap with sandy or
sweaty hands, never double-counted, the score is readable from the far baseline, and the
announcement is audible outdoors.

## Constraints and assumptions

- Native Android, Kotlin, Jetpack Compose. Single module `app`, single activity.
- minSdk 26, target/compile the latest stable SDK installed at `C:\Android\sdk`.
- Works fully offline. No accounts, no network permission, no analytics.
- Landscape only, immersive full-screen, screen always on while the app is in front.
- Two teams identified by colour: Blue and Red (names fixed in phase 1).
- UI and speech in German and English, selectable in settings (default: device language
  if German, otherwise English).
- Project root: `E:\AndroidProj\BeachScore` (own git repository). Package
  `ch.beachscore.app`.

## Phase 1 — scoreboard

### Main screen

- Screen split vertically into two halves. Initially Blue left, Red right. Each half is a
  solid team colour with the team's points as one very large, high-contrast numeral
  (auto-sized to fill most of the half's height; tabular/monospaced digits so width does
  not jump between 9 and 10).
- The whole half is the tap target: a tap anywhere on a half adds one point to that team.
- Each half shows the team's sets won, small, near the top (e.g. dots or a small number).
- A thin control strip along the bottom centre, deliberately small and away from the main
  tap areas: Undo, Swap sides, New match, Settings.
  - Undo: single tap, reverts the last scoring event (including a set that was just won).
    Disabled when there is nothing to undo.
  - Swap sides: exchanges which team is displayed left/right. Scores follow the team.
  - New match: long-press (about 1 s, with a visible progress fill) — resets points and
    sets to zero. A short tap does nothing but shows a hint "hold to reset".
  - Settings: opens the settings sheet.

### Multi-tap protection

- After an accepted point, **both** halves ignore touches for a lock period (default
  1500 ms; setting: 500–3000 ms in 250 ms steps).
- During the lock, the scoring half flashes/pulses briefly and the numbers are slightly
  dimmed or show a subtle lock indicator, so players see that the tap registered and that
  further taps are ignored.
- Multi-touch: only the first pointer down counts; additional simultaneous pointers are
  ignored.
- The lock applies to every scoring input source (touch, hardware keys, later voice).
  Undo is not blocked by the lock.

### Speech

- After every accepted point the app speaks the score with Android `TextToSpeech`, in the
  selected language, **scoring team's points first** (they serve next), e.g. 5:3 → "five,
  three" / "fünf, drei". Zero is spoken as "zero" / "null".
- A new utterance interrupts (flushes) any utterance still in progress.
- On set win it speaks "Set Blue, fifteen, twelve" / "Satz Blau, fünfzehn, zwölf".
- After an undo it speaks the restored score prefixed with "Correction" / "Korrektur".
- Setting "Announce score" (checkbox, default on) disables all speech.
- Speech uses the media stream; the settings sheet has a "Test voice" button. If the TTS
  engine or the language voice is unavailable, the app keeps working silently and shows a
  one-time, non-blocking notice in settings.

### Set rules

- Setting "Points per set": 15 (default) or 21.
- A set is won when a team reaches the target with a lead of at least 2. No cap.
- On set win: the winner's set counter increases, the win is announced, and a full-screen
  overlay shows "Set Blue 15:12" with a single large "Next set" button. Scoring input is
  blocked while the overlay is shown; Undo remains available and takes the last point
  back (returning to the running set).
- "Next set" resets points to 0:0 and keeps set counters. No best-of-N logic, no match
  end, no side-switch reminders.

### Hardware key input

- Volume-up adds a point for the team shown on the **left**, volume-down for the team on
  the **right** (this is what cheap Bluetooth shutter remotes and media clickers send).
  Key repeat is ignored; the tap lock applies.
- Setting "Hardware buttons score points" (default off, so normal volume control works
  unless the user opts in).

### Settings

Points per set (15/21) · Announce score (on/off) · Language (Deutsch/English) · Tap lock
duration · Hardware buttons score points (on/off) · Test voice.

### Persistence

- The complete match state and all settings are persisted (Jetpack DataStore) on every
  change and restored on start, so process death, rotation of the task, or an accidental
  swipe-away loses nothing. Undo history is persisted too (bounded to the current match).

## Architecture

Units, each with one purpose and testable alone:

- `score/ScoreEngine` — pure Kotlin, no Android imports. Immutable `MatchState`
  (points per team, sets per team, target, set-finished flag, event history) and a
  reducer `apply(state, event): MatchState` for events `PointFor(team)`, `Undo`,
  `NextSet`, `NewMatch`, `SetTarget(n)`. All rules live here.
- `score/Announcer` — pure function `(previous, new, language) → phrase?` that builds the
  text to speak; plus a thin `TtsSpeaker` wrapper around Android `TextToSpeech`.
- `input/ScoreInput` — interface emitting `ScoreCommand`s (`Point(side)`, `Undo`).
  Implementations in phase 1: touch (from the UI) and `HardwareKeyInput`. Phase 2 adds
  `VoiceInput` without touching the rest.
- `input/TapLock` — time-based gate with an injectable clock; decides whether a scoring
  command is accepted.
- `data/MatchRepository`, `data/SettingsRepository` — DataStore persistence.
- `ui/ScoreboardViewModel` — wires inputs → TapLock → ScoreEngine → repository → speaker;
  exposes one `StateFlow<ScoreboardUiState>`.
- `ui/` Compose screens: `ScoreboardScreen`, `SetWonOverlay`, `SettingsSheet`.

Side vs team: inputs speak in **sides** (left/right); the view model maps side → team
using the current swap state, so touch and hardware keys always match what is on screen.

## Error handling

- TTS init failure or missing voice → silent operation plus notice (see Speech).
- Corrupt persisted state → start a fresh match, keep settings if readable.
- No runtime permissions are needed in phase 1.

## Testing

- Unit tests (JVM) for `ScoreEngine`: point counting, set win at 15 and 21, win-by-2 at
  14:14 / 20:20, undo of a normal point, undo across a set win, next set, new match,
  changing target mid-set.
- Unit tests for `Announcer` (order of numbers, zero, set win, correction; both
  languages) and `TapLock` (fake clock).
- View-model tests: lock applies across sides and input sources; side swap maps correctly.
- A Compose UI smoke test: tap left → left number increments; second immediate tap is
  ignored.
- `./gradlew test` and `./gradlew assembleDebug` must pass. Install and exercise on an
  emulator or attached device if one is available.

## Phase 2 — remote scoring by voice (not in the current implementation scope)

Decision: voice commands, not claps. A ball contact sounds much like a clap, and a
1-clap/2-clap code misfires during rallies.

- Offline recognition with Vosk (small German and English models bundled or downloaded
  once), restricted to a fixed grammar: "Punkt Blau", "Punkt Rot", "zurück" / "point
  blue", "point red", "undo". Two-word scoring phrases reduce false triggers.
- Implemented as `VoiceInput : ScoreInput`; goes through the same TapLock and engine.
- The spoken score is the confirmation; a wrong recognition is fixed with "undo".
- Recognition results are discarded while the app's own TTS is speaking.
- Needs `RECORD_AUDIO` and a foreground microphone indicator; setting to enable/disable.
- Open risk: phone microphone range outdoors with wind. Phase 2 begins with a field test
  spike (does the grammar recognise commands from 8–10 m on sand?). Fallback that already
  works after phase 1: a Bluetooth clicker via hardware key input.

Phase 1 must not include any Vosk dependency, microphone permission or voice UI — only
the `ScoreInput` seam that makes phase 2 an addition rather than a rewrite.

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

## Phase 2 — detailed design (added 2026-11-11, in scope now)

Phase 1 is built and running on the user's phone (moto g15 power, Android 15). Phase 2
is built as the real feature with built-in diagnostics, so the first court session is
the range test; there is no separate throwaway spike.

### Behaviour

- Setting "Voice commands" (default **off**). Turning it on requests `RECORD_AUDIO`;
  if denied, the setting stays off and a short explanation is shown. No other new
  permission; still no network permission.
- While the setting is on and the scoreboard is in the foreground, the app listens
  continuously. Listening stops when the app goes to the background and resumes when it
  returns. No foreground service, no background listening.
- Recognition language follows the app language (one model loaded at a time).
- Commands (both word orders count; changed 2026-11-11, see "Spotting commands without
  silence" below):

  | Action        | Deutsch                       | English                       |
  |---------------|-------------------------------|-------------------------------|
  | Point Blue    | "Punkt Blau" / "Blau Punkt"   | "point blue" / "blue point"   |
  | Point Red     | "Punkt Rot" / "Rot Punkt"     | "point red" / "red point"     |
  | Undo          | "zurück"                      | "undo"                        |

- Voice names the **team** (colour), not the side, so it is unaffected by Swap sides.
  This needs a team-addressed scoring command next to the existing side-addressed one.
- Voice points go through the same TapLock as taps and keys. "Undo" is not locked.
- Voice input is ignored while the set-won overlay is shown, except "undo". "Next set"
  stays a touch action.
- Feedback is the existing spoken score. Nothing extra is spoken for a recognised
  command, and nothing at all for unrecognised speech.

### False-trigger protection

- Vosk recogniser constructed with a closed grammar: the command words for the active
  language plus `[unk]`, so other speech maps to unknown instead of the nearest command.
- Only final results count (not partials), but a final result can be forced early (see
  below). Originally the result had to be exactly one command phrase; that is replaced by
  the spotting rules below.
- Per-word confidence (enable word results) must be at or above a threshold for every
  command word. Setting "Voice sensitivity" with three levels (strict / normal / relaxed) maps
  to thresholds; pick sensible defaults and keep them in one place for tuning.
- Echo guard: audio results are discarded while the app's own TTS is speaking and for a
  short tail afterwards (about 500 ms), driven by the TTS utterance start/done
  callbacks. The recogniser is reset when the guard ends so a half-heard own
  announcement cannot complete into a command.

### Spotting commands without silence (added 2026-11-11)

Field test: commands worked only after silence. Any noise or speech directly before or
after made the result "[unk] punkt blau" (dropped), and continuous noise kept Vosk from
ending the utterance at all. At a court there is never silence, so:

- **Grammar**: both word orders of each point phrase, the undo word, about 150 ordinary
  *decoy* words (frequent words, court words, numbers, and words that sound like the
  command words — but no exact homophones such as "read"/"red"), and `[unk]`. With only
  the commands and `[unk]`, the small German model heard ordinary chatter as command words
  with full confidence ("zurück" 1.00 in "Satz Blau, fünfzehn, zwölf"); the decoys give it
  something else to hear.
- **Spotting**: a command counts anywhere in a final result when its words are adjacent:
  the point word next to a team word in either order, or the undo word. `[unk]` and other
  words around it do not matter, and only the command words need the confidence
  threshold. Command words that are not adjacent ("punkt … blau") are no command.
- **More than one command in a result**: the same command repeated ("punkt blau, punkt
  blau") counts once — people repeat themselves, and the tap lock would block a second
  point anyway. Two different commands ("punkt blau punkt rot", "zurück, punkt rot") give
  none (shown as "two commands"): guessing which one was meant could score the wrong
  team, and a missed command costs only a tap.
- **Commands inside speech** (`RejectReason.IN_SPEECH`, shown as "in speech"):
  - Undo counts only with nothing but `[unk]` (noise) around it. Measured: with the
    decoys, "zurück"/"undo" inside ordinary sentences ("der Ball kam zurück über das
    Netz", "can you undo the knot") is heard correctly *and* with confidence 1.00, so no
    threshold can tell it from a command; only isolation can. A missed undo costs a tap.
  - A point counts only with at most 4 other words (not `[unk]`) before it in the same
    result. A command phrase after a long stretch of talk is almost always talk (or two
    sentences meeting: "… das Team in Rot. Punkte …"). Words after it do not matter.
- **Not waiting for silence**: the partial result is checked after every 100 ms of
  audio. When it contains a command and either something follows the command or the
  partial has stayed the same for 200 ms (600 ms for undo, so that "zurück zum Auto"
  shows its following words and is rejected as inside speech), the recogniser is asked for its
  final result at once (with word confidences), which also starts a new utterance. The
  final result then goes through the rules above. A normal pause still ends an utterance
  as before.
- **Diagnostics**: a command spotted inside a longer result is shown as "… punkt blau …"
  (dots where other words were). A result that is only `[unk]` does not replace an
  accepted command's line while it is still visible.
- Measured with `VoiceSpottingTest` (instrumented, real Vosk): commands directly between
  sentences, between noise bursts, under continuous noise and chatter, and both; and
  chatter containing the command words separately or not adjacent, plus the undo word in
  ordinary sentences.

  | `VoiceSpottingTest` (emulator)           | 3.1.0            | 3.2.0                       |
  |------------------------------------------|------------------|-----------------------------|
  | command between sentences (46)           | 0                | 34                          |
  | command between noise bursts (46)        | 30               | 46                          |
  | command under continuous noise+chatter   | 28               | 33                          |
  | sentences around + continuous noise      | 0                | 29                          |
  | end of command → accepted               | median 830 ms    | median 240 ms, max 930 ms   |
  | chatter streams giving a command (144)   | 20 (Normal)      | 4 (Normal)                  |
  | wrong command                            | 0                | 0                           |

  The 46 commands per condition include 16 in reversed word order, which 3.1.0 never
  accepted.

### Diagnostics (this is what makes the court test useful)

- A small microphone indicator on the scoreboard while listening (and a distinct state
  while muted by the echo guard or when the recogniser failed to start).
- Setting "Show what was heard" (default on while voice commands are on): a small,
  unobtrusive line showing the last recognised text, its lowest word confidence and
  whether it was accepted or why it was rejected (unknown / low confidence / locked /
  echo guard). It fades after a few seconds and never covers the digits.

### Technical

- `com.alphacephei:vosk-android` (current stable). Small German and small English
  models bundled in the APK so the app stays fully offline; accept the larger APK.
  Models are unpacked on first use; show progress and handle failure without crashing.
- `VoiceInput : ScoreInput` owns the recogniser and audio capture; a pure
  `CommandParser` turns a Vosk result (text + word confidences) plus language and
  threshold into a command or a rejection reason. `CommandParser` and the echo-guard
  gate are unit-tested on the JVM with recorded/hand-written result JSON; the Vosk and
  microphone parts are thin and verified on device.
- Audio source should favour far-field pickup (compare `VOICE_RECOGNITION` with the
  default source; choose one and note why).

### Microphone choice (added 2026-11-11)

Use case: the phone lies at the sideline, a Bluetooth speaker (normal media playback, no
call mode) and the transmitter of a wireless clip-on microphone are at the net post, and
the microphone's USB-C receiver is plugged into the phone.

- Setting "Microphone" under Voice commands: "Automatic" (default; Android's own routing,
  the behaviour before this setting existed) plus one entry per connected input that
  makes sense: the phone's built-in microphone, USB audio devices/headsets and wired
  headsets. Several built-in microphones (bottom, back, ...) are shown as one entry.
  Bluetooth microphones are left out: they need call-mode (SCO) routing, which would
  also take over the speaker. Entries show a readable name: type plus product name.
- The choice is stored as type + product name, so the same USB receiver matches again
  when it is plugged in later. A stored choice that is not connected stays visible in
  the list as "not connected".
- The choice is applied as the preferred input device of the recorder. While listening,
  devices being plugged in and out are followed: when the chosen device disappears,
  recording falls back to Automatic and keeps listening; when it comes back, it is used
  again. A read error from a vanished device reopens the microphone instead of ending
  in a "failed" state.
- The microphone actually in use (the routed device, not the preference) is shown in
  the Microphone row ("In use: USB · ...") and compactly in the mic indicator on the
  scoreboard ("USB", "Phone", "Headset"), so it can be checked at the court.
- Capture format: the recogniser needs 16 kHz mono 16-bit. If a device cannot be opened
  at that format, the recorder is opened at a format the device offers (its own sample
  rate, mono or stereo) and converted in the app (downmix to mono, low-pass and
  resample to 16 kHz). The conversion and the choice of format are pure functions with
  JVM tests; device selection (device list -> options, stored choice -> device,
  fallback) too.
- No new permission.

### Out of scope

Clap detection, custom wake words, renaming teams, voice "next set" / "new match",
training on the players' voices, Swiss German dialect support.

## Phase 3 — match history, players and statistics (added 2026-11-11, in scope now)

Phases 1 and 2 are built and on the user's phone. The user wants to keep a record of
games so the group can look at statistics afterwards.

### Matches

- A history entry is a **match**: one or more finished sets played with the same
  lineups.
- The set-won overlay gets a second button "New match" next to "Next set". It saves the
  match (including the set just won) and starts a fresh match at 0:0 with zero sets.
  Both buttons are equally large: the user plays single-set matches as often as
  multi-set ones.
- The existing hold-to-reset "New match" in the control strip also saves the match
  before resetting.
- Only finished sets are saved. An unfinished trailing set is dropped. A match with no
  finished set creates no history entry.
- A match is written to history at the moment it is closed (either "New match"). The
  running match keeps living in the existing DataStore state as today, extended with
  the data history needs: match start time, per finished set its score, start/end time
  and point sequence, and the current lineups. Undo across a set win must keep this
  consistent (undoing the set-winning point removes that finished set again).
- Stored per match: id, start time, end time (time of last finished set), target at the
  time of each set, players per team (0–2 each), and its sets. Per set: index, Blue
  points, Red points, start/end time, and the point sequence as a compact string of
  `B`/`R` in scoring order.
- Match result: the team with more sets wins; equal sets is a draw.
- Voice: no new voice commands. "New match" stays touch only.

### Players

- Player list screen: add, rename, remove. Names are unique ignoring case and
  surrounding spaces; empty names rejected. Removing archives the player: hidden from
  pickers and the list, still shown in old matches and statistics.
- Lineup before a game: each half of the scoreboard shows a name area near the top
  (with the sets indicator). Empty state shows a subtle "Players" hint. Tapping it opens
  a picker with the active players as large chips; pick up to two for that team; a
  player cannot be on both teams (picking them for one team removes them from the
  other). The picker also offers "Add player" inline. The name area is a deliberate,
  separate tap target that never scores a point; keep it small enough that the score
  tap area stays dominant, and it follows the team on Swap sides.
- Lineups persist across matches until changed. Changing a lineup mid-match is allowed
  and applies to the whole running match (lineups are per match, not per set).
- History editing: open a match to set or correct its players with the same picker.
  Long-press in the history enters multi-select; actions "Assign players" (sets both
  teams' lineups on all selected matches) and "Delete" (with confirmation).
- Announcements and voice commands keep using Blue/Red (Blau/Rot), never player names.

### History screen

- List, newest first, grouped by day: time, both lineups (or "No players"), set scores
  (e.g. 15:12, 13:15), result highlight for the winning side.
- Match detail: lineups (editable), sets with scores and durations, delete match.
- Reached from a new "History" button in the control strip. From the history screen:
  "Statistics", "Players", "Export CSV". These screens may use normal (non-immersive
  tolerant) layouts but stay landscape; Back returns to the scoreboard.

### Statistics

- Period filter: Today, Last 30 days, This year, All time (default All time).
- Per player: matches, wins, losses, draws, win rate (wins / matches), sets won/lost,
  points for/against and difference. Sortable by at least win rate and matches;
  default sort by win rate, then matches.
- Per pair: same figures for each unordered pair that played together as a
  two-player team.
- Head-to-head: choose a player or a pair; list each opposing player (or pair) with the
  record against them.
- A match counts for a player if that player is in its lineup. A match with a lineup on
  only one side counts for that side's players (and pair) but has no head-to-head.
  Matches with no players are excluded and the screen says how many were excluded.
- All figures come from one pure, unit-tested calculator over a list of matches.

### CSV export

- One row per set. Columns: match_id, match_start (ISO local date-time), set_number,
  blue_player_1, blue_player_2, red_player_1, red_player_2, blue_points, red_points,
  set_winner, set_duration_seconds, match_winner, point_sequence.
- Semicolon-separated, UTF-8 with BOM, CRLF line ends, fields quoted when needed, so it
  opens directly in Excel with Swiss/German regional settings.
- Exports the whole history (or the selection when in multi-select) through the Android
  share sheet using a FileProvider and a cache file. No storage permission.

### Technical

- Room database for matches, sets and players (KSP). Schema version 1 exported;
  repositories expose Flows. Existing DataStore keeps settings and the running match;
  migrate the persisted running-match format so an in-progress match on the user's
  phone survives the update (missing new fields get sensible defaults).
- Pure, JVM-tested units: match-closing logic (running match → history record or
  nothing), statistics calculator, CSV writer, player-name validation.
- No new permissions, no network. German and English strings for everything new.

### Out of scope

Cloud sync or sharing between phones, import, charts, per-point statistics screens
(the point sequence is only stored and exported), player photos, team names, editing
set scores after the fact, voice control of any of this.

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
  tap areas: Undo, Swap sides, New match, menu (since 3.3.0 Undo, Swap sides and the
  menu are icons; see "3.3.0" at the end).
  - Undo: single tap, reverts the last scoring event (including a set that was just won).
    Disabled when there is nothing to undo.
  - Swap sides: exchanges which team is displayed left/right. Scores follow the team.
  - New match: long-press (about 1 s, with a visible progress fill) — resets points and
    sets to zero. A short tap does nothing but shows a hint "hold to reset".
  - Menu (was Settings): opens the side panel with Settings, History, Players,
    Statistics and About.

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

(Replaced in 3.4.0 by "Remote buttons", see below.)

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

  Since 3.3.0 the team and point words are configurable and "Punk"/"points" are also
  default point words; see "3.3.0" at the end.

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
  "Statistics", "Players", "Export CSV". (3.3.0: History, Players and Statistics are
  reached from the menu; the history screen keeps only "Export CSV".) These screens may use normal (non-immersive
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

## 3.3.0 — configurable voice words and a new bottom bar (added 2026-11-12)

### Voice words

- Under Voice commands, three fields: "Blue team", "Red team", "Point". They are stored
  separately for German and English; the panel shows the fields of the current app
  language (recognition follows the app language), titled with that language.
- A field holds one or more single words separated by commas. Empty means the defaults:

  | Field | Deutsch          | English           |
  |-------|------------------|-------------------|
  | Blue  | blau             | blue              |
  | Red   | rot              | red               |
  | Point | punkt, punk      | point, points     |

  "punk" and "points" are new defaults; "points" is no longer a decoy (as a decoy it
  swallowed "points blue"). Both are *alike variants*: they count when heard but get no
  grammar entry of their own. The closed grammar hears them as "punkt"/"point"; as
  separate entries they split the confidence of a clear "point" (0.50-0.64 instead of
  1.00) and the regular commands fell from 142 to 132 of 184 (see below). Words a user
  enters are always their own grammar entries.
- A filled field **replaces** the defaults of that field: only the user's words are
  commands, every combination of a point word and a team word in both orders. The
  default words it replaced are kept in the grammar as **decoys**, so the recogniser can
  hear them as what they are instead of bending them into the new words. Measured, it
  made no difference for the test set (both ways the old commands never scored); kept
  as the safer choice for real voices. User words are removed from the decoys.
- "zurück"/"undo" stays fixed.
- Each field shows its defaults below it and has a reset icon (undo arrow, described
  "Reset … to default") that empties it and saves (validated like a save, since the
  defaults could clash with a word in another field). "Save words" stores all three
  fields. The last save message is cleared when a field is edited, the panel closes or
  the app language changes.
- Validation on save, per language: entries are trimmed and lower-cased; empty entries
  between commas are ignored. Rejected, with a message naming the word:
  - more than one word in an entry ("punkt eins");
  - the undo word;
  - a word used in two fields (counting the defaults of empty fields);
  - a word the Vosk model of that language does not know (checked against the model's
    own vocabulary, `vosk_model_find_word`; the model is loaded for the check, which
    takes about a second). If the model cannot be loaded the words are not saved.
- Hint under the fields: short common words cause more false points.
- Everything from 3.2.0 (spotting, both orders, forced final, in-speech rule, echo
  guard) works on the active word set: `CommandWords` (resolved from the defaults and
  the user's fields) builds the grammar and drives `CommandParser` and
  `EarlyFinalizer`. Changing the words restarts the recogniser. Lineup pills, TTS
  announcements and team colours do not change.

### Bottom bar and menu

- Control strip: Undo = undo-arrow icon, Swap sides = swap-arrows icon, New match stays
  text (hold to reset), and one menu icon (three lines) replaces Settings and History.
- Icons are 48 dp touch targets with 30 dp white glyphs on the dark strip; a long press
  shows the label as a tooltip; each has a content description in both languages.
  Behaviour of each control is unchanged.
- The menu opens the side panel. At its top a short list: Settings, History, Players,
  Statistics, About. Settings is selected when the panel opens and its content shows
  below the list; About shows its content there instead (scrolled into view, since the
  list fills most of the panel in landscape). History, Players and
  Statistics open their full screens; Back from any of them returns to the scoreboard
  (Back from a match detail returns to History). The Statistics/Players buttons on the
  history screen are removed.
- About: app name, "Luis Rosa", version name and release date. Both come from
  `BuildConfig` (`VERSION_NAME`, and `RELEASE_DATE` = the build date, ISO), the date
  formatted for the app language. No hand-maintained strings.

### Measurements (VoiceSpottingTest, emulator)

Same synthetic rig as 3.2.0 (46 command clips × 4 conditions, 144 chatter streams). The
synthetic voices vary slightly between emulator boots (same words, confidences a few
hundredths apart), so 3.3.0 is also compared with the 3.2.0 grammar on the *same audio*
(`sameAudioWithThe320Grammar`; four paired runs, three as in the table, one with 142 vs
141 commands and 4 vs 4 chatter at both levels):

| `VoiceSpottingTest` (emulator)                | 3.2.0 grammar   | 3.3.0 defaults            |
|-----------------------------------------------|-----------------|---------------------------|
| commands recognised (184), same audio         | 142             | 142                       |
| chatter giving a command (144), same audio    | 4 Normal, 6 Relaxed | 5 Normal, 4 Relaxed   |
| wrong command                                 | 0               | 0                         |
| end of command → accepted                    | median 240 ms   | median 240 ms, max 930 ms |
| spoken "Punk Blau"/"red points" (48)          | –               | 36                        |

Across five separate 3.3.0 runs: 141–142 of 184 recognised, chatter 4–6 at Normal and
4–6 at Relaxed. The one extra Normal trigger on the same audio is the price of "points"
as a command word: "… leads by three points. Red serves …" can now give a point (as a
decoy, "points" swallowed it in 3.2.0). The test gate is: same audio, at most one command
fewer and at most one chatter trigger more than the 3.2.0 grammar.

Rejected alternatives (earlier runs): "punk"/"points" as their own grammar entries gave
132/184 and 34/48 variants; with additionally 0.15 confidence slack for the point word,
136/184 but chatter rose to 7/144 at Relaxed. All 24 English variant clips scored; the
German misses are the synthetic voice saying "Punk" as the English loanword, which the
grammar hears as "kann".

Replaced set (German: blue "himmel", red "feuer", point "treffer"), same conditions:

| replaced set                                  | old defaults as decoys | without   |
|-----------------------------------------------|------------------------|-----------|
| "Treffer Himmel" etc. recognised (48)         | 47–48                  | 47–48     |
| old "Punkt Blau" etc. scoring, Relaxed (48)   | 0                      | 0         |
| German chatter giving a command (72)          | 0                      | 0         |
| wrong command                                 | 0                      | 0         |

## 3.3.1 — a pause between the two command words (added 2026-11-13)

Field report (3.3.0, real voice, phone): with more than a short pause between "Punkt" and
"Blau" nothing counts. Vosk's endpointer closes the utterance (the app sets no endpointer
delays, so the model default of about 0.5 s applies) and each half arrives as its own
result; besides, the grammar has no entry for a single command word, so a lone half is
heard as a decoy or `[unk]`.

### Joining halves

- The grammar additionally holds every point word and every team word on its own (not the
  alike variants, not undo), so a half can be heard as itself.
- A *half* is a point word or a team word that is not part of an adjacent command. A
  half followed by nothing but `[unk]` (noise, silence) is *remembered*. When the matching
  other half follows (team after point, or point after team), again with nothing but
  `[unk]` in between, the two count as one point command for that team — whether they are
  in the same final result or in two consecutive ones. Both orders, the active
  `CommandWords` (user words and the alike variants included).
- Window: the pause between the halves, measured from the end of the first half to the
  start of the second with Vosk's word timestamps (seconds of audio, the same clock for
  both halves; independent of when the results arrive). Setting "Pause between words"
  under the voice settings: 1 s, 2 s, 3 s; default 2 s. Persisted like the other voice
  settings; a change restarts the recogniser (no app restart).
- The remembered half is dropped when: any other word (not `[unk]`) comes in between; a
  full command (or two) is recognised; the second half comes too late (shown as "pause
  too long"); the echo guard ends (the recogniser is reset, as before); voice is
  restarted or reconfigured. Undo is never a half. A second half of the same kind
  replaces the remembered one ("punkt … punkt … blau" joins the last "punkt").
- Rules per half as for a command: the in-speech rule counts the words before the
  *first* half in its result (at most 4); the second half can have only `[unk]` before
  it. Confidence of a joined command = the lower of the two halves' confidences, against
  the same threshold (as for adjacent words, where the lowest command word decides).
- Endpointer: the recogniser sets Vosk's endpointer delays so that it waits as long as the
  pause setting for trailing silence (`setEndpointerDelays(5, pause, 20)`; Vosk default
  0.5 s). "Punkt … Blau" then usually stays one utterance and the decoder hears the phrase
  with its context (a lone German "Rot" is often heard as the decoy "Brot"). The two
  words are then adjacent in one result; the window still applies, using the word times
  ("pause too long" as for a join). The join across results covers what the endpointer
  splits (longer pauses, noise). Chosen by measurement, see below.
- Speed: a command said in one go is unchanged (early finalize, about 200 ms). While a
  half is remembered, the early finalizer also ends the utterance as soon as the partial
  result shows the matching half (after only `[unk]`), with the same 200 ms stability or
  a following word, so the second half is acted on without waiting for Vosk's endpoint.
  The window is checked on the final result (the partial has no timestamps).
- Diagnostics: a remembered half shows as "punkt ‥" (waiting for the other word); a join
  shows both halves with the pause, "punkt ‥ blau (1.4 s)", accepted, or rejected as
  "pause too long" / low confidence.

### Measurements 3.3.1 (VoiceSpottingTest, emulator)

Paused commands: each half synthesised alone (3 voices × DE/EN × Blue/Red × both orders =
24 clips per pause), silence of about 0.7 / 1.5 / 2.5 s between, in quiet and under the
continuous noise bed (48 per pause):

| recognised (of 48)               | 0.7 s | 1.5 s | 2.5 s | second word end → accepted |
|----------------------------------|-------|-------|-------|----------------------------|
| setting 1 s                      | 34    | 1     | 0     | median 250 ms, max 590 ms  |
| setting 2 s (default)            | 34    | 32    | 0     | median 230 ms, max 590 ms  |
| setting 3 s                      | 34    | 31    | 36    | median 230 ms, max 590 ms  |
| 2 s, Vosk's default endpointer   | 22–23 | 21–22 | 0     |                            |

English in quiet: 24 of 24 at 0.7 s and 1.5 s. The misses are German (a lone synthetic
"Rot" is heard as "Brot", low confidence on single "Punkt") and the noise bed, whose
background words fill the pause and break the join. Wrong commands: none in quiet; under
the bed one clip at each setting ("point … blue" with a background "red" right after
"point").

Same audio as 3.3.0 (commands in one go, 184; chatter, 144):

|                        | 3.3.0 | 3.3.1 1 s | 3.3.1 2 s | 3.3.1 3 s |
|------------------------|-------|-----------|-----------|-----------|
| commands in one go     | 141   | 141       | 139       | 140       |
| chatter Normal         | 4     | 2         | 4         | 5         |
| chatter Relaxed        | 4     | 4         | 4         | 5         |
| wrong command          | 0     | 0         | 0         | 0         |

A second run gave 140 vs 142 (one go) and chatter 4/4 at 2 s with the long endpointer.
The one-go loss (1–2 of 184) is the single words in the grammar taking a little
confidence from weak pairs ("punkt:0.65 blau" after "schöner Aufschlag"). Latency of
one-go commands unchanged (median 240 ms). Chatter cost of joining: a sentence ending in
the point word followed by one starting with a team word ("… a great point. Red serves")
can join.

## 3.4.0 — remote buttons (added 2026-11-15)

Voice turned out unreliable even indoors. A small Bluetooth media remote worn by a player
(volume +/−, play/pause, next, previous; it pairs as a keyboard / HID device) scores instead.
It replaces "Hardware buttons score points" (volume up = left, volume down = right).

### Settings: "Remote buttons" / "Fernbedienung"

- Three actions: **Blue point**, **Red point**, **Undo**. Each row shows the assigned buttons
  by a readable name ("Volume +", "Next track", "A", or "Key 87" as a fallback), or "Not
  assigned", with **Assign** and **Clear**.
- **Assign** is a learn mode: the row says "Press a button on the remote…" with **Cancel**;
  the next key press from any device is stored for that action. A button already assigned
  to another action moves to this one. Up to 3 buttons per action (two remotes can both
  work); a fourth replaces the oldest. Closing the menu cancels learning.
- Not learnable (they pass through): system keys — Home, Back, Power, App switch, Call,
  End call, Search, Notification, Assist, Voice assist, Sleep, Wake up, Brightness, All apps,
  Settings, SysRq, and "unknown". Everything else can be learned: media keys (next,
  previous, play/pause, play, pause, headset hook), volume keys, Enter, D-pad, letters …
- Actions follow the **team**, not the court side, so they stay right after "Swap sides".

### Behaviour

- A mapped key is consumed on the scoreboard: the volume does not change, no volume panel
  appears, music does not start. Unmapped keys behave as normal. Auto-repeat of a held
  button is consumed and ignored (one press = one action); the matching key-up is consumed
  as well.
- Presses go through the same `ScoreInput` seam and the same tap lock as touch and voice;
  Undo ends the lock. The score is spoken as usual.
- Presses are ignored (the key keeps its normal meaning) while the menu, the lineup picker
  or another screen is open — except in learn mode. With the set-won overlay up they behave
  like touch there: points are blocked, Undo works.
- Confirmation: a small pill under the voice indicator for 2.5 s, "Remote: Blue point ✓" or
  "Remote: Red point ✗ locked", so a player can glance at the result from afar.

### How keys reach the app

Key events from a Bluetooth HID remote go through the input dispatcher to the focused
window: `MainActivity.dispatchKeyEvent` sees volume and media keys before the system acts on
them (the system's fallback — volume change, media session — only runs when the activity
does not consume the key). No permission needed. Remotes that send media buttons over AVRCP
(as audio devices, not as a keyboard) bypass the window and go to the active media session;
they are not supported.

### Migration

The old toggle's key `hardware_buttons` is read once: if it was on and no remote buttons
are stored yet, Volume + is assigned to the team shown on the left and Volume − to the team
on the right at the moment of the first start of 3.4.0 (from the saved swap state). From then
on they follow those teams. The old toggle is removed from settings.

## 3.4.1 — team word alone (added 2026-11-17)

The point field of the voice words can be emptied. Then there is no point word: a team word
alone ("Blau", "Rot", or the user's team words) scores the point.

- The point field now shows its default words ("punkt, punk" / "point, points") instead of
  being empty. Emptying it and saving turns the point word off; the reset icon puts the default
  words back. The default words typed in are stored as "default". The team fields are unchanged
  (empty means default).
- Stored as `-` in the point key of the language; existing installs keep their setting.
- Grammar: the team words and undo, no pairs; the default point words stay as decoys, so the
  old "Punkt Blau" still gives one point for blue.
- The other rules stay: confidence threshold, at most four words of talk before the word, the
  same team twice counts once, both teams in one result give nothing, echo guard, tap lock.
- Expected cost: more false points, since a single colour word turns up in ordinary talk.
  Not measured; only unit tests were run for this version.

## 3.5.0 — minimum confidence as a slider (added 2026-11-23)

Court report: the correct phrase was shown many times but rejected as "unsure" even at
Relaxed (0.70). The threshold becomes a number the user tunes against the heard line.

### Setting

- "Voice sensitivity" (three levels) becomes **"Minimum confidence" / "Mindest-Sicherheit"**,
  a slider from **0.30 to 0.95 in steps of 0.05**, showing the value ("0.55"). A legend line
  keeps the old levels recognisable: Strict 0.95 · Normal 0.85 · Relaxed 0.70. Hint: lower
  accepts more commands and gives more false points; compare with the number next to a
  rejected command in the heard line.
- Default 0.85. Stored as whole hundredths (`voice_min_confidence_pct`), so 0.85 is exactly
  0.85; values read from disk are clamped to the range and snapped to the step.
- Migration: without a stored number, the old `voice_sensitivity` level gives STRICT 0.95,
  NORMAL 0.85, RELAXED 0.70 — nobody's behaviour changes on update.
- Applies wherever the threshold applied (parser, joined halves, one-word mode); a change
  restarts the recogniser (the model stays loaded). The slider saves when released.

### Heard line

- A rejection by the threshold reads "✗ unsure: below 0.70" / "✗ unsicher: unter 0.70",
  next to the command's own confidence, so the gap is visible at a glance.
- A result without per-word scores (Vosk word list missing or not matching the text) was also
  shown as "unsure"; it now has its own reason, "no word scores" / "keine Wortwerte".

### One-word team words (VoiceSpottingTest.oneWordTeamWords, emulator, 2026-11-23)

One-word mode (point field empty, 3.4.1). Each word said alone, three TTS voices. "Quiet" is
silence or neutral sentences around the word (12 runs per pair), "noisy" is noise bursts,
the noise bed, and speech plus bed (18). Chatter: 36 streams × with/without bed (72).
Tabulated from one run at 0.30 (a command accepted at 0.30 with confidence c is accepted at T
exactly when c ≥ T).

| pair (blue/red)  | quiet 0.85 | noisy 0.85 | noisy 0.50 | wrong | chatter 0.85 / 0.50 | right word's confidence |
|------------------|------------|------------|------------|-------|---------------------|-------------------------|
| blau/rot         | 8 of 12    | 8 of 18    | 8 of 18    | 0     | 22 / 24 *           | min 0.87 (16 of 30)     |
| himmel/feuer     | 11 of 12   | 13 of 18   | 14 of 18   | 0     | 0 / 0 *             | min 0.64 (25 of 30)     |
| ozean/vulkan     | 10 of 12   | 18 of 18   | 18 of 18   | 0     | 5 / 6               | min 1.00 (28 of 30)     |
| delfin/tiger     | 12 of 12   | 9 of 18    | 12 of 18   | 1     | 2 / 3               | min 0.63 (25 of 30)     |
| EN blue/red      | 11 of 12   | 9 of 18    | 12 of 18   | 0     | 42 / 43 *           | min 0.63 (24 of 30)     |
| EN ocean/volcano | 11 of 12   | 17 of 18   | 17 of 18   | 0     | 1 / 1               | min 0.60 (29 of 30)     |

\* The chatter is full of "Blau/Rot"/"blue/red" by design and contains none of the other
words, so it counts every sentence for blau/rot and only mis-hearings for the others.

- A lone "Rot" is heard as "Brot" or "wo" (2 of 15): a recognition miss (shown as
  "unknown"), not a threshold problem — lowering the threshold does not help.
- "Punkte zählen" is heard as "punkte ozean": ozean/vulkan recognises best but scores in
  chatter. Himmel/feuer had no chatter trigger and no wrong point.
- Misses after a long neutral sentence ("Weiter so, gut gemacht …") are the in-speech rule
  (more than 4 words of talk before the word), by design.
- Below about 0.60 nothing more is gained on this synthetic audio; real voices score lower
  (court report), which is what the slider is for.

Per word (right team, 0.85 / 0.50; quiet of 6, noisy of 9): Blau 6/6, 8/9 — Rot 2/6, 0/9 ·
Himmel 6/6, 5/9 — Feuer 5/6, 8/9 (9/9 at 0.50) · Ozean 5/6, 9/9 — Vulkan 5/6, 9/9 ·
Delfin 6/6, 4/9 (6/9) — Tiger 6/6, 5/9 (6/9) · blue 5/6, 0/9 (6/6, 3/9) — red 6/6, 9/9 ·
ocean 5/6, 8/9 — volcano 6/6, 9/9. Himmel is weak under the noise bed (all three voices);
ozean/vulkan's chatter triggers all come from "zählen" heard as "ozean". Three voices per
word only. The heard line now cuts the confidence to two decimals (0.698 reads 0.69), so a
rejected word never shows the threshold's own number.

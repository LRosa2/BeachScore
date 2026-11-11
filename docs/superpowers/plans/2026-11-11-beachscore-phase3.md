# BeachScore phase 3 — implementation plan (history, players, statistics, CSV)

Spec: section "Phase 3 — match history, players and statistics" in
`docs/superpowers/specs/2026-11-11-beachscore-design.md` (source of truth).
Execution: inline, test-first for the pure parts, small commits on `master`, version 3.0.0.

## Design choices where the spec is open

- **Times.** A set starts at its first point and ends at its winning point; the match starts at
  its first point. Wall-clock time comes from an injected clock (the tap lock keeps its own
  monotonic clock).
- **Running match.** `MatchState` gains `matchStartedAt`, the current set's start and `B`/`R`
  sequence, and `finishedSets` (a set is added the moment it is won, so "New match" on the
  set-won overlay includes it). Undo of a set-winning point removes that set again, also after
  "Next set". Lineups live next to the swap state in the persisted match, not in the engine.
- **Migration.** The persisted format becomes `beachscore-match-v2` (key=value lines). A v1
  match is upgraded by replaying its undo snapshots: they record every point, so finished sets,
  their scores and point sequences are rebuilt exactly; only times are unknown (stored as null,
  CSV duration left empty, match start falls back to the closing time).
- **Players.** Names are trimmed and inner whitespace collapsed; unique ignoring case among
  active players; max 30 characters. Adding the name of an archived player brings that player
  back (keeps their history) instead of creating a second one.
- **Lineup picker.** One panel for both teams (Blue/Red tabs, opened on the tapped team);
  tapping an active player toggles them on the selected team, moving them from the other team;
  a third player on a full team is ignored (chip disabled). Inline "Add player" puts the new
  player on the selected team if there is room.
- **Statistics.** Periods use local calendar dates of the match start: Today = today; Last 30
  days = today and the 29 days before; This year = calendar year; All time. Head-to-head: a
  player is compared against each opposing *player*; a pair against each opposing *team*
  (pair or single player). Win rate of 0 matches is not shown (no division by zero).
- **CSV.** `set_winner`/`match_winner` are `blue`/`red`/`draw` (language independent), empty
  player columns for missing players, ISO local date-time to the second.
- **Navigation.** Simple in-activity screen state (scoreboard / history / match detail /
  players / statistics) with system Back; no navigation library.

## Tasks

1. Plan (this file).
2. **Engine + codec** (test-first): set records, sequences, times, undo consistency;
   `MatchCodec` v2 with v1 migration.
3. **Pure history units** (test-first): `MatchCloser`, `Lineup` toggling, `PlayerNames`
   validation, `StatisticsCalculator` (draws, one-sided lineups, archived players, single-player
   teams, period boundaries), `CsvWriter`.
4. **Room** (KSP, schema v1 exported): players, matches, sets; `HistoryRepository`,
   `PlayerRepository` with Flows; DAO instrumented test.
5. **Scoreboard**: name areas (separate tap target, never scores, follows swap), lineup picker,
   two equal buttons on the set-won overlay, hold-to-reset saves, "History" in the strip;
   view-model tests for closing/saving.
6. **History / detail / multi-select assign + delete, Players, Statistics, CSV share**
   (FileProvider, cache file); German and English strings.
7. **Verify**: JVM tests, instrumented flows on the emulator, real upgrade 2.0.0 → 3.0.0 with a
   running match that has a finished set, voice and phase 1 behaviour unchanged.

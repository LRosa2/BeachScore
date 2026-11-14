# BeachScore

A beach volleyball scoreboard for Android. The phone lies next to the court in landscape, shows the
score in two big halves and speaks it after every point. Points are counted by tapping a half, with
the volume keys, or by voice ("Blau Punkt", "point red") — offline, in German and English.

The full design is in
[`docs/superpowers/specs/2026-11-11-beachscore-design.md`](docs/superpowers/specs/2026-11-11-beachscore-design.md).

## Features

- Two-half scoreboard, always on. A tap lock stops double counts.
- Speaks the score after each point; can be switched off.
- Sets to 15 or 21, win by two.
- Offline voice commands with configurable words, also inside longer speech and with a pause
  between the two words.
- Microphone choice: automatic, phone, or USB / wired.
- Match history, player list, statistics per player, per pair and head-to-head, CSV export.

The app needs no network and asks only for the microphone, and only when voice is switched on.

## Install

APKs are on the [Releases](https://github.com/LRosa2/BeachScore/releases) page (Android 8.0 or
newer). The signing certificate's SHA-256 of release builds is
`26924e0f96b2d1a9309b8752a96f2c89404f1346cd304847804b9f67127e3b85`.

## Build

Requirements: Android Studio with AGP 9.4, JDK 17+, Android SDK platform 37.

```bash
./gradlew test assembleDebug
```

The first build downloads the two small Vosk speech models (about 90 MB) into `.vosk-cache/`
and checks their SHA-256.

## Release signing

One release key is used for every distribution channel. The keystore and its passwords stay
**outside** the repository. Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=D:/keys/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then build the signed app:

```bash
./gradlew assembleRelease
```

Without that file, release builds are unsigned.

## Licence

BeachScore is released under the [MIT License](LICENSE).

## Third-party parts

- [Vosk](https://alphacephei.com/vosk/) speech recognition and its small German and English
  models, under the Apache License 2.0.

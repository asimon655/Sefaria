# Sefaria reader (Android)

A native, offline-first reader for the server in `../sefaria_dev`, a separate repo next to this one. The specs and roadmap are in its `docs/` ([APP_SPEC.md](../sefaria_dev/docs/APP_SPEC.md) describes this app).

```
org.asimon.sefaria
  core/ref        ref model, parser and formatter, section navigation (mirrors the server's internal/ref)
  core/text       HTML subset -> AnnotatedString, Hebrew utilities, search normaliser and matcher
  data/remote     Api (OkHttp, typed results, ETag) and DTOs
  data/local      Room (user data, library state, category tree; response cache), library files
  data/repo       repositories, downloads, offline answers
  feature/*       home, library, book, reader, connections, search, saved, settings, downloads, onboarding
  ui/             theme, fonts, components
  work/           download and maintenance workers
```

## Commands

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest           # JVM tests, incl. shared vectors and server fixtures
./gradlew :app:assembleDebug               # debug build talks to http://10.0.2.2:8080 (the host, from the emulator)
./gradlew :app:connectedDebugAndroidTest   # needs a device: bundled SQLite (FTS5) and cache trim
```

Run the server for the emulator: `cd ../sefaria_dev/server && go run ./cmd/sefaria-server -addr :8080`. Debug builds speak h2c (`-Psefaria.h2c=false` for an HTTP/1.1-only server); `-Psefaria.apiBaseUrl=http://localhost:8080` suits a phone with `adb reverse tcp:8080 tcp:8080`. The server URL can also be changed in Settings.

Opt-in tests on real data: `SEFARIA_LIBRARY=<build>/library` for `OfflineSearchBench`; with a running server (`-rate-limit 0`) and `SEFARIA_SERVER=http://127.0.0.1:8080`, also `SearchAssessment` and `DownloadBench`.

## Emulator (on the KINGSTON drive)

The system image is in `/Volumes/KINGSTON/android/system-images` (symlinked from the SDK). The AVD `Sefaria_API_36` lives in an APFS disk image, `/Volumes/KINGSTON/android/avd.sparsebundle`, because exFAT lacks the hard links the emulator uses for its lock files. The disk image is mounted at `~/.android/avd`. After a reboot or replugging the drive, mount it again before starting the emulator (or Android Studio's device manager):

```sh
hdiutil attach -nobrowse -mountpoint ~/.android/avd /Volumes/KINGSTON/android/avd.sparsebundle
~/Library/Android/sdk/emulator/emulator -avd Sefaria_API_36 -no-audio &
```

## Test fixtures

`app/src/test/resources/fixtures/` holds JSON recorded from the real server, plus relative symlinks into `../sefaria_dev/server`, so shared files stay in sync:
- `vectors.tsv`, `fixture_catalog.json` (from `internal/ref/testdata`): the ref parser;
- `normalize.tsv`, `forms.tsv`, `match.tsv` (from `internal/search/testdata`): the search normaliser and matcher;
- `toc_schema.sql` (from `internal/api/testdata`): the schema of `/v1/toc.sqlite`.

`app/src/test/resources/library/` is the library fixture written by the server's `TestLibrary` (the test corpus's library files and that server's responses). These tests need `sefaria_dev` checked out next to this repo. When the server's API changes, record the JSON again.

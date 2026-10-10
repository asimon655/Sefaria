# Sefaria reader (Android)

A native, offline-first reader for the server in `/Volumes/KINGSTON/sefaria_dev` (specs and roadmap in its `docs/`).

```
org.asimon.sefaria
  core/ref        Ref model, parser/formatter, section navigation (mirrors server internal/ref)
  core/text       Hebrew utils, HTML subset -> AnnotatedString
  data/remote     Api (OkHttp, typed results, ETag) + DTOs
  data/local      Room: response cache + bookmarks, history, reading positions
  data/repo       cache-first repositories
  feature/*       library, book, reader, search, saved, settings
```

## Commands

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest      # JVM tests, incl. shared ref vectors and server contract fixtures
./gradlew :app:assembleDebug          # debug build talks to http://10.0.2.2:8080 (host from the emulator)
./gradlew :app:connectedDebugAndroidTest   # needs a device: FTS5 spike + Room checks
```

Run the server for the emulator: `cd /Volumes/KINGSTON/sefaria_dev/server && go run ./cmd/sefaria-server -addr :8080`. The server URL can be changed in Settings.

## Emulator (lives on the KINGSTON drive)

The system image is in `/Volumes/KINGSTON/android/system-images` (symlinked from the SDK). The AVD
`Sefaria_API_36` lives in an APFS disk image, `/Volumes/KINGSTON/android/avd.sparsebundle`, because exFAT
lacks the hard links the emulator uses for its lock files. The disk image is mounted at `~/.android/avd`.
After a reboot or replugging the drive, mount it again before starting the emulator (or Android Studio's
device manager):

```sh
hdiutil attach -nobrowse -mountpoint ~/.android/avd /Volumes/KINGSTON/android/avd.sparsebundle
~/Library/Android/sdk/emulator/emulator -avd Sefaria_API_36 -no-audio &
```

## Test fixtures

`app/src/test/resources/fixtures/` holds JSON recorded from the real server plus `vectors.tsv` and `fixture_catalog.json`, copied from `server/internal/ref/testdata`. When the server's API or ref model changes, re-record and re-copy them.

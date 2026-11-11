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

## Releases and reproducible builds

A release APK is a function of the commit: anyone who builds the same commit gets the same bytes, so an APK can be checked against its source.

- **Pinned inputs.** The Gradle distribution by checksum (`gradle-wrapper.properties`), every plugin and library by SHA-256 (`gradle/verification-metadata.xml`: a download that doesn't match fails the build), the JDK the build runs on (`gradle/gradle-daemon-jvm.properties`), and the versions in `gradle/libs.versions.toml`, none of them a range.
- **Nothing of the machine or the moment in the APK**: no checkout information (`vcsInfo`), no encrypted dependency list (`dependenciesInfo`). The one outside input is the server address, `-Psefaria.apiBaseUrl`; a release's `BUILD_INPUTS` file says what it was built with.
- **From a clean tree.** A tree that was built before can give other bytes (R8 saw other intermediate files), so releases are built from a fresh clone, as `scripts/reproduce.sh` does.

```sh
scripts/reproduce.sh -r v0.3.0                             # builds that tag in a temporary clone, prints its SHA-256
scripts/reproduce.sh -r v0.3.0 sefaria-0.3.0-unsigned.apk  # ... and compares with a downloaded APK
```

The unsigned APK is the one to compare. A signed one is the same file with a signing block added; [`apksigcopier compare`](https://github.com/obfusk/apksigcopier) checks it against an unsigned build.

GitHub builds a release for a tag `v<versionName>` and for a release published by hand (`.github/workflows/release.yml`): it runs the unit tests, builds the APK, builds it a second time in another directory and fails if the two differ, then attaches `sefaria-<version>-unsigned.apk`, `SHA256SUMS` and `BUILD_INPUTS`. With a keystore in the repository's secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) it attaches the signed `sefaria-<version>.apk` as well; the repository variable `SEFARIA_API_BASE_URL` sets the server address.

After changing a dependency or a plugin, record the new checksums, and review the diff of the file as you would the diff of the code:

```sh
./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Gradle records the build tools of the machine it runs on only: a new `aapt2` needs its `-linux`, `-osx` and `-windows` jars hashed (they are at `https://dl.google.com/android/maven2/com/android/tools/build/aapt2/<version>/`).

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

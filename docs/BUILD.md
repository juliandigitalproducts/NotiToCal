# Build, Tests & CI

Dieser Text wurde unverändert aus der bisherigen README übernommen, damit die Build-Informationen erhalten bleiben.
Pfade und Links in diesem übernommenen Text gelten vom Repository-Stamm aus, nicht von `docs/` aus.

### Build
```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```
Strings live in `tools/i18n/*.py`; regenerate the resources with `python3 tools/i18n/generate.py`.

A release build needs the release key: `NOTITOCAL_KEYSTORE_FILE`, `NOTITOCAL_KEYSTORE_PASSWORD`, `NOTITOCAL_KEY_ALIAS`,
`NOTITOCAL_KEY_PASSWORD` or `../notitocal-release.keystore.properties`. Without it the build stops; for a local test
build with the public preview key in `signing/` add `-PnotitocalPreview` (such APKs are never published).

CI runs on the project's own Forgejo instance: branches build a debug
test APK; on `main` the patch version in `version.properties` is bumped and the APK is signed with the release key,
tagged and released. `versionCode` comes from `version.properties` for local and CI builds. `.github/smoke.sh` is an emulator smoke
test that can be run by hand.

### Tests
| What | Command |
|---|---|
| Unit tests (Robolectric, no device) | `./gradlew :app:testDebugUnitTest` |
| Pipeline harness: the sample messages (`MessageSamples`) through the whole local pipeline – filters, pre-filter, model call, rules, decision, duplicate check, CalDAV / own server / webhook writers – with fake targets on 127.0.0.1 and a fixed clock (Fri 20.11.2026 10:00 Europe/Berlin); also checks that no message text leaves with "Send message text to webhook and server" off | `./gradlew :app:pipelineHarness` → `app/build/reports/pipeline-harness.md`, `app/build/reports/prefilter-30-samples.md` |
| E2E on an emulator/device: notifications posted with `cmd notification post` reach the listener and show up as a suggestion (UI Automator) | `./gradlew :app:connectedDebugAndroidTest` (only compile: `:app:assembleDebugAndroidTest`) |
| Server E2E: `api.php` in PHP's built-in server with SQLite – store, update by uid, validation, ICS feed | `php server/tests/e2e.php` |

The E2E test changes the app's settings on the device (offline rules, `com.android.shell` in the app list) and Gradle
uninstalls the app after the run, so use a test emulator.

### Project layout
```
app/src/main/java/de/juliandigitalproducts/notitocal/
  analysis/   DateParser, RuleEngine, LLM clients, on-device model manager, strictness decision
  capture/    NotificationListenerService, screenshots, Anti-Vocale transcripts, share target
  output/     calendar, task apps, Todoist, CalDAV, Notion, server, webhook, broadcast, share
  pipeline/   filter → analyse → decide → log → deliver / ask
  ui/         Compose screens (wizard, home, entry, settings, models)
server/       self-hosted PHP bridge for MySQL / MariaDB (+ docker-compose), tests/ = server E2E (not for upload)
tools/i18n/   translations (7 languages) and generator
```

### Automatisierung
Ziel „Tasker / Automatisierung“ sendet den Broadcast `de.juliandigitalproducts.notitocal.DETECTED` mit den Extras
`kind, title, description, start, end, all_day, due, location, priority, confidence, source_app, source_package, sender, start_millis`.
Empfänger sind Tasker, MacroDroid und Automate oder die eine App, die du unter „Senden an“ wählst — nicht mehr jede installierte App.

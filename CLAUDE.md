# Spoiler Blocker

An Android app (Kotlin, Jetpack Compose) that covers posts and dismisses
notifications about a topic the owner does not want spoiled. Personal use on
one phone: a Galaxy S25 Ultra on Android 16.

Read these before changing anything:

- `docs/ARCHITECTURE.md`: how v1 works and the status of every decision.
- `docs/BUILD_PLAN.md`: the phases, the pull request breakdown and progress.

## Never commit

The repository is public.

- API keys or other secrets. `local.properties` is gitignored and holds them.
- Model weights.
- Anything real from a phone: screen text, notification text, screenshots,
  `uiautomator` dumps. They contain other people's names and messages. Test
  data in the repository is written by hand.
- Real labelled examples for the evaluation script. They go in `eval/data/`,
  which is gitignored.

## Commands

A JDK (17 or newer) and the Android SDK are needed. Point at the SDK with
`ANDROID_HOME` or with `sdk.dir` in `local.properties`.

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles everything, runs unit tests and lint. CI runs exactly this. |
| `./gradlew :app:testDebugUnitTest` | Unit tests for the app module only. |
| `./gradlew :matcher:test` | Unit tests for the matcher. Plain JVM, no phone needed. |
| `./gradlew -q :eval:run --args="<blocker.json> <examples.jsonl>"` | Runs the matcher over labelled examples and prints the misses and the over-blocks. Try it with the two files in `eval/sample/`. |
| `./gradlew :expansion:test` | Unit tests for keyword expansion. They use a fake server and never call the real API. |
| `./gradlew :expansion:run --args="\"2026 Japanese Grand Prix\" narrow"` | Makes one real, billed Claude API call and prints the three lists. Needs a key: the `ANTHROPIC_API_KEY` environment variable, or an `anthropic.apiKey=` line in `local.properties`. |
| `./gradlew :app:installDebug` | Installs the debug build on a connected phone or emulator. |

## Layout

| Path | What is in it |
|---|---|
| `app/` | The Android app. Package `io.github.aaroncchung.spoilerblocker`. |
| `app/…/SpoilerBlockerApplication.kt` | The Application and `AppContainer`, which owns the objects shared by the whole app. |
| `app/…/data/` | `Blocker` and `BlockerRepository`: blockers are stored as JSON in `files/blockers.json`. `HiddenNotification` and `HiddenNotificationRepository`: the hidden list is `files/hidden_notifications.json`, newest first, 500 at most. |
| `app/…/blocking/` | `ActiveBlockers`, which decides which enabled blocker blocks something. Plain Kotlin, shared by the notification listener and, later, the screen reader. |
| `app/…/notifications/` | The notification listener, `NotificationContent` (a notification's text as plain Kotlin), `HidingDecision.kt` (what to dismiss and what to list, as plain Kotlin) and the notification access helpers. |
| `app/…/status/` | `StatusNotifier`, which shows the status notification while a blocker is on, `StatusNotificationContent` (what it says, as plain Kotlin) and the helpers for the permission to post notifications. |
| `app/…/ui/blockers/` | The blocker list and editor screens with their ViewModels. |
| `app/…/ui/hidden/` | The "Hidden while blocking" screen and its ViewModel. |
| `matcher/` | The matching rules. Plain Kotlin, no Android, no dependencies. Package `io.github.aaroncchung.spoilerblocker.matcher`. `TermTableTest` is a table of terms and texts: add a row there when a match surprises you. |
| `eval/` | A command-line script that measures the matcher against labelled examples. See `eval/README.md`. |
| `expansion/` | Keyword expansion: one Claude API call that turns a description into strong terms, weak terms and sources. Plain Kotlin, no Android. The prompt is in `ExpansionPrompt.kt`; the model and limits are constants at the top of `KeywordExpander.kt`. |
| `gradle/libs.versions.toml` | Every dependency version. |
| `.github/workflows/ci.yml` | CI: `./gradlew build` on every pull request. |
| `docs/` | Architecture, build plan and, later, Phase 0 findings. |

## Conventions

- The owner is new to Android and Kotlin and learns from the diffs. Write
  plain, conventional code. Comment the why, and any Android behaviour a
  newcomer would not guess. Do not comment what the code plainly says.
- One pull request per row of the breakdown in `docs/BUILD_PLAN.md`. Keep each
  one small enough to read in a sitting. Tick the row off in the same pull
  request.
- Logic that does not need Android goes in plain Kotlin, so it can be unit
  tested on a PC. Every pull request with logic has tests for it.
- No dependency injection framework and no new library without a reason given
  in the pull request. Shared objects live in `AppContainer`.
- There is one `BlockerRepository` and one `HiddenNotificationRepository` per
  process; get them from `AppContainer`. A second one on the same file throws.
- A new field on `Blocker` or `HiddenNotification` needs a default value, so
  that what an older build stored still loads, and a raised `CURRENT_VERSION`
  in its repository, so that an older build refuses the newer file instead of
  stripping the field. Add a new frozen document to the repository's test at
  the same time.
- Work that must outlive a screen or a service runs in
  `AppContainer.applicationScope`.
- When something changes whether blocking works, call
  `AppContainer.statusNotifier.refresh()` on the main thread, so that the
  status notification never says more than is true.
- The Anthropic API key lives in `local.properties` as `anthropic.apiKey=`.
  It is never committed, logged or shown.
- The prompt in `ExpansionPrompt.kt` describes how the matcher works. When
  the matching rules change, change the prompt in the same pull request.
- User-visible text goes in `res/values/strings.xml`.
- Screen text is never stored or sent anywhere in normal use (decision 15).
  Only the description typed for a blocker, its breadth and today's date ever
  leave the phone (decision 8). They go to the Claude API, once per blocker.
  The one thing that is stored is the text of a hidden notification, in the
  hidden list on the phone (decision 13).
- A notification's title or text is never logged, in any build type. The
  listener logs the package and its decision, in debug builds only. An
  exception is logged by its class name only, because its message can quote
  what it was reading.
- Update `docs/ARCHITECTURE.md` when a decision changes status, and this file
  when a module or command is added.

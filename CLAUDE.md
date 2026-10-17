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

## Commands

A JDK (17 or newer) and the Android SDK are needed. Point at the SDK with
`ANDROID_HOME` or with `sdk.dir` in `local.properties`.

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles everything, runs unit tests and lint. CI runs exactly this. |
| `./gradlew :app:testDebugUnitTest` | Unit tests for the app module only. |
| `./gradlew :matcher:test` | Unit tests for the matcher. Plain JVM, no phone needed. |
| `./gradlew :app:installDebug` | Installs the debug build on a connected phone or emulator. |

## Layout

| Path | What is in it |
|---|---|
| `app/` | The Android app. Package `io.github.aaroncchung.spoilerblocker`. |
| `matcher/` | The matching rules. Plain Kotlin, no Android, no dependencies. Package `io.github.aaroncchung.spoilerblocker.matcher`. |
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
  in the pull request.
- User-visible text goes in `res/values/strings.xml`.
- Screen text is never stored or sent anywhere in normal use (decision 15).
  Only the description typed for a blocker ever leaves the phone (decision 8).
- Update `docs/ARCHITECTURE.md` when a decision changes status, and this file
  when a module or command is added.

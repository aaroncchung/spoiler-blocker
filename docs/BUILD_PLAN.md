# Build plan: Spoiler Blocker v1

Last updated: 2026-10-05. Read [ARCHITECTURE.md](ARCHITECTURE.md) first.

## How the plan is shaped

- The riskiest unknowns are measured before anything is built on them.
- Every phase ends with either a measured answer or something usable on the
  phone.
- Early phases are small, because this is a first Android and Kotlin project.
  Each phase lists what it teaches.
- Tools: Android Studio, Kotlin, Jetpack Compose for the app's own screens.

## Phase 0: setup and experiments

**Goal:** answer the questions the design depends on. The code written here is
thrown away.

**Setup**

1. Install Android Studio.
2. Turn on Developer options and USB debugging on the phone.
3. Run the default "hello world" app on the phone.

**Experiments**

| # | Question | How |
|---|---|---|
| E1 | What text do YouTube and Instagram expose, and can it be grouped into posts? | Run `adb shell uiautomator dump` on each screen: YouTube home, search, watch page, Shorts; Instagram feed, Explore, Reels, Stories, messages. No code needed. |
| E2 | How far does a box lag behind a scrolling post? | A probe app whose accessibility service draws a box over one list item and logs event times. Film it in slow motion. |
| E3 | Can the start of a scroll be detected before new content is visible? | Same probe app. Compare touch-down time with the first scroll event. |
| E4 | Does a notification banner show before it can be dismissed? | A probe notification listener that dismisses everything from one test app. |
| E5 | Does a per-window screenshot leave out the app's own black boxes? | Same probe app. Only matters for thumbnail scanning later, but it is cheap to check now. |
| E6 | Does One UI stop the service? | Leave the probe running overnight, once with battery set to Unrestricted and once without. |

**Teaches:** installing an app on a phone, what a service is, how permissions
are granted, reading logs.

**Done when:** the answers are written up in `docs/FINDINGS.md` and the
"Needs experiment" items in the architecture document are updated.

## Phase 1: notification blocker

**Goal:** a working blocker for notifications only.

**Build**

- App skeleton with one screen listing blockers.
- Create, edit, delete and switch a blocker, with terms typed by hand.
- Notification listener that dismisses matches.
- "Hidden while blocking" list.
- Status notification while any blocker is on.
- A first, simple matcher.

**Teaches:** Kotlin basics, Compose screens, saving data, the notification
listener.

**Done when:** with a blocker on for a race already watched, matching test
notifications disappear and show up in the hidden list, and others are left
alone.

## Phase 2: matcher and keyword expansion

**Goal:** the real matching rules and automatic term lists.

**Build**

- A separate plain-Kotlin matcher module: text normalisation, strong and weak
  terms, sources, breadth.
- Unit tests using hand-written examples.
- The cloud LLM call that turns a description into the three lists.
- A review screen for editing the lists before saving.
- The API key read from local configuration that is never committed.

**Teaches:** modules, unit testing, network calls, keeping secrets out of git.

**Done when:** the tests pass, and creating a blocker for a past race gives
lists that need only light editing.

## Phase 3: screen reading

**Goal:** the app can see posts in YouTube and decide about each one. Nothing
is covered yet.

**Build**

- Accessibility service limited to watched apps and active only while a blocker
  is on.
- Grouping of on-screen text into posts, YouTube first.
- A debug screen listing the posts the app sees and the verdict for each.
- Opt-in capture of screen structure to a private folder on the phone, for
  making test cases.

**Teaches:** the accessibility service, the tree of on-screen elements,
debugging on a device.

**Done when:** on YouTube's home and search screens, the debug list shows every
visible video with the right title, channel and verdict.

## Phase 4: covering

**Goal:** matched posts are hidden in YouTube.

**Build**

- Overlay manager that draws and removes black boxes.
- Cover on scroll and reveal after checking, tuned to the Phase 0 measurements.
- Strict and relaxed settings.
- Handling for rotation and for the video player in full screen.
- Health monitor that warns if the screen reader stops.

**Teaches:** drawing over other apps, timing and performance.

**Done when:** scrolling YouTube with a past-race blocker on, no matched video
is ever visible uncovered in strict mode, and unmatched videos reveal within
the target time.

## Phase 5: Instagram and hardening

**Goal:** a second app, and reliability over days.

**Build**

- Post grouping for the Instagram home feed. Other Instagram screens follow
  what Phase 0 found.
- A setup screen that walks through permissions and One UI's battery setting.
- A performance pass: time per screen check and battery use over a day.

**Done when:** a week of daily use passes without the service stopping
unnoticed.

## Phase 6: evaluate v1

**Goal:** decide what comes next using evidence.

**Build**

- A labelled test set from a race already watched: a few hundred titles,
  captions and notifications, each marked related or not. Real examples stay
  out of the repository.
- A script that runs the matcher over the set and counts misses and
  over-blocks.

**Decide**

- Whether a text model is needed. If so, test EmbeddingGemma and Decima-small
  on the same set.
- Whether thumbnail scanning is the next most valuable addition.

## After v1

In this order unless Phase 6 says otherwise:

1. Thumbnail text using on-device text recognition.
2. An on-device text model, if the evidence supports one.
3. A short, personal-only experiment with a cloud vision model, to count how
   many extra spoilers vision catches.
4. On-device vision, starting with OneJev-0.8B, only if step 3 justifies it.
5. More surfaces: Google Discover, Chrome, search suggestions.
6. Work needed to publish on Google Play.

## Testing approach

| Layer | How it is tested |
|---|---|
| Matcher | Unit tests on a PC with hand-written examples. |
| Post grouping | Recorded screen structures replayed through the grouping code. Recordings stay local. |
| Covering and notifications | A manual checklist on the phone, using topics already watched so that testing spoils nothing. |
| Whole app | The Phase 6 test set, plus daily use. |

## v1 is finished when

- A blocker can be created from a description, reviewed and switched on.
- Matching notifications are dismissed and listed.
- Matching posts in YouTube and the Instagram home feed are covered.
- The app shows clearly whether it is working.
- Misses and over-blocks have been measured on a real past event.

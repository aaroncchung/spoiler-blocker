# Phase 0 probe: running the experiments

Last updated: 2026-10-05. Read [ARCHITECTURE.md](ARCHITECTURE.md) and the
Phase 0 section of [BUILD_PLAN.md](BUILD_PLAN.md) first.

"SB Probe" is a throwaway app that measures experiments E1 to E6 on the real
phone. It lives on the `probe` branch and is never merged into `main`. This
document says how to install it, how to run each experiment, and what to write
down. The answers go into `docs/FINDINGS.md`; a template is at the end.

The probe was built and checked on an Android 16 emulator. An emulator shows
that the instrument works. It does not answer the experiments: only the Galaxy
S25 Ultra does.

## Contents

1. [What you need](#1-what-you-need)
2. [One-time setup](#2-one-time-setup)
3. [Controlling the probe](#3-controlling-the-probe)
4. [The log](#4-the-log)
5. [E1: what text do the apps expose?](#5-e1-what-text-do-the-apps-expose)
6. [E2: how far does a box lag behind a scrolling post?](#6-e2-how-far-does-a-box-lag-behind-a-scrolling-post)
7. [E3: can the start of a scroll be detected in time?](#7-e3-can-the-start-of-a-scroll-be-detected-in-time)
8. [E4: does a notification banner show before it can be dismissed?](#8-e4-does-a-notification-banner-show-before-it-can-be-dismissed)
9. [E5: does a per-window screenshot leave out the boxes?](#9-e5-does-a-per-window-screenshot-leave-out-the-boxes)
10. [E6: does One UI stop the service?](#10-e6-does-one-ui-stop-the-service)
11. [When something goes wrong](#11-when-something-goes-wrong)
12. [Cleaning up](#12-cleaning-up)
13. [Template for docs/FINDINGS.md](#13-template-for-docsfindingsmd)

## 1. What you need

- The Galaxy S25 Ultra and a USB cable that carries data.
- This repository on the PC, on the `probe` branch.
- Android Studio on the PC. It brings everything else with it.
- For E2, E3 and E4: something that films in slow motion, such as a second
  phone at 240 frames per second. If you have none, the phone's own screen
  recorder is a weaker substitute; section 6 says why.
- YouTube and Instagram installed and signed in.

**Privacy rule.** Tree dumps and screenshots contain whatever was on the
screen: other people's names, messages and photos. The probe keeps them in its
own private storage on the phone. The helper script copies them only into the
`captures` folder of this repository, which git ignores. Never move them
anywhere that git tracks, and never paste real screen text into
`docs/FINDINGS.md`. Describe structure instead ("the title is a TextView with
id `title`").

## 2. One-time setup

### 2.1 Tools on the PC

1. Install [Android Studio](https://developer.android.com/studio) with the
   default options. It installs the Android SDK, including `adb`, the program
   that talks to the phone.
2. Open a **Windows PowerShell** window in the repository folder. In File
   Explorer, open the folder, click the address bar, type `powershell` and
   press Enter.
3. Switch to the probe branch:

   ```powershell
   git fetch
   git switch probe
   ```

4. Windows blocks PowerShell scripts by default. Allow your own, once:

   ```powershell
   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
   ```

5. Some commands below start with `adb`. For those to work, tell this
   PowerShell window where adb is. This lasts until the window is closed:

   ```powershell
   $env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
   ```

   The helper script `tools\probe.ps1` finds adb by itself and does not need
   this.

### 2.2 The phone

The menu names below are from One UI and may differ a little on One UI 8.5.
The search box at the top of Settings finds each of them.

1. **Turn on Developer options.** Settings > About phone > Software
   information. Tap **Build number** seven times and enter your PIN.
2. **Look at Auto Blocker first.** Samsung's Auto Blocker (Settings >
   Security and privacy > Auto Blocker) blocks apps from sources other than
   the Play Store and Galaxy Store, and "blocks commands by USB cable".
   Owners of Samsung phones report that while it is on, the USB debugging
   switch in the next step is itself greyed out. This is reported, not
   confirmed: no Samsung phone was available when the probe was built. If the
   switch is greyed out, or adb cannot see the phone, or the install is
   refused, turn Auto Blocker off for the experiments and on again
   afterwards. Write down whether you had to.
3. **Turn on USB debugging.** Settings > Developer options > **USB
   debugging**.
4. Connect the phone to the PC. The phone asks "Allow USB debugging?". Tick
   "Always allow from this computer" and tap Allow.
5. Check that the PC sees it:

   ```powershell
   .\tools\probe.ps1 status
   ```

   Under "Devices adb can see" there should be one line ending in `device`.
   `unauthorized` means the question on the phone was not answered; unplug,
   plug in again and look at the phone.

### 2.3 Build and install the probe

The easy way: open the repository folder in Android Studio (File > Open) and
wait for "Gradle sync" to finish. In the toolbar at the top, choose **probe**
in the box that shows the app to run, choose the phone next to it, and press
the green Run arrow.

Or from PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :probe:installDebug
```

The two `$env:` lines tell Gradle where Java and the Android SDK are. The
paths shown are Android Studio's defaults. Any Java 17 or newer will do. The
first build downloads a lot and takes several minutes.

An app called **SB Probe** is now on the phone. It can be installed next to
the real Spoiler Blocker app.

Allow its notifications, either by opening SB Probe and tapping **Allow
notifications**, or with:

```powershell
adb shell pm grant io.github.aaroncchung.spoilerblocker.probe android.permission.POST_NOTIFICATIONS
```

### 2.4 Switch on the two services

The probe has two background parts that only you can switch on.

**The accessibility service** (E1, E2, E3, E5, E6):

1. Open SB Probe and tap **Open accessibility settings**.
2. Find **SB Probe**. On One UI it is under "Installed apps".
3. Turn on "Use SB Probe". Android warns that the app gets "full control of
   your device". For an accessibility service that is the standard warning.
   The description on that screen lists exactly what the probe does. Tap
   Allow.

**Notification access** (E4, E6):

1. In SB Probe tap **Open notification access settings**.
2. Turn on **SB Probe** and confirm.

**"Restricted setting".** Since Android 13, an app that did not come from an
app store may be refused these two switches. The switch is greyed out, or
tapping it shows "Restricted setting: for your security, this setting is
currently unavailable". This is a guard against malware that talks people
into granting such access. It is not an error in the probe. To get past it:

1. Tap the greyed-out switch once so that the message has appeared. The next
   step is hidden until it has.
2. Go to Settings > Apps > SB Probe.
3. Tap the three dots at the top right and choose **Allow restricted
   settings**. Confirm with your PIN or fingerprint.
4. Go back and turn the switch on.

If there are no three dots, the same thing can be done from the PC:

```powershell
adb shell appops set io.github.aaroncchung.spoilerblocker.probe ACCESS_RESTRICTED_SETTINGS allow
```

Whether you meet this dialog depends on the phone maker. The emulator did not
show it for an app installed over USB. A Samsung phone may. Note in the
findings which happened, because the real app's setup screen needs to know.

### 2.5 Check that it works

Open SB Probe. The Setup section should say:

```
Accessibility service: ON and connected
Notification access: ON and connected
Probe may show notifications: yes
```

Now open the phone's Settings app. A magenta box should cover one line of the
list and stay on it as you scroll. Pull down the notification shade: there is
an **SB Probe controls** notification with seven buttons. The box hides while
the shade is open.

## 3. Controlling the probe

While YouTube or Instagram is in front you cannot press buttons in the probe.
There are three ways to control it.

**The probe's own screen.** For switches you set before going to the other
app: which mechanism draws the box, events or poll, the film strip, the touch
watcher, the E4 package.

**The notification.** Pull down the shade and expand **SB Probe controls**.
The probe closes the shade, waits a moment and then acts on the app that was
in front.

| Button | What it does |
|---|---|
| Next item | E2: moves the box to the next item of the list |
| Next list | E2: moves the box to another scrollable part of the screen |
| Next mech. | E2: switches to the next way of drawing the box |
| Summary | E2: writes the timing summary to the log, then starts a fresh count |
| Screenshot | E5: takes the per-window screenshot and checks it |
| Rate test | E5: finds the screenshot rate limit (15 seconds) |
| Dump tree | E1: saves the tree of the screen in front |

**The PC.** `tools\probe.ps1` wraps the adb commands. It prints each adb
command it runs.

| Script command | Plain adb form (after `adb shell am broadcast -n io.github.aaroncchung.spoilerblocker.probe/.TriggerReceiver`) |
|---|---|
| `.\tools\probe.ps1 repick` | `-a sbprobe.REPICK` |
| `.\tools\probe.ps1 nextlist` | `-a sbprobe.NEXT_LIST` |
| `.\tools\probe.ps1 mechanism sc-display` | `-a sbprobe.MECHANISM --es name sc-display` (no name: the next one) |
| `.\tools\probe.ps1 tracking poll` | `-a sbprobe.TRACKING --es mode poll` (or `events`) |
| `.\tools\probe.ps1 box off` | `-a sbprobe.BOX --ez on false` |
| `.\tools\probe.ps1 strip on` | `-a sbprobe.FILM_STRIP --ez on true` |
| `.\tools\probe.ps1 summary reset` | `-a sbprobe.E2_SUMMARY --ez reset true` (without `reset`: summary only, the count goes on) |
| `.\tools\probe.ps1 touchwatch on` | `-a sbprobe.TOUCH_WATCH --ez on true` |
| `.\tools\probe.ps1 motionlisten` | `-a sbprobe.MOTION_LISTEN` |
| `.\tools\probe.ps1 notify 3000` | `-a sbprobe.POST_TEST --ei delay_ms 3000` |
| `.\tools\probe.ps1 cancel on com.example.app` | `-a sbprobe.CANCEL --ez on true --es package com.example.app` |
| `.\tools\probe.ps1 screenshot` | `-a sbprobe.SCREENSHOT` |
| `.\tools\probe.ps1 ratetest` | `-a sbprobe.RATE_TEST` |
| `.\tools\probe.ps1 dump youtube-home` | `-a sbprobe.DUMP_TREE --es label youtube-home` (the script also runs `uiautomator dump` and copies both files) |
| `.\tools\probe.ps1 runstart night-1` | `-a sbprobe.RUN_START --es label night-1` |

So the full plain form of the first row is:

```powershell
adb shell am broadcast -n io.github.aaroncchung.spoilerblocker.probe/.TriggerReceiver -a sbprobe.REPICK
```

Only adb and the probe itself can send these. Other apps on the phone cannot.

If more than one phone or emulator is connected, add `-Serial <serial>` to the
script, or `-s <serial>` straight after `adb`. `adb devices` lists the serials.

## 4. The log

Everything the probe measures goes to one file in its private storage, and to
Android's system log under the tag `SBProbe`.

```powershell
.\tools\probe.ps1 log 100       # the last 100 lines
.\tools\probe.ps1 e6            # the last E6 lines only
.\tools\probe.ps1 pull          # copy the log, dumps and screenshots into captures\
adb logcat -s SBProbe           # follow it live; Ctrl+C stops
```

The first three read a file. They do not start, wake or "use" the probe, so
they are safe in the middle of an E6 run. Opening SB Probe is not.

The plain adb form of the first is
`adb shell run-as io.github.aaroncchung.spoilerblocker.probe tail -n 100 files/probe.log`.
`run-as` lets adb read an app's private files. It works because the probe is a
debug build.

The newest lines are also at the bottom of the probe's own screen.

A line looks like this:

```
2026-10-05 19:07:18.940 up=3408081 rt=3408081 pid=7603 E2 #6 mech=wm-canvas by=CONTENT_CHANGED evt=3408052 recv=+1 ...
```

| Part | Meaning |
|---|---|
| `2026-10-05 19:07:18.940` | Time of day. |
| `up=` | Milliseconds the processor has been awake since the phone started. Android stamps touches and accessibility events with this clock, and the film strip shows its last five digits. |
| `rt=` | Milliseconds since the phone started, including time asleep. E6 compares `up` and `rt`. |
| `pid=` | The number of the probe's process. A new number means Android started the probe afresh. |
| `E1` to `E6`, `APP` | Which experiment the line belongs to. |

The log holds times, sizes, class names and view ids. It never holds text from
the screen or from a notification.

The example lines in this document show what a line looks like. Most were
copied from the emulator; a few were written by hand when the format changed.
The numbers in them are not findings.

## 5. E1: what text do the apps expose?

**Answers:** which words YouTube and Instagram hand to an accessibility
service, and whether the words of one post can be told apart from the next.

The probe captures each screen twice:

- **The service's view** (`*.service.capture.json`). This is the tree the
  probe's own accessibility service is given, which is what the real app will
  get. Layout-only elements are included and marked `"important":false`.
- **`uiautomator dump`** (`*.uiautomator.xml`). This is Android's own tool,
  the one the build plan names. It can fail on screens that never stop moving,
  and it is treated as an assistive tool, so it may be shown things a normal
  service is not.

### Steps

For each screen in the table: open it on the phone, let it settle, then run
the command on the PC.

| Screen | Command |
|---|---|
| YouTube home | `.\tools\probe.ps1 dump youtube-home` |
| YouTube search results | `.\tools\probe.ps1 dump youtube-search` |
| YouTube watch page (a video playing, comments and suggestions below) | `.\tools\probe.ps1 dump youtube-watch` |
| YouTube Shorts | `.\tools\probe.ps1 dump youtube-shorts` |
| Instagram feed | `.\tools\probe.ps1 dump instagram-feed` |
| Instagram Explore | `.\tools\probe.ps1 dump instagram-explore` |
| Instagram Reels | `.\tools\probe.ps1 dump instagram-reels` |
| Instagram Stories (one open) | `.\tools\probe.ps1 dump instagram-stories` |
| Instagram messages (the list, then one conversation) | `.\tools\probe.ps1 dump instagram-messages` |

Do each screen twice, scrolled to different places, so that one odd post does
not mislead.

Away from the PC, use **Dump tree** on the notification instead. It saves the
service's view only, named after the app. Copy the files later with
`.\tools\probe.ps1 pull`.

`uiautomator dump` switches every accessibility service off for the moment it
runs. The probe log shows `a11y disconnected` and, a second later, `a11y
connected`. That is expected. Do not run `dump` during an E6 run.

### What you see

The script prints an outline of each dump, indented by depth. For the
service's view it starts with a few lines about the capture: the app, the
windows that were on screen, and which accessibility services were on. Then
come the elements that carry text, and the elements that say how the screen
is grouped.

```
 1  RecyclerView #results LIST[20x1] [0,142][1080,2300]
 2   ViewGroup ITEM[row 3,col 0] SRF [0,142][1080,900]
 3    TextView #title text="..." [40,700][900,760]
```

The columns are depth, class, `#` view id, marks, text, description (`desc=`,
what a screen reader would say) and bounds as `[left,top][right,bottom]` in
pixels. The marks are the app's own statements about grouping:

| Mark | Meaning |
|---|---|
| `LIST[rows x columns]` | The element says it is a list or grid of that size. |
| `ITEM[row,col]` | The element says it is one item of a list, and which. |
| `SRF` | "Screen reader focusable": the element a screen reader stops on and reads out as one unit. Apps put it on what wraps a whole post. |

`.\tools\probe.ps1 outline captures\<file>` prints an outline again later.

The JSON file has one element per line, with the index of its parent, so the
full tree can be rebuilt from it. Besides text it records, for every element,
`clickable`, `longClickable`, `enabled`, `focusable`, `screenReaderFocusable`,
`heading`, and the `collection` and `collectionItem` details where the app
gives them. Only the window in front is walked. The first line lists the
other windows that were on screen.

If the outline warns that the dump "was cut off at the node limit", the screen
had more than 5000 elements and the file is incomplete. Write that down.

### What to write down

For each screen:

- Is the post's title there? The channel or account name? The caption? As
  `text` or as `desc`?
- Is there one element per post whose bounds cover the whole post, with the
  title and the channel underneath it? Note its class and view id, and
  whether it carries `ITEM` or `SRF`. This decides whether "group text into
  posts" can be generic.
- Is anything in the `uiautomator` outline missing from the service's outline,
  or the other way round?
- Did `uiautomator dump` fail?
- From the log line `E1 dump ... nodes=N walkCold=Nms walkWarm=Nms`: the
  number of elements and the two times. See below.
- Which other accessibility services were on (the outline lists them). Apps
  can lay themselves out differently when a screen reader is running, so do
  the dumps with the phone set up as you normally use it, and say how.
- For Shorts, Reels and Stories: is there any text at all beyond the caption?

**The two walk times.** Android keeps a store of elements a service has
already been given. `walkCold` is the time to read the whole screen with that
store emptied first: every element is fetched from the app. `walkWarm` is the
same walk again straight afterwards, with the store full. Both include the
layout-only elements and the work of writing them down, which the real app
will not do. So `walkCold` is an upper bound on "reading one screen", not an
estimate of it. The real figure lies below it.

## 6. E2: how far does a box lag behind a scrolling post?

**Answers:** whether a box can stay on a moving post, and so whether feeds
must go black on every scroll (decision 11).

The probe keeps a coloured box on one list item of whichever app is in front.
There are four ways to draw it and two ways to make it move. Each way of
drawing has its own colour.

| Mechanism | Colour | What it is |
|---|---|---|
| `wm-move` | magenta | A small overlay window the size of the box, moved with `WindowManager.updateViewLayout`. |
| `wm-canvas` | cyan | One full-screen overlay window. The box is repainted inside it. |
| `sc-display` | yellow | A surface attached to the display with `attachAccessibilityOverlayToDisplay` (Android 14), moved with a `SurfaceControl.Transaction`. |
| `sc-window` | green | The same, attached to the app's window with `attachAccessibilityOverlayToWindow`. |

| Tracking | What makes the box move |
|---|---|
| `events` | The app's own scroll and content-change events. Android makes apps hold these back to about ten a second, so the box moves in steps. |
| `poll` | The probe asks the app where the item is on every frame of the display. Smoother, but each question makes the app's main thread do a little work. |

### Steps

1. In SB Probe: **Show the box** on, mechanism `wm-move`, tracking `events`,
   **Film strip** on.
2. Open YouTube's home feed. A box appears on one item. If it is on the wrong
   thing, use **Next item** or **Next list**.
3. Note the phone's refresh-rate setting (Settings > Display > Motion
   smoothness: Adaptive or Standard) and whether power saving is on. Both
   change how often the screen, and the probe, get to draw.
4. Prop the phone up. Start filming it in slow motion with the second device.
5. Scroll three ways: a slow drag up and down, a quick flick, and a flick
   stopped with a tap. Let the list come to rest between them.
6. Stop filming. Press **Summary** on the notification, or run
   `.\tools\probe.ps1 summary reset` on the PC. Both write the summary and
   then start a fresh count, so each clip gets its own. Do this after every
   clip: a summary covers everything since the last reset.
7. Switch to the next mechanism and repeat from step 4, for all four.
8. Set tracking to `poll` and do all four again.
9. Repeat the best and the worst combination in Instagram's feed.

That is eight short clips in YouTube. The box colour says which mechanism a
clip shows.

No second camera? Use the phone's screen recorder (in the quick panel). It
records what the screen was told to show, so each recorded frame is exact. But
it records fewer frames per second than the screen shows, and it cannot show
how late the panel itself is.

### What to look at in the film

**Measure by counting frames of film.** At 240 frames a second each frame of
film is about 4 ms. Find the frame where the post first moves and the frame
where the box first moves, and count the frames between them. Do the same
where the list stops.

- While the list moves, how far is the box from its post? Judge it against
  the height of the post: "a tenth", "half", "a whole post".
- How many frames of film pass between the post starting to move and the box
  starting to move?
- In `events` mode, does the box jump in steps?
- In `poll` mode, does it stay on the post? Does the feed itself scroll less
  smoothly than with the box off?
- After the list stops, how many frames until the box sits exactly on the
  post?

**The clock is for finding log lines, not for measuring.** The film strip sits
over the status bar. Its left third is a clock: seconds and milliseconds of
the `up=` clock, so `34.567` on film is near `up=...34567` in the log. But the
clock is drawn by the probe, on the same thread that waits for the app's
answers. While the probe waits, the clock stands still, and it does so exactly
when the box is late. The digits also reach the screen a frame or two after
they were drawn. Use it to tell which stretch of the log a clip belongs to.

### What to read in the log

One line per box move:

```
E2 #6 mech=wm-canvas by=CONTENT_CHANGED evt=3408052 recv=+1 bounds=+3 submit=+4 frame=+9 commit=+26 top=1890 moved=-90 dy=-
```

| Part | Meaning |
|---|---|
| `by=` | What caused the move: `VIEW_SCROLLED`, `CONTENT_CHANGED` or `poll`. |
| `evt=` | When the app sent the event (`up` clock). For `poll`, when the frame began. |
| `recv=` | Milliseconds until the probe was told. |
| `bounds=` | Milliseconds until the probe had asked the app where the item now is. |
| `submit=` | Milliseconds until the probe had handed the new position to Android. |
| `frame=` | Milliseconds until the probe's next frame began. Only for the `wm-` mechanisms. |
| `commit=` | Milliseconds until Android's compositor took the change. Not available for `wm-move`, where the window manager moves the window on its own schedule. `-` also when the report did not come back within half a second. |
| `top=`, `moved=` | The box's new top edge, and how far it moved, in pixels. |
| `dy=` | The scroll distance the event reported, if any. |

The summary gives the median, the 90th percentile and the maximum of each
column, for each app, mechanism and cause:

```
E2 summary app=com.google.android.youtube mech=wm-canvas by=poll n=62 recv=0/0/0 bounds=6/10/12 submit=7/10/13 frame=0/0/0 commit=33/35/36 commit-n=60 (median/p90/max ms after the event)
E2 summary app=com.google.android.youtube scroll events n=48 gap between neighbours=100/117/250 (median/p90/max ms; pauses over 1000ms left out)
E2 summary poll frames n=412 interval=8/9/17 (median/p90/max ms) display mode=120.0Hz power saving=false
```

`n` is the number of box moves in the group. `commit-n` is how many of them
got a commit time; the commit figures are about those only. If a line says
`CAPPED`, more moves happened than the probe keeps and the later ones are
missing: reset more often.

**Read the commit column with care.**

- It is not the lag. The film is the measure of lag. The log only shows how
  the time after the starting point divides up.
- The starting point differs. For `VIEW_SCROLLED` and `CONTENT_CHANGED` rows
  it is when the app *sent* the event, and Android makes an app wait up to
  100 ms after the content moved before it sends one. For `poll` rows it is
  when the probe's own frame began. **Never compare the commit column of an
  events row with that of a poll row.** Compare rows of the same kind only.
- "Commit" is when Android's compositor accepted the change. The picture
  reaches the panel one to two screen refreshes after that. Add about two
  refreshes: 17 ms at 120 Hz, 33 ms at 60 Hz.

**The poll frames line** says how often the probe really got to run while
polling. At 120 Hz the interval should be about 8 ms. If it is 16 or more,
the probe was being given every second frame or fewer (power saving and
adaptive refresh can do that), and the box cannot have kept up whatever the
mechanism. `display mode` is the rate the screen is set to; an adaptive
screen can run slower than that from moment to moment, so the measured
interval is the figure to trust. Write both down with the results. The line
only has figures after a clip filmed in `poll` mode.

**When does a scroll end?** The real app must uncover the feed soon after
scrolling stops (the budget is 150 ms). All it can go by is that no scroll
event has come for a while. Two things in the log bear on that:

- "gap between neighbours" in the summary: how far apart scroll events are
  *during* a scroll. The app cannot call a scroll over until a gap longer
  than the largest of these has passed.
- How long after the content stops the last scroll event arrives. On film the
  right-hand block of the strip is blue while scroll events keep coming, and
  goes dark 300 ms after the last one. Count the film frames from the content
  stopping to the block going dark, turn them into milliseconds, and take
  300 off. What is left is how long after the stop the last event came. The
  log line `E2 scroll ended: 14 scroll events, first evt=... last evt=...
  (then none for 400ms)` records when that event was sent.

  The two added together, the last event's delay and the longest gap, are the
  least time between the content stopping and an app being able to know it.

**`sc-window` and items that are not real views.** The line `E2 picked item
...` ends with `inWindow=... (reported by the app)` or `(worked out: ...)`.
Feeds drawn by the app's own code report a screen position only, and the
probe then works out the position within the window itself. If the green box
is in the wrong place while the other three are right, that working-out is
what failed: copy the `picked` line into the findings.

### What to write down

- The refresh-rate setting, whether power saving was on, and the `display
  mode` and poll `interval` figures from the summary.
- For each of the eight combinations: lag in frames of film (and so in
  milliseconds) and as a fraction of a post, on a slow drag and on a flick.
- The summary lines.
- Which combination is best, and whether even the best is good enough to
  leave unmatched posts uncovered while scrolling.
- Whether `poll` makes the feed stutter.
- For the end of a scroll: the largest "gap between neighbours", and the film
  frames from content stopping to the last scroll event.
- Anything odd: a box that slides instead of jumping, flickers, or is left
  behind when the app changes screen.

## 7. E3: can the start of a scroll be detected in time?

**Answers:** whether the app can know that a finger is down before the feed
shows anything new, so that it can cover the feed in time.

Android does not let a third-party service watch touches directly. The probe
tries what there is:

| Mechanism | What it is | What is known |
|---|---|---|
| Outside-touch watcher | A one-pixel overlay window with `FLAG_WATCH_OUTSIDE_TOUCH`. Android sends it one event for the first finger of every gesture. The gesture still goes to the app. | Worked on the emulator: reported within a few milliseconds, scrolling unaffected. Says only that a finger went down, not where it goes next. |
| Motion events | `setMotionEventSources(SOURCE_TOUCHSCREEN)` and `onMotionEvent` (Android 14). | Android's documentation says such events "are not sent to the rest of the system". Confirmed on the emulator: the service saw the touches and the app did not. The probe asks for ten seconds only. |
| Touch-interaction events | The accessibility events `TYPE_TOUCH_INTERACTION_START` and friends. | Android only sends them while a screen reader's "explore by touch" is on. The probe logs them if they ever arrive. |

Two more exist and were left out. Watching motion events without taking them
(`setObservedMotionEventSources`) is reserved for Android's own tests: it
needs a permission only the system can hold. Turning on "explore by touch"
would give the service every touch first, but it changes how the whole phone
behaves and how apps draw themselves.

### Steps

Use a real finger. `adb shell input swipe` is a shortcut that skips part of
the path a real touch takes.

1. In SB Probe: **Outside-touch watcher** on, **Film strip** on.
2. Open YouTube's home feed. Film the phone in slow motion.
3. With the list at rest, put a finger down and drag straight away, as you
   normally scroll. Lift, **wait until the list has stopped**, and do it
   again, ten times. Then ten flicks, again letting the list stop each time.
4. Now the opposite on purpose: flick, and put the finger down again while
   the list is still moving.
5. Then tap videos, use the back gesture, pinch a photo in Instagram: does
   everything still behave as normal?
6. Read the log: `.\tools\probe.ps1 log 60`.
7. Back in SB Probe, press **Listen to motion events for 10 s** and try to
   scroll the probe's own screen. Then read the log again.

### What to look at

**The answer comes from the film.** The middle block of the film strip turns
red when the probe hears of the touch, and the right block turns blue when a
scroll event arrives. Count the frames of film between the red block
appearing and the feed first moving. That count, positive or negative, is the
time there would be to cover the feed.

One caution about that. The strip is a window that already exists and only
has to be repainted. A cover that is kept ready in the same way, drawn but
empty, can be filled as fast as the red block appears. A cover that has to be
created when the finger lands takes longer, by an amount this test does not
show.

In the log:

```
E3 touch #23 src=outside-touch down=3650482 recv=+1ms
E3 touch #23: first VIEW_SCROLLED event was sent +165ms and received +172ms after touch-down; the outside-touch signal reached the probe 171ms before that EVENT did. The content moves up to 100ms before its event is sent, so this is not the time to spare for covering.
E3 touch #24 src=outside-touch down=3652010 recv=+2ms (touch during scroll, not matched: a scroll event arrived in the 300ms before it)
E3 motion-listen off: motion events seen=44, scroll events from the app meanwhile=0 (...)
```

`down=` is when the touch screen reported the finger. `recv=` is how much
later the probe knew.

**The "before that EVENT" figure is not the margin.** It compares the touch
signal with the scroll *event*, and the event is late: starting from rest, an
app sends its first scroll event about 100 ms after the content first moved.
So the figure overstates the time to spare by about that much. If you put the
finger down and paused before dragging, most of the figure is your pause.
Use it to compare runs, not as the answer.

**"touch during scroll, not matched"** is what step 4 should produce. A list
that is still moving has a scroll event already on its way when the finger
lands, and pairing the touch with that would be nonsense. The probe leaves
such a touch unpaired. If touches in step 3 are marked this way, the list had
not stopped yet: wait longer between gestures.

### What to write down

- `recv=` for the outside-touch watcher: typical and worst.
- "first VIEW_SCROLLED event was sent +N ms": typical for a drag and for a
  flick, from step 3 only.
- From the film: does the red block appear before the feed moves? By how many
  frames, and so how many milliseconds? This is the margin.
- Does every touch produce a line? Try a second finger, a touch on the status
  bar and the back gesture.
- Does scrolling, tapping and pinching work as usual with the watcher on?
- The motion-listen test: did the screen stop responding? `motion events
  seen` and `scroll events` from the log.
- Whether any `E3 accessibility event TYPE_TOUCH_...` line ever appeared.

## 8. E4: does a notification banner show before it can be dismissed?

**Answers:** whether a spoiler in a notification can be seen in the moment
before the app removes it (open risk 5).

The probe's notification listener dismisses every notification from one app
the moment Android reports it. The app is the probe itself unless you change
it.

### Three things that hide a banner and have nothing to do with dismissal

Each of these would put "no banner" on film for the wrong reason. Rule them
out first.

- **The film strip.** It is an opaque band over the whole status bar. Turn it
  off for E4. The probe does this by itself whenever it posts a test
  notification or is pointed at a real app, and says so in the log.
- **Swiping a banner away.** If you push a banner up off the screen, Android
  shows no more banners from that app for a minute. So in the control runs
  below, do not touch the banner. Let it leave by itself.
- **One UI's pop-up style.** Settings > Notifications > Notification pop-up
  style is either Brief or Detailed, and the two are drawn differently. Write
  down which is set, and do the whole test once with each.

### Steps

1. In SB Probe turn the film strip off.
2. **Control run, before.** Turn **Dismiss every notification from this
   app** off. Press **Post a test notification in 3 s** and go to the home
   screen. A banner must appear at the top. Do not touch it. If none
   appears, check Do Not Disturb and the notification settings for SB Probe
   before going on, or the real test proves nothing.
3. Turn the switch back on. Start filming the top of the screen in slow
   motion. Press the button and go to the home screen. Stop filming a few
   seconds later.
4. Do it ten times. Include: over YouTube, with a video in full screen, and
   with the phone locked and the screen on.
5. **Control run, after.** Turn the switch off again and post one more test.
   A banner must appear again. If it does not, something other than the
   probe suppressed the banners, and the ten runs prove nothing.
6. Read the log: `.\tools\probe.ps1 log 40`.
7. Change the pop-up style and repeat steps 2 to 6.
8. **A real app.** Put a real app's package name in the box, for example
   `com.instagram.android`. `adb shell pm list packages -3` lists the
   packages of the apps you installed. Have a notification sent to you (ask
   someone to message you) and film it.

**Dismissing a real app's notifications loses them.** The probe keeps no
copy; the log records only that one was dismissed, and when. So that this
cannot be left on by mistake, it switches itself back to the probe's own
package after 15 minutes. While it lasts, the SB Probe controls notification
and the probe's screen say `DISMISSING every notification from <package>
until <time>`. Changing the package or the switch starts the 15 minutes
again.

From the PC, `.\tools\probe.ps1 notify` posts the same test banner.

Android can also post a notification from adb itself:

```powershell
adb shell "cmd notification post -t 'Some title' sometag 'Some text'"
.\tools\probe.ps1 cancel on com.android.shell
```

It comes from the package `com.android.shell` at default importance, which
shows no banner. It is useful for the timings only.

### What to read in the log

```
E4 test notification id=100: notify() called at up=2566560, returned after 2ms
E4 dismiss requested pkg=... id=100 posted=... listenerCalled=+228ms cancelReturned=+231ms (ms after Android accepted the notification) sinceNotifyCall=+229ms
E4 removed from Android's list pkg=... id=100 reason=10 +30ms after the listener was called (not the end of a banner: one that reached the screen stays about a second)
```

**The log cannot say whether a banner was seen. Only the film can.**

- Android tells the status bar about a new notification at the same moment as
  it tells the probe. From then on it is a race between the status bar
  putting the banner up and the probe's dismissal reaching it.
- `removed from Android's list` is when Android dropped the notification from
  its records. It is not when a banner left the screen.
- If the banner did reach the screen, the status bar keeps it there for a
  minimum time before taking it down. In Android's own status bar that is 2
  seconds, or half a second in some configurations. One UI's may differ.
- So the outcome is all or nothing: no banner at all, or a banner that stays
  for a second or so. Expect to see one or the other in each run, not a
  flicker.
- `listenerCalled=+2xx ms` on the emulator is not time a banner was showing.
  When a "notification assistant" is switched on, Android holds every new
  notification for 200 ms before telling anyone, the status bar included. On
  a phone without one the figure is small. Either way it is delay before the
  race starts.

### What to write down

- The pop-up style (Brief or Detailed) for each set of runs.
- Did both control runs, before and after, show a banner?
- In how many of the ten runs was a banner visible at all? For how long, in
  frames of film?
- Did the phone make a sound or vibrate? Did a status bar icon, the lock
  screen, the always-on display or a watch show anything?
- Typical `listenerCalled` and `removed` figures, as background only.
- The same for the real app.

## 9. E5: does a per-window screenshot leave out the boxes?

**Answers:** whether thumbnail scanning (after v1) could photograph a feed
without photographing the app's own black boxes, and how often.

On request the probe calls `takeScreenshotOfWindow` (Android 14) for the
window under the box. It then looks for the box's colour where the box is.

"The box is not in the picture" is only an answer if the picture really shows
the app. Three things guard that, and the third is you:

- As a control the probe takes a screenshot of the whole display straight
  afterwards, in which the box must be found. This shows the check can find a
  box. It says nothing about the window picture.
- The probe tests the window picture for being one flat colour. A blank
  picture would not contain the box either.
- Open the window picture and see that the post under the box is in it.

If a guard fails the log says `NO ANSWER`.

### Steps

1. Open YouTube's home feed with the box showing.
2. Run `.\tools\probe.ps1 screenshot`, or press **Screenshot** on the
   notification.
3. Switch mechanism and repeat, for all four.
4. Run `.\tools\probe.ps1 ratetest`, or press **Rate test**, and wait 15
   seconds.
5. Do one screenshot in Instagram too, and one on a screen that might forbid
   screenshots, such as a banking app.
6. `.\tools\probe.ps1 pull`, then look at the images in `captures\`.

### What to read in the log

```
E5 window screenshot: took=10ms copy=78ms size=1080x2424 boxRegion=[4,1897][1076,2231] cyan pixels=0% verdict=BOX_ABSENT wholeImage: cyan=0% not blank file=...
E5 display screenshot: took=10ms copy=200ms ... cyan pixels=100% verdict=BOX_PRESENT wholeImage: cyan=14% not blank file=...
E5 result mech=wm-canvas: the per-window screenshot LEAVES OUT the box. Open the window image and confirm the post under the box can be seen in it.
E5 rate-test gap=320ms (measured 322ms) -> INTERVAL_TIME_SHORT (asked again too soon)
E5 rate-test gap=340ms (measured 343ms) -> ok
E5 rate-test result: shortest measured gap that worked=343ms longest measured gap that was refused=322ms valid pairs=10 invalid pairs=0 call time median=14ms max=59ms n=15
```

`took` is the time until Android returned the screenshot. `copy` is the extra
time to turn it into pixels that can be read. `wholeImage` is the share of
the whole picture that has the box colour, and whether the picture is `BLANK
(one flat colour)`.

The rate test takes pairs of screenshots a set time apart. The gap that
counts is the *measured* one: a busy phone stretches what was asked for. A
pair whose first shot failed is `INVALID` and is left out, because nothing
then started Android's timer. If the result line ends in `INCONSISTENT`, run
the test again.

Android's own source sets the limit at one screenshot per window every 333
ms. One UI may differ; the rate test shows what this phone does.

### What to write down

- For each mechanism: left out or included?
- For each mechanism: did you open the window image, and is the post that was
  under the box visible in it?
- `took` and `copy`, typical.
- The rate limit: shortest measured gap that worked, longest that was
  refused, and how many pairs were invalid.
- Any screen that answers `SECURE_WINDOW`.

## 10. E6: does One UI stop the service?

**Answers:** whether the services survive being left alone, with and without
the battery setting changed (open risk 4).

While they are connected, each service writes a heartbeat line once a minute.
The timer counts awake time only and never wakes the phone. So a sleeping
phone writes no heartbeats, and that is not a failure. A failure is the phone
being awake with no heartbeat. Each line carries both clocks, and the summary
works out which it was. Each line also carries the battery setting and
Android's "standby bucket" for the probe, so a change during a run shows.

### What one night can and cannot show

Samsung's own description of its battery management says a background app
that has not been used for about 3 days, and that it judges bad for the
battery, is put to "sleep", and one unused for about 16 days into "deep
sleep" (developer.samsung.com/mobile/app-management.html).
A night starts minutes after you last opened SB Probe, and you open it again
in the morning. So:

- A clean night shows the service survives a night. It is a lower bound. It
  does not show the service survives being left alone.
- Two clean nights, one with each battery setting, do not show that the
  setting makes no difference. The difference may only appear after days.

The question the real app cares about needs the long run below.

### Steps

| Run | Battery setting for SB Probe | Length |
|---|---|---|
| A | As installed. Do not touch it. | One night |
| B | Unrestricted. | One night |
| C | As installed. | 4 days or more, without opening SB Probe |

The setting is under Settings > Apps > SB Probe > Battery. **Open app settings
(battery)** in the probe goes to the app's page. The probe shows the current
setting in its E6 section.

For runs A and B:

1. In SB Probe turn off the film strip and the touch watcher, and set
   tracking to `events`.
2. Check that both services say "ON and connected".
3. Press **Start a new run** (or `.\tools\probe.ps1 runstart night-a`).
4. Unplug the phone. Android only goes into its deepest sleep on battery.
   Lock it and leave it lying still for at least eight hours.
5. In the morning, **before opening SB Probe**, check that the services still
   do their work. A heartbeat only proves that the process ticks. It does not
   prove that Android still sends it events. In this order:
   - With the phone on the PC, run `.\tools\probe.ps1 e6 4`. When was the
     last heartbeat of each service? This only reads the log.
   - Open the phone's Settings app. Does the coloured box appear on a list
     item and follow a scroll?
   - Run `.\tools\probe.ps1 notify 1000` and then `.\tools\probe.ps1 log 5`.
     Is there an `E4 dismiss requested` line? (This command starts the
     probe's process if it was dead. The summary will still show the gap.)
6. Now open SB Probe and read the Setup section: are both services still
   "ON and connected"?
7. Scroll to the E6 section and read the summary.
8. `.\tools\probe.ps1 pull` to keep the log.

For run C:

1. Do steps 1 to 3 above (`runstart long-c`), then close SB Probe.
2. Use the phone as you normally do for at least four days. Do not open SB
   Probe, do not tap its notification, and do not reinstall it. Charging is
   fine.
3. Each day, if the phone is at the PC anyway, look without touching the
   probe: `.\tools\probe.ps1 e6 6`. The last heartbeat lines show whether it
   is still ticking and which `bucket=` it is in. This reads a file; it does
   not start or wake the probe.
4. At the end: first `.\tools\probe.ps1 pull`, so that the log is saved as it
   was. Then do step 5 above (box and test notification), and only then open
   SB Probe and read the summary.

During any run do not reinstall the probe and do not run `dump`. Both restart
the service and would show up as a gap.

Optional: use the phone for a day including swiping SB Probe away from the
recent-apps screen and using "Close all", and see what the log shows.

### Reading the summary

```
As of 06:44:02:
Run started 2026-10-05 22:40:11 battery=optimised bucket=active label=night-a
Process started 0 time(s) since then (more than 0 means it died and came back). Reboots: 0.
Standby bucket during the run: active, then working-set.
Battery setting during the run: optimised.
a11y: 131 heartbeats over 8 h 02 min, of which 5 h 53 min in deep sleep. Reconnected 0 time(s), disconnected 0 time(s).
a11y: longest awake time between heartbeats: 1 min 00 s (about 1 min is normal).
a11y: no gaps.
a11y: last heartbeat 41 s of awake time ago.
```

"As of" is when the summary was worked out. That happens each time you come
back to the probe's screen, every half minute while it is open, and when you
press **Refresh**. `a11y` is the accessibility service and `nls` the
notification listener. This example is made up, to show a good night.

"Standby bucket" is Android's grading of how recently an app was used:
`active`, `working-set`, `frequent`, `rare`, `restricted`. The further down,
the more Android holds the app back. The summary lists every change. A move
to `restricted` during a long run is itself a finding.

A gap is reported when more than 90 seconds of awake time pass without a
heartbeat:

| The summary says | Meaning |
|---|---|
| the app's process died and was started again | Android or One UI killed the probe, and something brought it back. The "Earlier process exit" lines say who killed it and why. |
| Android disconnected the service but the process stayed alive | The service was unbound and bound again. |
| the process stayed alive but did not run (a freezer, or a blocked main thread; the log cannot say which) | The probe existed but ran no code for that long. Either the system froze it, or its own main thread was stuck. The log cannot tell these apart. |
| the phone was rebooted | Not a failure of the probe. |
| NOT RUNNING. No heartbeat for ... | The service stopped and never came back. |
| NOT RUNNING. No heartbeat at all in the ... since the run began | The service has written nothing since the run was marked. It was not connected when the run began, or stopped within the first minute. |

"no heartbeat yet" only appears in the first minute and a half of a run.

In the log, `E6 exit-info ... reason=... description=...` is Android's own
record of why an earlier process ended. `USER_REQUESTED` with `[FORCE STOP]`
means something force-stopped the app. A force stop also switches the
accessibility service off in Settings; the probe then shows "Accessibility
service: off", and the heartbeat lines of the other service show
`a11y-setting=off` from that minute on.

### What to write down

For each run:

- The battery setting, and where SB Probe is listed under Settings > Device
  care > Battery > Background usage limits (Sleeping apps, Deep sleeping apps,
  Never auto sleeping apps), if anywhere. Look again at the end of run C.
- Before the probe was opened: did the box appear, and did the test
  notification produce an `E4` line?
- Was each service on in Settings? Connected?
- Hours covered, hours asleep, number of heartbeats.
- Longest awake time between heartbeats.
- Every gap: kind, when, how long.
- The standby buckets and battery settings the summary lists.
- Process starts, and every `exit-info` line.

Report a clean night as "survived one night", not as "One UI does not stop
the service".

## 11. When something goes wrong

| What you see | What to do |
|---|---|
| `adb.exe was not found` | Install Android Studio, or open a new PowerShell window after installing it. |
| No line under "Devices adb can see" | Try another cable or USB port. Check USB debugging is on. See Auto Blocker in 2.2. |
| `unauthorized` | Answer "Allow USB debugging?" on the phone. |
| "more than one device/emulator" | Add `-Serial <serial>` to the script. |
| "running scripts is disabled on this system" | Run the `Set-ExecutionPolicy` line in 2.1, or start the script as `powershell -ExecutionPolicy Bypass -File tools\probe.ps1 status`. |
| The install fails | See Auto Blocker in 2.2. If the message has `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall SB Probe on the phone and install again. |
| The switch is greyed out, "Restricted setting" | Section 2.4. |
| "ON in Settings but NOT connected" | Turn the switch off and on again in Settings. If it happens by itself, that is a finding for E6: write down when. |
| No box | Is "Show the box" on? Does the screen have a list? Try **Next list**. The last `E2` lines of the log say what the probe found. Some windows, Android's permission dialogs for one, give a service that is not an assistive tool nothing at all to read. |
| The box is on the wrong thing | **Next item** walks through the list. **Next list** tries the next scrollable part of the screen. |
| Only the green (`sc-window`) box is in the wrong place, or missing | The item does not report its position within its window and the probe's own working-out was wrong. Copy the `E2 picked item ...` line into the findings. |
| The film strip turned itself off | Posting an E4 test, or pointing E4 at a real app, turns it off so that it cannot hide a banner. Turn it on again for E2 and E3. |
| The E4 package went back to the probe's own | That is the 15-minute limit on dismissing a real app's notifications. Enter the package again to go on. |
| No SB Probe controls notification | Allow notifications for SB Probe (2.3). The notification only exists while the accessibility service is connected. |
| The buttons are not visible | Expand the notification with the small arrow on its right. |
| The screen ignores touches | The motion-event test is running. It stops by itself after ten seconds. |
| After reinstalling the probe the services are off | Android sometimes switches an accessibility service off when its app is updated. Switch it on again. |
| `run-as: package not debuggable` or similar | Follow the log with `adb logcat -s SBProbe` instead and tell the coordinator. `run-as` was not tested on One UI. |

## 12. Cleaning up

When the experiments are done:

1. Turn off both switches in Settings (accessibility and notification
   access).
2. Uninstall SB Probe. This deletes its log, dumps and screenshots from the
   phone.
3. Turn Auto Blocker back on if you turned it off.
4. Keep the `captures` folder on the PC only as long as you need it. It is
   ignored by git. Do not copy from it into the repository.

Nothing above turns off what section 2.2 turned on. These three stay on until
you undo them, and the later phases of the project need them again:

- **USB debugging.** While it is on, a PC the phone has authorised can read
  and control the phone over the cable. To turn it off: Settings > Developer
  options > USB debugging.
- **The PC's authorisation.** You ticked "Always allow from this computer".
  To take that back: Settings > Developer options > **Revoke USB debugging
  authorisations**. The phone will ask again next time.
- **Developer options.** Harmless by itself. To hide it again, turn off the
  switch at the top of Settings > Developer options.

If you stop here for a while, turn USB debugging off. If you go straight on
to Phase 1, leave all three as they are.

## 13. Template for docs/FINDINGS.md

Copy this into a new file `docs/FINDINGS.md` on the branch that updates the
architecture, and fill it in. Describe structure and give numbers. Do not
paste text from real screens or notifications.

````markdown
# Phase 0 findings

Measured on: (date). Phone: Galaxy S25 Ultra, Android (version), One UI
(version). Probe commit: (hash). YouTube version: (x). Instagram version: (x).

Phone settings during the tests: refresh rate (Motion smoothness: Adaptive or
Standard): . Power saving: on/off. Notification pop-up style: Brief/Detailed.
Other accessibility services switched on: .

Setup notes: was the "Restricted setting" dialog shown for an app installed
over USB? (yes/no). Did Auto Blocker have to be turned off? (yes/no). Was the
USB debugging switch greyed out while Auto Blocker was on? (yes/no).

## E1: what text do YouTube and Instagram expose?

Affects: decisions 6, 9 and 10; open risks 1 and 3; the open decision "Which
Instagram screens are in v1?"; "Reels, Shorts and Stories" under Not in v1;
the budget "Reading and matching one screen: under 30 ms".

| Screen | Title or caption exposed? | Channel or account exposed? | One element per post? (class, view id; marked ITEM or SRF?) | Elements | Cold walk (ms) | Warm walk (ms) | `uiautomator dump` worked? |
|---|---|---|---|---|---|---|---|
| YouTube home | | | | | | | |
| YouTube search | | | | | | | |
| YouTube watch page | | | | | | | |
| YouTube Shorts | | | | | | | |
| Instagram feed | | | | | | | |
| Instagram Explore | | | | | | | |
| Instagram Reels | | | | | | | |
| Instagram Stories | | | | | | | |
| Instagram messages | | | | | | | |

The cold walk is an upper bound on reading one screen: nothing was cached,
and it includes layout-only elements and the work of writing the dump, which
the real app will not do. The real figure lies below it.

- Can text be grouped into posts with one generic rule? (yes/no, and the rule)
- Do the apps mark list items themselves (ITEM, SRF)? On which screens?
- Differences between the service's view and `uiautomator dump`:
- Anything the service could not read at all:
- Any dump cut off at the node limit:
- **Answer for the architecture:** which screens can v1 cover, and is 30 ms
  per screen realistic given that the cold walk is an upper bound?

## E2: how far does a box lag behind a scrolling post?

Affects: decision 11; open risk 2; the open decision "Strict covering while
scrolling, or cover only matched posts and accept a brief flash?"; the budget
"Time from scroll settling to reveal: under 150 ms"; Phase 4.

Film: (camera, frames per second). Lag is counted in frames of film; the
on-screen clock was used only to find log lines.

From the summary: display mode (Hz): . Poll frame interval (median/p90/max
ms): .

| Mechanism | Tracking | Lag on slow drag (film frames = ms / fraction of a post) | Lag on flick (film frames = ms / fraction of a post) | Log: commit median / p90 / max (ms), and commit-n of n | Notes |
|---|---|---|---|---|---|
| wm-move | events | | | not available | |
| wm-canvas | events | | | | |
| sc-display | events | | | | |
| sc-window | events | | | | |
| wm-move | poll | | | not available | |
| wm-canvas | poll | | | | |
| sc-display | poll | | | | |
| sc-window | poll | | | | |

The commit column starts from a different moment in events rows (the app sent
the event, up to 100 ms after the content moved) and in poll rows (the
probe's frame began). Compare it within one kind only. It is the compositor's
commit; the panel shows the change about two refreshes later.

- Time from the list stopping to the box sitting exactly on the post (film
  frames = ms):
- End of a scroll: longest gap between neighbouring scroll events (ms): .
  Last scroll event arrived (ms) after the content stopped on film: .
  So the earliest an app could know a scroll had ended (ms): .
- Does polling make the feed stutter? (yes/no)
- Same result in Instagram? (yes/no, differences)
- **Answer for the architecture:** can a box follow a moving post well enough
  to leave unmatched posts uncovered while scrolling? (yes/no) If yes, with
  which mechanism and tracking? If no, strict covering it is. Is "scroll
  settling to reveal under 150 ms" reachable given the figures above?

## E3: can the start of a scroll be detected before new content is visible?

Affects: decision 11 and step 2 of "While a blocker is on" ("When scrolling
starts, the scrolling area is covered"); "Tap to reveal a covered post" under
Not in v1.

| Mechanism | Fires on every touch? | Delay after touch-down (ms, typical / worst) | App still fully usable? |
|---|---|---|---|
| Outside-touch watcher | | | |
| Motion events (`onMotionEvent`) | | | |
| Touch-interaction accessibility events | | | |

- First scroll event after touch-down, list at rest (ms): drag: , flick: .
  (Background only: the event is sent about 100 ms after the content first
  moves, so this is not the margin.)
- **The margin, from the film:** the touch marker appeared (N) frames = (N)
  ms before the feed first moved. Drag: . Flick: .
- Touches that produced no signal:
- **Answer for the architecture:** can the feed be covered before it moves?
  (yes/no, by which mechanism, with how much margin) This holds for a cover
  that already exists and only has to be filled in; creating one on
  touch-down was not measured.

## E4: does a notification banner show before it can be dismissed?

Affects: open risk 5; decisions 13 and 16; Phase 1.

The film strip was off. Control banners were left to time out, not swiped.

| Pop-up style | Case | Control before showed a banner? | Runs | Banner visible in (N) runs | How long when visible (film frames = ms) | Sound or vibration? | Control after showed a banner? |
|---|---|---|---|---|---|---|---|
| Brief | Probe test, home screen | | | | | | |
| Brief | Probe test, over YouTube | | | | | | |
| Brief | Probe test, phone locked | | | | | | |
| Brief | Real app: (package) | | | | | | |
| Detailed | Probe test, home screen | | | | | | |
| Detailed | Probe test, over YouTube | | | | | | |
| Detailed | Probe test, phone locked | | | | | | |
| Detailed | Real app: (package) | | | | | | |

A banner is all or nothing: it does not appear, or it stays for about a
second. The log cannot say which; only the film can.

- Background from the log: listener called after (ms): . Removed from
  Android's list after (ms): .
- Shown anywhere else? (status bar icon, lock screen, always-on display, watch)
- **Answer for the architecture:** is dismissing after the fact good enough
  for v1? (yes/no) In what share of runs did a banner show? If not good
  enough, what leaks?

## E5: does a per-window screenshot leave out the app's own boxes?

Affects: "Thumbnail scanning" under Not in v1; item 1 of "After v1";
decision 3 (Android 14 as the minimum, for per-window screenshots).

| Mechanism | Box left out of the window screenshot? | Control found the box? | Window image opened: post under the box visible? |
|---|---|---|---|
| wm-move | | | |
| wm-canvas | | | |
| sc-display | | | |
| sc-window | | | |

- Time for one screenshot (ms): call: , copy to readable pixels:
- Rate limit: shortest measured gap that worked (ms): , longest measured gap
  that was refused (ms): , invalid pairs:
- Screens that refuse screenshots (`SECURE_WINDOW`):
- **Answer for the architecture:** can thumbnails be scanned under the boxes,
  and how many screenshots per second are possible?

## E6: does One UI stop the service?

Affects: open risk 4; decisions 12 and 17; the health monitor (Phase 4) and
the setup screen for One UI's battery setting (Phase 5).

| Run | Battery setting | Length | Time asleep | Longest awake gap between heartbeats | Gaps (kind, when, how long) | Process starts | Standby buckets seen | Before opening the probe: box shown? E4 line? | Services on in Settings at the end? |
|---|---|---|---|---|---|---|---|---|---|
| A: one night, as installed | | | | | | | | | |
| B: one night, unrestricted | | | | | | | | | |
| C: 4+ days without opening SB Probe | | | | | | | | | |

A clean night is a lower bound: it shows the service survived that night.
Samsung puts apps to "sleep" after about 3 days unused and into "deep sleep"
after about 16, so only run C speaks to being left alone, and nothing here
reaches 16 days.

- Where SB Probe was listed under Background usage limits, at the start and
  at the end of run C:
- `exit-info` lines (reason and description, no personal data):
- What swiping the app away from recent apps did:
- **Answer for the architecture:** does the service survive untouched for a
  night? For several days? With "Unrestricted"? What must the setup screen
  ask the user to change, and what does the health monitor need to detect?

## Changes to make in ARCHITECTURE.md

- Decision 11: (new status and wording)
- Budgets: (revised numbers)
- Open risks 2, 4, 5: (updated)
- Open decisions: (answers)
````

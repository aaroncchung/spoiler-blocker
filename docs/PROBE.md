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
2. **Turn on USB debugging.** Settings > Developer options > **USB
   debugging**.
3. Connect the phone to the PC. The phone asks "Allow USB debugging?". Tick
   "Always allow from this computer" and tap Allow.
4. Check that the PC sees it:

   ```powershell
   .\tools\probe.ps1 status
   ```

   Under "Devices adb can see" there should be one line ending in `device`.
   `unauthorized` means the question on the phone was not answered; unplug,
   plug in again and look at the phone.

**Auto Blocker.** Samsung's Auto Blocker (Settings > Security and privacy >
Auto Blocker) blocks apps from sources other than the Play Store and Galaxy
Store, and "blocks commands by USB cable". If adb cannot see the phone, or the
install in the next step is refused, turn Auto Blocker off for the
experiments and on again afterwards. This was not tested: no Samsung phone
was available when the probe was built.

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
| Summary | E2: writes the timing summary to the log |
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
| `.\tools\probe.ps1 summary reset` | `-a sbprobe.E2_SUMMARY --ez reset true` |
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
.\tools\probe.ps1 pull          # copy the log, dumps and screenshots into captures\
adb logcat -s SBProbe           # follow it live; Ctrl+C stops
```

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

The example lines in this document were copied from the emulator. They show
what a line looks like. The numbers in them are not findings.

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

The script prints an outline of each dump: only the elements that carry text,
indented by depth.

```
12             TextView #title text="..." [210,652][510,723]
```

The columns are depth, class, `#` view id, text, description (`desc=`, what a
screen reader would say) and bounds as `[left,top][right,bottom]` in pixels.
`.\tools\probe.ps1 outline captures\<file>` prints an outline again later.

The JSON file has one element per line, with the index of its parent, so the
full tree can be rebuilt from it.

### What to write down

For each screen:

- Is the post's title there? The channel or account name? The caption? As
  `text` or as `desc`?
- Is there one element per post whose bounds cover the whole post, with the
  title and the channel underneath it? Note its class and view id. This
  decides whether "group text into posts" can be generic.
- Is anything in the `uiautomator` outline missing from the service's outline,
  or the other way round?
- Did `uiautomator dump` fail?
- From the log line `E1 dump ... nodes=N walk=Nms`: the number of elements and
  the time the service took to read them all.
- For Shorts, Reels and Stories: is there any text at all beyond the caption?

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
3. Prop the phone up. Start filming it in slow motion with the second device.
4. Scroll three ways: a slow drag up and down, a quick flick, and a flick
   stopped with a tap.
5. Stop filming. Press **Summary** on the notification (or
   `.\tools\probe.ps1 summary reset` on the PC).
6. Switch to the next mechanism and repeat from step 3, for all four.
7. Set tracking to `poll` and do all four again.
8. Repeat the best and the worst combination in Instagram's feed.

That is eight short clips in YouTube. The box colour says which mechanism a
clip shows.

No second camera? Use the phone's screen recorder (in the quick panel). It
records what the screen was told to show, so each recorded frame is exact. But
it records fewer frames per second than the screen shows, and it cannot show
how late the panel itself is.

### What to look at in the film

The film strip sits over the status bar. Its left third is a clock: seconds
and milliseconds of the `up=` clock, so `34.567` on film is `up=...34567` in
the log.

- While the list moves, how far is the box from its post? Judge it against
  the height of the post: "a tenth", "half", "a whole post".
- When the post starts moving, read the clock. When the box starts moving,
  read it again. The difference is the lag.
- In `events` mode, does the box jump in steps?
- In `poll` mode, does it stay on the post? Does the feed itself scroll less
  smoothly than with the box off?
- After the list stops, how long until the box sits exactly on the post?

The clock digits reach the screen a frame or two after they were drawn. Trust
differences between two readings, not single readings.

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
| `commit=` | Milliseconds until Android's compositor took the change. Not available for `wm-move`, where the window manager moves the window on its own schedule. |
| `top=`, `moved=` | The box's new top edge, and how far it moved, in pixels. |
| `dy=` | The scroll distance the event reported, if any. |

The summary gives the median, the 90th percentile and the maximum of each
column, for each mechanism and cause:

```
E2 summary mech=wm-canvas by=poll n=62 recv=0/0/0 bounds=6/10/12 submit=7/10/13 frame=0/0/0 commit=33/35/36 (median/p90/max ms after the event)
```

**These numbers are a floor, not the lag.** `evt` is when the app *sent* the
event. Android makes an app wait up to 100 ms after the content moved before
it sends one. The film shows the true lag; the log shows how it divides up
after the event.

### What to write down

- For each of the eight combinations: lag in milliseconds and as a fraction
  of a post, on a slow drag and on a flick, from the film.
- The summary lines.
- Which combination is best, and whether even the best is good enough to
  leave unmatched posts uncovered while scrolling.
- Whether `poll` makes the feed stutter.
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
3. Put a finger down, pause, drag. Then flick several times. Then tap videos,
   use the back gesture, pinch a photo in Instagram: does everything still
   behave as normal?
4. Read the log: `.\tools\probe.ps1 log 60`.
5. Back in SB Probe, press **Listen to motion events for 10 s** and try to
   scroll the probe's own screen. Then read the log again.

### What to look at

On film, the middle block of the film strip turns red when the probe hears
of the touch, and the right block turns blue when a scroll event arrives. The
strip is drawn the way a cover would be. So if the red block is on screen
before the feed moves, a cover would have been too. Count the frames between
the red block appearing and the feed first moving.

In the log:

```
E3 touch #23 src=outside-touch down=3650482 recv=+1ms
E3 touch #23: first VIEW_SCROLLED sent +165ms and received +172ms after touch-down; the outside-touch signal was 171ms ahead of it
E3 motion-listen off: motion events seen=44, scroll events from the app meanwhile=0 (...)
```

`down=` is when the touch screen reported the finger. `recv=` is how much
later the probe knew.

### What to write down

- `recv=` for the outside-touch watcher: typical and worst.
- "first VIEW_SCROLLED sent +N ms": typical for a slow drag and for a flick.
- From the film: does the red block appear before the feed moves? By how many
  frames or milliseconds?
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

### Steps

1. **Control run.** In SB Probe turn **Dismiss every notification from this
   app** off. Press **Post a test notification in 3 s** and go to the home
   screen. A banner must appear at the top. If none does, check Do Not Disturb
   and the notification settings for SB Probe before going on, or the real
   test proves nothing.
2. Turn the switch back on. Start filming the top of the screen in slow
   motion. Press the button and go to the home screen. Stop filming a few
   seconds later.
3. Do it ten times. Include: over YouTube, with a video in full screen, and
   with the phone locked and the screen on.
4. Read the log: `.\tools\probe.ps1 log 40`.
5. **A real app.** Put a real app's package name in the box, for example
   `com.instagram.android`. `adb shell pm list packages -3` lists the
   packages of the apps you installed. Have a notification sent to you (ask
   someone to message you) and film it. Every notification from that app is
   dismissed while this is on, so set the package back afterwards.

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
E4 removed pkg=... id=100 reason=10 +30ms after the listener was called
```

`listenerCalled` is usually just over 200 ms. That is not time the banner was
showing. Android holds every new notification for 200 ms, so that its own
assistant can adjust it, before it tells anyone, the status bar included. The
window in which a banner could show starts when the listener is called and
ends at `removed`: 30 ms in the lines above.

### What to write down

- Was the banner visible in any frame? In how many of the ten runs, and for
  how many frames?
- Did the phone make a sound or vibrate? Did a status bar icon, the lock
  screen, the always-on display or a watch show anything?
- Typical `listenerCalled` and `removed` figures.
- The same for the real app.

## 9. E5: does a per-window screenshot leave out the boxes?

**Answers:** whether thumbnail scanning (after v1) could photograph a feed
without photographing the app's own black boxes, and how often.

On request the probe calls `takeScreenshotOfWindow` (Android 14) for the
window under the box. It then looks for the box's colour where the box is. As
a control it takes a screenshot of the whole display straight afterwards, in
which the box must be found. If the control fails, the check is not working
and the answer means nothing; the log says so.

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
E5 window screenshot: took=10ms copy=78ms size=1080x2424 boxRegion=[4,1897][1076,2231] cyan pixels=0% verdict=BOX_ABSENT file=...
E5 display screenshot: took=10ms copy=200ms ... cyan pixels=100% verdict=BOX_PRESENT file=...
E5 result mech=wm-canvas: the per-window screenshot LEAVES OUT the box
E5 rate-test gap=320ms (actual 322ms) -> INTERVAL_TIME_SHORT (asked again too soon)
E5 rate-test gap=340ms (actual 343ms) -> ok, took=9ms
E5 rate-test result: shortest gap that worked=340ms longest gap that failed=320ms call time median=14ms max=59ms n=15
```

`took` is the time until Android returned the screenshot. `copy` is the extra
time to turn it into pixels that can be read.

Android's own source sets the limit at one screenshot per window every 333
ms. One UI may differ; the rate test shows what this phone does.

### What to write down

- For each mechanism: left out or included?
- `took` and `copy`, typical.
- The rate limit: shortest gap that worked, longest that failed.
- Any screen that answers `SECURE_WINDOW`.

## 10. E6: does One UI stop the service?

**Answers:** whether the services survive a night, with and without the
battery setting changed (open risk 4).

While they are connected, each service writes a heartbeat line once a minute.
The timer counts awake time only and never wakes the phone. So a sleeping
phone writes no heartbeats, and that is not a failure. A failure is the phone
being awake with no heartbeat. Each line carries both clocks, and the summary
works out which it was.

### Steps

Do two runs on two nights.

| Run | Battery setting for SB Probe |
|---|---|
| A | As installed. Do not touch it. |
| B | Unrestricted. |

The setting is under Settings > Apps > SB Probe > Battery. **Open app settings
(battery)** in the probe goes to the app's page. The probe shows the current
setting in its E6 section.

For each run:

1. In SB Probe turn off the film strip and the touch watcher, and set
   tracking to `events`.
2. Check that both services say "ON and connected".
3. Press **Start a new run** (or `.\tools\probe.ps1 runstart night-a`).
4. Unplug the phone. Android only goes into its deepest sleep on battery.
   Lock it and leave it lying still for at least eight hours.
5. In the morning, **before anything else**, open SB Probe and read the Setup
   section: are both services still "ON and connected"?
6. Scroll to the E6 section, press **Refresh** and read the summary.
7. `.\tools\probe.ps1 pull` to keep the log.

During a run do not reinstall the probe and do not run `dump`. Both restart
the service and would show up as a gap.

Optional third run: use the phone normally for a day, including swiping SB
Probe away from the recent-apps screen and using "Close all".

### Reading the summary

```
As of 06:44:02:
Run started 2026-10-05 22:40:11 battery=optimised bucket=active label=night-a
Process started 0 time(s) since then (more than 0 means it died and came back). Reboots: 0.
a11y: 131 heartbeats over 8 h 02 min, of which 5 h 53 min in deep sleep. Reconnected 0 time(s), disconnected 0 time(s).
a11y: longest awake time between heartbeats: 1 min 00 s (about 1 min is normal).
a11y: no gaps.
a11y: last heartbeat 41 s of awake time ago.
```

"As of" is when the summary was worked out. That happens each time you come
back to the probe's screen, every half minute while it is open, and when you
press **Refresh**. `a11y` is the accessibility service and `nls` the
notification listener. This example is made up, to show a good night.

A gap is reported when more than 90 seconds of awake time pass without a
heartbeat:

| The summary says | Meaning |
|---|---|
| the app's process died and was started again | Android or One UI killed the probe, and something brought it back. The "Earlier process exit" lines say who killed it and why. |
| Android disconnected the service but the process stayed alive | The service was unbound and bound again. |
| the process stayed alive but did not run (frozen or blocked) | The probe existed but was given no processor time, which is what One UI's "sleeping apps" do. |
| the phone was rebooted | Not a failure of the probe. |
| NOT RUNNING. No heartbeat for ... | The service stopped and never came back. |

In the log, `E6 exit-info ... reason=... description=...` is Android's own
record of why an earlier process ended. `USER_REQUESTED` with `[FORCE STOP]`
means something force-stopped the app. A force stop also switches the
accessibility service off in Settings; the probe then shows "Accessibility
service: off", and the heartbeat lines of the other service show
`a11y-setting=off` from that minute on.

### What to write down

For each run:

- The battery setting, and where SB Probe is listed under Settings > Battery
  > Background usage limits, if anywhere.
- In the morning: was each service on in Settings? Connected?
- Hours covered, hours asleep, number of heartbeats.
- Longest awake time between heartbeats.
- Every gap: kind, when, how long.
- Process starts, and every `exit-info` line.

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

## 13. Template for docs/FINDINGS.md

Copy this into a new file `docs/FINDINGS.md` on the branch that updates the
architecture, and fill it in. Describe structure and give numbers. Do not
paste text from real screens or notifications.

````markdown
# Phase 0 findings

Measured on: (date). Phone: Galaxy S25 Ultra, Android (version), One UI
(version). Probe commit: (hash). YouTube version: (x). Instagram version: (x).

Setup notes: was the "Restricted setting" dialog shown for an app installed
over USB? (yes/no). Did Auto Blocker have to be turned off? (yes/no).

## E1: what text do YouTube and Instagram expose?

Affects: decisions 6, 9 and 10; open risks 1 and 3; the open decision "Which
Instagram screens are in v1?"; "Reels, Shorts and Stories" under Not in v1;
the budget "Reading and matching one screen: under 30 ms".

| Screen | Title or caption exposed? | Channel or account exposed? | One element per post? (class, view id) | Elements | Time to read all (ms) | `uiautomator dump` worked? |
|---|---|---|---|---|---|---|
| YouTube home | | | | | | |
| YouTube search | | | | | | |
| YouTube watch page | | | | | | |
| YouTube Shorts | | | | | | |
| Instagram feed | | | | | | |
| Instagram Explore | | | | | | |
| Instagram Reels | | | | | | |
| Instagram Stories | | | | | | |
| Instagram messages | | | | | | |

- Can text be grouped into posts with one generic rule? (yes/no, and the rule)
- Differences between the service's view and `uiautomator dump`:
- Anything the service could not read at all:
- **Answer for the architecture:** which screens can v1 cover, and is 30 ms
  per screen realistic?

## E2: how far does a box lag behind a scrolling post?

Affects: decision 11; open risk 2; the open decision "Strict covering while
scrolling, or cover only matched posts and accept a brief flash?"; the budget
"Time from scroll settling to reveal: under 150 ms"; Phase 4.

Film: (camera, frames per second).

| Mechanism | Tracking | Lag on slow drag (ms / fraction of a post) | Lag on flick (ms / fraction of a post) | Log: commit median / p90 / max (ms) | Notes |
|---|---|---|---|---|---|
| wm-move | events | | | not available | |
| wm-canvas | events | | | | |
| sc-display | events | | | | |
| sc-window | events | | | | |
| wm-move | poll | | | not available | |
| wm-canvas | poll | | | | |
| sc-display | poll | | | | |
| sc-window | poll | | | | |

- Time from the list stopping to the box sitting exactly on the post (ms):
- Does polling make the feed stutter? (yes/no)
- Same result in Instagram? (yes/no, differences)
- **Answer for the architecture:** can a box follow a moving post well enough
  to leave unmatched posts uncovered while scrolling? (yes/no) If yes, with
  which mechanism and tracking? If no, strict covering it is.

## E3: can the start of a scroll be detected before new content is visible?

Affects: decision 11 and step 2 of "While a blocker is on" ("When scrolling
starts, the scrolling area is covered"); "Tap to reveal a covered post" under
Not in v1.

| Mechanism | Fires on every touch? | Delay after touch-down (ms, typical / worst) | App still fully usable? |
|---|---|---|---|
| Outside-touch watcher | | | |
| Motion events (`onMotionEvent`) | | | |
| Touch-interaction accessibility events | | | |

- First scroll event after touch-down (ms): slow drag: , flick:
- From the film: the touch marker appeared (N) frames / (N) ms before the
  feed first moved.
- Touches that produced no signal:
- **Answer for the architecture:** can the feed be covered before it moves?
  (yes/no, by which mechanism, with how much margin)

## E4: does a notification banner show before it can be dismissed?

Affects: open risk 5; decisions 13 and 16; Phase 1.

| Case | Runs | Banner visible in (N) runs | Longest visible (frames / ms) | Sound or vibration? | Listener called after (ms) | Removed after listener called (ms) |
|---|---|---|---|---|---|---|
| Probe test, home screen | | | | | | |
| Probe test, over YouTube | | | | | | |
| Probe test, phone locked | | | | | | |
| Real app: (package) | | | | | | |

- Shown anywhere else? (status bar icon, lock screen, always-on display, watch)
- **Answer for the architecture:** is dismissing after the fact good enough
  for v1? (yes/no) If not, what leaks?

## E5: does a per-window screenshot leave out the app's own boxes?

Affects: "Thumbnail scanning" under Not in v1; item 1 of "After v1";
decision 3 (Android 14 as the minimum, for per-window screenshots).

| Mechanism | Box left out of the window screenshot? | Control found the box? |
|---|---|---|
| wm-move | | |
| wm-canvas | | |
| sc-display | | |
| sc-window | | |

- Time for one screenshot (ms): call: , copy to readable pixels:
- Rate limit: shortest gap that worked (ms): , longest that failed (ms):
- Screens that refuse screenshots (`SECURE_WINDOW`):
- **Answer for the architecture:** can thumbnails be scanned under the boxes,
  and how many screenshots per second are possible?

## E6: does One UI stop the service?

Affects: open risk 4; decisions 12 and 17; the health monitor (Phase 4) and
the setup screen for One UI's battery setting (Phase 5).

| Run | Battery setting | Hours | Hours asleep | Longest awake gap between heartbeats | Gaps (kind, when, how long) | Process starts | Services on in Settings in the morning? |
|---|---|---|---|---|---|---|---|
| A: as installed | | | | | | | |
| B: unrestricted | | | | | | | |
| C: a normal day (optional) | | | | | | | |

- `exit-info` lines (reason and description, no personal data):
- What swiping the app away from recent apps did:
- **Answer for the architecture:** does the service survive untouched? With
  "Unrestricted"? What must the setup screen ask the user to change, and what
  does the health monitor need to detect?

## Changes to make in ARCHITECTURE.md

- Decision 11: (new status and wording)
- Budgets: (revised numbers)
- Open risks 2, 4, 5: (updated)
- Open decisions: (answers)
````

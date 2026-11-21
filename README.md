# Yggdrasil Mobile

[Yggdrasil](https://github.com/SebbeJohansson/yggdrasil), rebuilt as an
Android app that runs only on the phone. No server, no PC.

The model runs on the phone too: Gemma 4 through
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Claude can be
added as a second option, through Claude Code running on the phone
([docs/claude-bridge.md](docs/claude-bridge.md)).

## Look

The same HUD as [my-widget](https://github.com/SebbeJohansson/my-widget),
the desktop Yggdrasil's dashboard (`ui/`):

- **Ground:** near-black, with the dot grid and its rolling wave, rising
  specks, and a horizon glow in the colour of the time of day (`Backdrop`).
  When something new needs you, the next wave passes amber.
- **Colour:** cyan for what is normal, amber for what wants you.
- **Panels:** corner brackets, turning amber when they warn.
- **Fonts:** Rajdhani for headings and numbers, JetBrains Mono for labels,
  Inter for reading. All are bundled; their licences are in `docs/licenses`.
- **Top bar:** the arc reactor (`ArcReactor`), one segment per part. Tap it
  for the alerts.
- **Chat:** Norn's chat reads like the dashboard's ask panel. Your lines are
  cyan after `›`. Norn's lines sit under its name, with a line down the left.
- **Alerts:** the alert list keeps its severity line and its Seen, Snooze 1h,
  1d and Mute buttons.

| Norn | Alerts | Messages | Phone | Jobs | Huginn |
| --- | --- | --- | --- | --- | --- |
| ![Norn](docs/screenshots/1-norn.png) | ![Alerts](docs/screenshots/2-alerts.png) | ![Messages](docs/screenshots/3-messages.png) | ![Phone](docs/screenshots/4-phone.png) | ![Jobs](docs/screenshots/5-jobs.png) | ![Huginn](docs/screenshots/6-huginn.png) |

**Bifröst at night**, as on the desktop: after the working day (09:00–17:00,
Monday to Friday), when the app has gone 20 s untouched (5 min during
work hours), the ground fades into a slow aurora. It doesn't come while
you're typing or Norn is writing, and any touch sends it away. Today's
alerts and the troubled parts make it stormier: a calm day is slow and
green, a stormy one restless and violet. It's never red and shows no
numbers. When Android's animations are off it stands still.

| Calm | Stormy |
| --- | --- |
| ![Calm aurora](docs/screenshots/aurora-calm.png) | ![Stormy aurora](docs/screenshots/aurora-stormy.png) |

**The boot sequence**, as on the desktop (`boot/`):

- **Once a day:** the first open shows a heads-up display that waits for a
  tap. It brings the parts online line by line, then the outside (network,
  calendar access, notifications), then the phone, and gives a verdict.
- **Voice:** a calm voice says how it went. Only a voice installed on the
  phone is used, never a network one; with none installed there's no voice.
- **Ending it:** any tap ends it, and *Skip it for today* skips it.
- **Other opens:** the quick check, the same in about a second. It's
  see-through and lets every touch through.
- **From a notification:** you get at most the quick one.
- **Settings:** under Parts → Settings (daily boot, voice, quick check, Run it
  now).

| Waiting for a tap | Systems check |
| --- | --- |
| ![Boot](docs/screenshots/0-boot.png) | ![Systems check](docs/screenshots/0-boot-check.png) |

`ScreenshotTest` and `AuroraScreenshotTest` draw these on the JVM (Robolectric) into
`app/build/screenshots/`.

## Parts

The parts keep their Yggdrasil names and jobs. On the phone they are
packages in one app, not processes talking HTTP.

| Part | On the phone | Status |
| --- | --- | --- |
| **Norn** | chat, memory, the front desk | chat with Gemma 4, saved chats, memory |
| **Gjallar** | alerts from every part, in one place | working |
| **Hermóðr** | every message to you, as Android notifications | working |
| **Dellingr** | runs the jobs you put on a clock | working |
| **Huginn** | reacts to what just happened: messages, meetings, alerts | working |
| **Veðrfölnir** | the phone itself: battery, storage, memory, heat | working |
| **Sága** | keeps the phone's alerts and the desktop's in step, through Mímir | working (alerts) |

Sindri, Heimdall, Munin and Ratatoskr are desktop jobs and have no phone part.

## Alerts and messages

A part raises a problem with Gjallar, and tells you something through
Hermóðr. Both keep their state in `ygg.db` (SQLite).

**Gjallar** (`gjallar/`), with the same rules as Yggdrasil:

- The same source and key updates the open alert. A resolved alert opens
  again. An acknowledged one opens again when its severity goes up.
- How loud: `info` is listed in the app only. `warning` gets a quiet
  notification when it opens. `critical` gets an urgent one, again at most
  every 15 minutes while it keeps being raised.
- Muted alerts are listed, never pushed. Snoozed ones aren't pushed until
  the snooze ends (at most 30 days).
- Acknowledging, snoozing or resolving takes the notification back.
- Resolved alerts are kept for 30 days.

**Hermóðr** (`hermodr/`):

- There's one notification channel per priority (low, normal, high,
  urgent), so you set sound and Do Not Disturb for each in Android's
  settings.
- Quiet hours (default 22:00–07:00) hold everything but urgent messages.
  WorkManager sends what was held when they end.
- `hold` also waits while you're in a meeting. Meetings come from the
  phone's own calendars (`READ_CALENDAR`, asked for from the Messages tab,
  and read on the phone only). A meeting is timed, shown as busy, and not
  declined. Meetings less than 5 minutes apart count as one. Without
  calendar access nothing is ever held for a meeting.
- `push = false` lists a message in the app only.
- When notifications are off, the message is kept with the reason, and the
  Messages tab says why.
- Messages are kept for 7 days.
- **Do Not Disturb:** Android silences Yggdrasil too. The Messages tab says
  whether the Urgent channel gets through, and *Let urgent through* opens
  Android's settings for that channel, where only you can switch on
  "Override Do Not Disturb". Critical alerts and urgent messages then still
  reach you, and the rest stay quiet.

Notification buttons: **Seen** on messages, **Acknowledge** and **Snooze 1 h**
on alerts. The Alerts and Messages tabs have test buttons that run the
whole path on the phone.

## Syncing with the desktop: `saga/`

Sága keeps the phone's alerts and the desktop's in step through
[Mímir](https://github.com/SebbeJohansson/mimir), a small event log on your
VPS, reached over Tailscale. The desktop runs the same part (`ygg saga`).
Either side can be off: what the other sent waits in Mímir until it starts.
What each event means is in Mímir's `docs/events.md`.

- **The phone's alerts** go to Mímir each time something about one changes
  (opened, seen, snoozed, muted, resolved), not each time it is raised again.
  On the desktop they show in Gjallar as source `phone`, and are never pushed
  there: the phone already told you.
- **The desktop's alerts** are listed in the Alerts tab under *Desktop*, and
  never pushed here. **Seen**, **Snooze** and **Mute** on one ask the desktop
  to do the same. The alert shows it at once; the desktop's answer settles it.
- **What the desktop asks of the phone's alerts**, Sága does here, and takes
  the notification back.
- Every event waits in an outbox in `ygg.db` until Mímir has it.

It syncs a couple of seconds after an alert changes, when the app comes to
the front, and every 15 minutes with Veðrfölnir's check while it is closed.

Set up in the Alerts tab, under *Mímir*: the URL (`https://<vps>.<tailnet>.ts.net`,
HTTPS only: Android refuses plain HTTP) and this phone's token, both printed
by Mímir's `deploy/install.sh`. Keep the name `phone` unless you gave the token
another one. The phone needs Tailscale on.

## The phone: `vedrfolnir/`

Veðrfölnir watches the phone as the desktop part watches the machine. One
problem is one alert with Gjallar (source `vedrfolnir`), and the alert
resolves by itself when its check passes again. It fixes nothing.

| Check | Warning | Critical |
| --- | --- | --- |
| `battery-low` | 15% or less and not charging | 5% |
| `storage` | 90% full | 95% |
| `heat` | Android's thermal status is severe, or the battery is 45 °C or more | Android's thermal status is critical |

It checks when the app starts, and every 15 minutes through WorkManager,
which is Android's shortest period. The Phone tab shows the readouts live,
every 5 s while it's open, and has a segment on the arc reactor. A reading
the phone doesn't give, such as a battery level, raises nothing.

## Memory and chats: `norn/`

Norn remembers you, under the desktop's rules (`Memory.kt`, ported from
`norn/memory.go` with its tests):

- **Learning:** after each answer, Norn picks lasting facts out of *your*
  message, never its own answer. A small model states things confidently
  that aren't true, and a fact taken from its answer would come back as
  something you said.
- **The names guard:** a fact naming someone your message didn't mention
  is dropped. A small model copies the worked example otherwise.
- **No duplicates:** a fact already known, by wording or by meaning, isn't
  stored twice.
- **Recall:** the facts that bear on a question go in front of it, in the
  chat and in Dellingr's and Huginn's questions to Norn. In the chat they
  sit in front of your message, not in the system prompt, so the model's
  open conversation survives.
- **Finding the right facts:** by meaning with EmbeddingGemma 2 (165 MB,
  on the CPU, downloaded from the Memory page), by newest without it.
- **Learning runs on the side:** in a conversation of its own, so it
  doesn't close the chat's.
- **The Memory page:** see, add and forget facts, and switch learning off.

Chats are kept on the phone. **Chats** lists them and **New** starts one.
A reopened chat gives the model exactly what it had before, memories
included.

| Memory | Chats | A reopened chat |
| --- | --- | --- |
| ![Memory](docs/screenshots/7-memory.png) | ![Chats](docs/screenshots/8-chats.png) | ![Chat](docs/screenshots/9-norn-chat.png) |

## Jobs: `dellingr/`

Dellingr runs the jobs you put on a clock (Parts → Jobs), under the desktop
part's rules:

- **When:** said in words, turned into cron once when you save, and shown
  back before you save. It understands "every weekday at 8", "every monday
  and friday at 9:30", "the 1st of each month at 10", "every 15 minutes", or
  cron itself (`Cron.kt`, ported from `cron.go` with its tests).
- **What:** *Tell me* sends your text. *Ask Norn* sends Norn's answer.
  Norn answers on the phone, and it knows the date and today's meetings
  when it may read the calendar. The desktop's *goal* jobs need Sindri,
  so there are none.
- **Delivery:** through Hermóðr with hold, so a job's message waits out
  quiet hours and meetings. It's a notification, or only listed in Messages.
- **Missed runs:** a run the phone slept through runs once on waking, never
  once per missed tick. A job still running is skipped, not run twice at
  once.
- **Failures:** 3 in a row raise `failing:<id>` (warning) with Gjallar. It
  resolves when the job works again. Runs are kept for 30 days.
- **Timing:** an alarm wakes the phone for the next run. It's exact once you
  allow exact alarms (Android asks from Android 14); otherwise Android may
  move it by some minutes. Veðrfölnir's 15-minute check also ticks
  Dellingr, so nothing waits longer than that. Alarms are set again after a
  restart.

## Reactions: `huginn/`

Huginn reacts to what just happened (Parts → Huginn), under the desktop
part's rules. A rule is a trigger (an event and its filters) and an
action: *Tell me* (a text with `{fields}` filled in) or *Ask Norn*. It's
delivered through Hermóðr, with hold when it should wait out a meeting.

| Event | From | Fields |
| --- | --- | --- |
| `notification.arrived` | other apps' notifications, the phone's inbox (`HuginnListener`, once you allow notification access) | `{from} {app} {summary} {text}` |
| `calendar.soon`, `calendar.ended` | the phone's calendars: starting within 30 minutes, or ended in the last 20 | `{subject} {start} {end} {where} {notes}` |
| `alert.opened`, `alert.escalated`, `alert.resolved` | Gjallar, muted alerts left out | `{title} {detail} {severity} {source}` |

The built-in rules can be switched off, but never changed or removed:

- **Message from someone who matters:** "People who matter" is the phone's
  stand-in for the desktop's manager-or-above, a list of names you keep.
  Told with high priority.
- **Urgent message:** a one-to-one message that says urgent, ASAP,
  emergency, akut or brådskande. Told with urgent priority.
- **Meeting in the next half hour:** Norn writes a brief from the invite.
  It waits out the meeting you're in.

As on the desktop:

- An event more than an hour old is skipped.
- When two rules would tell you the same event the same way, only the
  first one does.
- Reactions are listed for 7 days.

Notification text is read and matched on the phone. Only what a rule sends
is kept, and the app's own notifications are never read. The calendar is
looked at on start and with Veðrfölnir's 15-minute check, so a brief comes
15 to 30 minutes before the meeting.

## World tree and runestones: `history/`

Ported from the desktop's Tree and Runes pages (`tree.ts`, `futhark.ts`,
`runestones.ts`). Both live under Parts → Tree and Parts → Runes.

**The tree** grows from what happened:

- **Branch:** a fact Norn came to remember.
- **Scar:** an alert that healed.
- **Raven:** a time Huginn told you something.
- **Leaves:** follow the seasons of your weeks. A busy week is summer, a
  quiet one turns to autumn.
- **Time:** drag the slider back through it, or tap *Grow* to watch it
  grow from the first day. Tap a branch, scar or raven to see what it was.

**Runestones** are carved for moments worth keeping:

- the first memory, then 100 and 1000
- a busy day
- the first raven, then 100
- a long storm that passed
- a quiet week

Each stone says it in English, written in Elder Futhark and drawn stroke
by stroke. Tap a stone for the plain words and the day.

The history is an append-only `history` table, so a forgotten memory or
a pruned alert stays in the tree. On first start the app fills it from
the memories, healed alerts and reactions you already have.

| Tree | Runes |
| --- | --- |
| ![Tree](docs/screenshots/10-tree.png) | ![Runes](docs/screenshots/11-runes.png) |

## Home screen: `home/`

Ygg can be the phone's home screen, so pressing Home brings you to Ygg. It's
optional: nothing changes until you choose it.

- **Turning it on:** Parts → Settings → *Use Ygg as home screen*, or
  Android's Settings → Apps → Default apps → Home app. Switch back the same
  way.
- **Apps tab:** your pinned apps on top, then every app A-Z under its
  letter.
  - **Search:** type to find an app. It matches the start of the name or of
    a word, the initials ("gm" finds Google Maps) and accents. Enter opens
    the first match.
  - **Long-press:** pin, move, App info, Uninstall.
  - **Work apps:** apps in a work profile are listed too, marked *work*.
- **Home and Back:** Home always comes back to the apps and clears the
  search. Back goes to the apps and never leaves Ygg.
- **Light on the phone:**
  - **Boot:** as the home screen Ygg shows the daily boot only, never the
    quick check.
  - **Gemma:** the model still loads on your first question, never on Home.
- **One APK:** the home screen is a second entry point to the same app, a
  few kilobytes. Opening Ygg from its icon still works as before.

| Apps | Search |
| --- | --- |
| ![Apps](docs/screenshots/home-apps.png) | ![Search](docs/screenshots/home-search.png) |

## Updates: `update/`

Release builds keep themselves up to date from this repo's GitHub releases.

- **Checking:** every 6 hours on any network, and when the app starts if
  it's been longer.
- **Safety checks:** a newer APK is downloaded, checked against its
  `.sha256` file and against this install's signing key, then installed.
- **Installing automatically** (on by default): Android 12+ lets an app
  update itself without asking, once you've allowed Ygg to install apps.
  - **When:** only when the screen is off or the phone is idle, and never
    while a model downloads.
  - **Turned off:** Hermóðr tells you once that the update is ready, and
    you install it under Parts → Settings.
- **Settings:** Parts → Settings → Updates shows your version and the
  newest one. It has *Check now*, *Download*, *Install now* and the
  automatic switch.
- **After an update:** Messages says what you came from, and Dellingr's
  alarms are set again.
- **Dev builds** never update themselves.
- **Play Protect:** if it blocks the update, install it by hand once (see
  `docs/releasing.md`).

## Models: `llm/`

Parts never pick a model. They make an `LlmRequest` with:

- a **tier**: `SMALL` (names, classifying, one-liners) or `LARGE` (chat, briefs)
- a **privacy** level: `ON_DEVICE_ONLY` (the default) or `MAY_LEAVE_DEVICE`

`LlmRouter` goes through the providers in order and uses the first one that
is allowed, fits the tier, and is ready. When none can answer,
`NoProviderException` says why for each.

| Provider | Where | Status |
| --- | --- | --- |
| `LiteRtLmProvider` | on the phone, GPU with CPU fallback | working: Gemma 4 E2B (default), E4B |
| `ClaudeBridgeProvider` | Claude Code CLI on the phone, your subscription | app side done; bridge not written |

`LiteRtLmProvider` keeps the last conversation open, so a follow-up
message doesn't feed the whole chat to the model again.

## Run it

1. Build and install:

   ```bash
   ./gradlew :app:installDebug
   ```

   Or install a signed APK from [Releases](https://github.com/SebbeJohansson/yggdrasil-mobile/releases).
   To publish one, push a tag like `v0.2.0` ([docs/releasing.md](docs/releasing.md)).

2. Open the app and tap **Download model**. Gemma 4 E2B is 2.6 GB, downloaded
   from Hugging Face and checked against its SHA-256. A stopped download
   continues where it left off, but only while the app is open.

   Or push a file you already have:

   ```bash
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/io.github.sebbejohansson.yggdrasil/files/models/
   ```

## Develop

```bash
./gradlew :app:testDebugUnitTest   # router, Gjallar, Hermóðr, quiet hours, Sága
./gradlew :app:assembleDebug
```

Needs JDK 21 and the Android SDK with `platforms;android-37.2`.

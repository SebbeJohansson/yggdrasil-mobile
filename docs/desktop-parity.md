# Desktop parity

What the phone has of the desktop [Yggdrasil](https://github.com/SebbeJohansson/yggdrasil)
and its dashboard ([my-widget](https://github.com/SebbeJohansson/my-widget)),
what it doesn't, where the two behave differently, and what the desktop has
changed since a part was ported.

[AGENTS.md](../AGENTS.md) gives the *why* for the big differences. This file
is the full list.

**Last checked 2026-11-23** against desktop `5dae781`, my-widget `9066fcf`,
and this repo at `0585ca8`. To check again, compare each ported file's last
commit here with
`git -C <desktop> log --format='%h %ad %s' --date=short -- <source file>`,
and read the desktop's and dashboard's commits since the date above.

Paths: `K/` is `app/src/main/java/io/github/sebbejohansson/yggdrasil/`,
`bridge` is `app/src/main/assets/bridge/ygg_bridge.py`, `D/` is the desktop
repo, `W/` is my-widget's `app/`.

## To carry over

Desktop changes made after the phone's port, and changes made before the
port that it missed. Most worth doing first.

| # | Desktop change | What the phone lacks | Where |
| --- | --- | --- | --- |
| 1 | `2809e3b` Claude's own words on a failed turn, and the `/login` hint | `complete()` returns `claude: <result>`, with no hint and no expired sign-in check | `bridge` `complete()` |
| 2 | `a89e7b6` `/v1/claude-login` | `LinuxLogin` runs `claude auth login` without `--claudeai` or `BROWSER=true`, takes the first `https://` link, and counts you as signed in if `.credentials.json` exists. It never notices an expired sign-in | `K/linux/OwnLinux.kt:80,216`, `K/linux/Proot.kt:104` |
| 3 | `da020c3` a loop whose run fails runs again after a minute | No retry. A failed `loki.plan` shows as failing for an hour | `K/core/Loops.kt`, `K/YggApp.kt:298` |
| 4 | `f1497ac` Remote Control on every agent, `remoteUrl` | Local sessions start with no arguments, so none can be followed from claude.ai. Not yet checked whether interactive claude in herdr can switch it on by a flag | `bridge:735` |
| 5 | `81b5d7b` promises have their own on/off switch | Reading promises from chat is tied to the memory *Learn* switch, and Share isn't gated at all | `K/norn/NornChat.kt:75`, `K/norn/ShareActivity.kt:26` |
| 6 | `d95e3bc` promise matching knows a person's aliases | Aliases are used when names are swapped, not when promises are matched | `K/norn/Promises.kt` |
| 7 | `7da406d` working hours from `WORK_HOURS` | Fixed at 09–17, Monday to Friday, in the Today card and Bifröst | `K/today/Today.kt:30`, `K/ui/Aurora.kt` |
| 8 | `fa44928` Heimdall's first-night findings kept out of the tree and runestones | Heimdall came to the phone in `ee0cb6b`. No such filter was found; not yet checked whether the phone's tree can get the flood at all | `K/history/` |

Only if the phone gets agents of its own: `7633054` (auto permission mode
for Ask's agents).

Nothing to carry over in the files ported line by line from the desktop:
`Memory.kt`, `Pseudo.kt`, `Promises.kt`, `Today.kt` (from `gap.go`),
`Focus.kt`, `Cron.kt`, `Dellingr.kt`, `Huginn.kt`, `GjallarService.kt`
(`3f15a54` landed here as `86c14e4`), the bridge's session code (from
`conduit_claude.go`, `conduit_herdr.go`, `agent_sessions.go`), and `saga/`.

## Stale docs and strings in this repo

- **Tailscale.** The desktop's `5dae781` says Mímir is reached at its
  public HTTPS URL with a token per device, not over Tailscale. Still
  saying Tailscale: `README.md:168`, `:186` (`<vps>.<tailnet>.ts.net`),
  `:189` ("The phone needs Tailscale on"), and the placeholder in the app's
  Settings, `K/gjallar/AlertsScreen.kt:300`.
- **README, Look:**
  - Tapping the reactor opens Health, not Phone.
  - Inbox also has Promises.
  - System also has Health, Performance and Watchers.
  - Settings also has Claude bridge, Focus and Watching you.
- **README, Parts:** Sága says "working (alerts)", but the Code tab's
  sessions work too.
- **AGENTS.md, Sindri row:** the desktop's Sindri now also has auto
  permission mode, Remote Control on every agent, sign-in from the
  dashboard, and `loginExpired`.
- **AGENTS.md, Cloud starts row:** the desktop starts in a herdr pane when
  herdr runs, and under `script` otherwise.
- **AGENTS.md, Loki row:** "not ported yet" also covers the strike log,
  the `slow` trick, and Loki's settings.
- **[claude-bridge.md](claude-bridge.md):**
  - A blank line ends the contract table early, which leaves the
    `GET /v1/repos` and `/v1/complete` rows below the prose.
  - `POST /v1/cloud` says it shallow-clones. It makes a stub first, and a
    full clone only if claude refuses the stub.
  - Two routes are missing: `POST /v1/local/{name}/resume` and
    `POST /v1/github`.

## By part

Each part lists what the desktop has that the phone doesn't, what both have
but do differently, and what only the phone has.

### Norn

**Not on the phone:**
- the week's plan (weave)
- save points
- last time with this person (`recap*.go`)
- one list of people
- Rewind
- a goal's why
- keeping a break free (`POST /v1/calendar/breaks`)
- the inbox: Outlook, Teams, Slack and Gmail, with ranking and summaries
- reply drafts
- Graph and Google calendars, and the `calendar.*` events
- meeting briefs and prep per series
- meeting actions to Linear
- Obsidian
- dispatch
- MCP lookups
- "your sessions"
- the activity heatmap
- profile
- calendar suggestions

**Partly there:**
- **Promises.** The ledger, day parsing and reminders are here. Missing:
  - sent mail, Teams, Slack and meeting notes as sources
  - the follow-up draft (`fab0a0d`)
  - done by itself when a later message keeps the promise
  - Huginn's `dev` judgement
- **The task sized to your gap.** Here as the Today card's "one thing that
  fits". Its goal, agent, race, review and draft items are replaced by a
  critical alert, a promise, or a failing job. It doesn't step aside while a
  save point waits (`D/norn/gap.go:257`).
- **Cloud mode.** The phone has the Claude provider and "send names for N
  hours" instead, with no privacy levels.

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| How close a fact must be to recall it | 0.5 (`D/norn/memory.go:32`) | 0.4, tuned for EmbeddingGemma (`K/norn/Memory.kt:65`) |
| Promise words | `D/norn/promises.go:356` | adds "i promised", "i told", "promised", "jag lovade", "lovade", for chat |
| Reading promises | every 2 min | on chat and Share only |
| Reminding of promises | every 10 min, as a `promise.due` event that Huginn turns into a message | every 15 min, straight to Hermóðr |
| Breaks and focus blocks | Outlook category or flag | title starting with `break` or `focus` (`K/hermodr/Focus.kt`) |

**Phone only:**
- promises from chat, from Share, and added by hand
- Gemma on the phone
- the Today card and its day ring

### Gjallar

**Not on the phone:**
- `expiresAt`
- `traceId`, `count`, and an alert's history
- `/v1/alerts/healed` (the phone's `history` table does this job)
- heartbeats
- the event stream (an in-process flow instead)
- `/v1/alerts/mine`
- the dashboard link in a push

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| Warning | badge only, no push | a quiet notification when it opens |
| Reopening a resolved alert | keeps its snooze and when it was last pushed | clears both (`K/gjallar/GjallarService.kt:63`) |
| Resolved alerts | kept forever | deleted after 30 days |
| Push text | `source · detail` | `detail` then `from source` |

**Phone only:**
- the notification is taken back on ack, snooze or resolve
- the desktop's alerts are listed under *Desktop*

### Hermóðr

**Not on the phone:**
- ntfy, and `<CHANNEL>_MIN` per channel
- the `channel:<name>` alert
- `notBefore` ("later")
- focus kinds (general, dev, goal) and focusing on a goal
- separate *in a break* and *in a focus block* reasons
- 30 days of focus history (the phone shows 7)

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| Held messages | never pruned | deleted after 7 days like the rest (`K/core/YggDatabase.kt:337`) |
| `push=false` during a focus | held | listed at once |
| Meetings | each on its own | less than 5 min apart count as one (`K/norn/Calendar.kt:35`) |
| Calendar | Norn's, polled every 2 min | the phone's, read on each send |
| A break | held when flagged | held only if shown busy; a focus block holds even when shown free |
| Recap when a focus ends | made by the dashboard | made by the service, when 2 or more waited |

**Phone only:**
- a channel per priority
- the Do Not Disturb status, and *Let urgent through*
- notification buttons
- the Focus tile
- a focus fitted to the next meeting (`FocusPlan`, from the dashboard's
  `focusPlan.ts`)
- a `FAILED` state with its reason

### Dellingr

**Not on the phone:**
- goal jobs and `autoStart` (on purpose: there is no Sindri)
- a time zone per job
- editing a job
- jobs a part creates starting disabled (the phone creates them enabled,
  `K/dellingr/Dellingr.kt:118`)

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| Clock | ticks every 30 s | an exact alarm, plus the 15-min worker |
| Ask Yggdrasil | Norn conversation `dellingr`, with its history | a one-off `NornAsker` with the date, meetings and memory |
| A message that can't be delivered | counts as a failed run | doesn't |

**Phone only:** a Morning brief template, and a warning when exact alarms
are off.

### Huginn

**Not on the phone:**
- Ratatoskr's gossip
- learned habits, suggestions, and suggested mutes
- these built-in rules:
  - `meeting-moved`
  - `alert-impact`
  - `calendar-duplicate` and `calendar-stale`
  - `linear-todo`
  - savepoint
  - `week-plan`
  - `promise-due` (Norn reminds directly instead)
- these events: `inbox.arrived` (`notification.arrived` replaces it),
  `draft.ready`, `calendar.added`, `.moved`, `.removed`
- these filters: `relation`, `band`, `mentionsMe`, `calendar`,
  `startsWithinMinutes`
- Claude's brief per meeting series, and the open-items brief
- the `stream:<part>` alert
- editing rules
- rules a part creates starting disabled

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| Meeting brief priority | normal | high (`K/huginn/Huginn.kt:293`) |
| Who matters | Norn's relation: manager and above | the *People who matter* list |
| Urgent | Norn's ranking band | words in English and Swedish (`K/huginn/Sources.kt`) |
| `calendar.soon` | checked every 30 s, 30 min ahead | checked every 15 min, so the brief comes 15–30 min ahead |
| `calendar.ended` | any meeting not declined | timed and shown busy, in the last 20 min |
| `hold` on a new rule | on | off (`K/huginn/HuginnScreen.kt:268`) |
| Ask | Norn conversation `huginn` | `NornAsker`, with no history |

**Phone only:**
- `notification.arrived` from other apps
- the *People who matter* list
- each reaction grows a raven on the tree

### Veðrfölnir

**Not on the phone:**
- CPU, GPU, Windows and WSL readings
- consumers, services, containers and logs
- alerts for a failed unit or a crashed container

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| When to raise | down after 2 failed checks, up after 2 passes | raises on the first failure, resolves on the first pass (`K/vedrfolnir/VedrfolnirService.kt:42`); `core/Watch.kt` isn't used |
| How often | alerts every 1 min | every 15 min; the screen every 5 s |
| Storage key | `disk:<mount>` | `storage`, at the same 90 and 95 % |

**Phone only:** the `battery-low` and `heat` checks, and a memory readout.

### Sindri

The phone has no Sindri. It starts and answers your own Claude Code
sessions through the bridge (Sindri's Path A), and has no agents of its
own.

**Not on the phone:**
- Ask's agents, goals, review, races, conduits and auto-allow (local sessions do get their own worktree, as Sindri's agents do)
- agents ended by a restart counting as resumable (`5c16922`)
- `/v1/complete` with privacy levels
- your prompts, kept
- `/v1/sessions/recent` and the heatmap
- merged goals
- a goal's why
- the night shift and self-repair
- the permission marker from the hook (`D/sindri/conduit_claude.go:416`),
  so phone sessions show only the dialogs herdr spots
- teleport and forget for the bridge's own cloud sessions

**Different:**

| What | Desktop | Phone |
| --- | --- | --- |
| Cloud start | in a herdr pane, or under `script` when herdr is down; waits 90 s in your checkout | always `script`, in a stub or a full clone; waits 180 s (`bridge:47,456`) |
| Following up a cloud session | 60 s | 90 s; also takes `cse_` ids |
| Titles | read from the end of the transcript, `custom-title` or `ai-title` | read from the whole file, `type` not checked (`bridge:967`) |
| Last words | the last 4000 characters with a leading "…", then Sága clips to 600 with a trailing "…" | `[-4000:]` then `[:600]`, no "…" (`bridge:959,1020`) |
| Phases | from the transcript | the same, plus `ended` and `starting` |

**Phone only:**
- several Claude accounts
- cloud starts from a stub
- local sessions in the phone's own checkout, with `repo`, `diff`, `pull`,
  `push` and `keys`
- an ended session resumes when you send to it
- Ygg's own Linux

### Sága

**Not on the phone:**
- publishing the phone's own sessions (`sindri.sessions`)
- carrying out what other devices ask of them (`SAGA_DRIVE_FROM`, the
  30-minute limit)
- the HTTP routes

**Different:**
- The phone ignores `startedAt` on sessions and `lastSentAt` on cloud
  sessions (`K/saga/Sessions.kt:103`).
- A device counts as quiet after 12 min. The desktop sends at least every
  5 min.

**Phone only:** its own local sessions next to the other machines' in the
Code tab.

### Ratatoskr

**Not on the phone:**
- a check of each part's `/health` every 30 s
- the Loki-proxy "cut off" logic
- `late` when you answer a Sindri agent
- `RATATOSKR_YOU_PUSH`
- the status routes

**Different:**
- **How often:** a round every 15 min (`K/parts/Health.kt:110`).
- **Down:** only what a part can tell about itself; only the bridge has a
  probe.
- **`silent:ratatoskr`:** raised by Ratatoskr's own late round, not by
  Gjallar missing a heartbeat.
- **`late`:** means the phone is in use.

### Munin

**Not on the phone:**
- the `openai` and `ollama` targets, and the `models:` check
- sources (`MUNIN_SOURCES`, `tcgpriser`)
- `offline` (on purpose)
- separate local and internet loops (one round every 15 min instead)

**Different:** while a target is down, the phone still runs its other
checks; it skips only the incident check.

**Phone only:** Mímir, Hugging Face, and Anthropic (with the bridge) as
default targets.

### Heimdall

**Not on the phone:**
- the `listening`, `files` and `scopes` checks
- `/v1/report/run`
- `HEIMDALL_SINDRI_REPOS`
- `heimdall:ignore` (gitleaks runs with `--redact` instead)

**Different:**
- **Where it runs:** a GitHub Action on this repo, into an issue the app
  reads, plus checks on the phone.
- **When the summary is critical:** whenever any finding is critical. The
  desktop only does that for a pushed secret or a listener open beyond the
  machine.
- **Not in the desktop's default `HEIMDALL_REPOS`:** this repo.

### Loki

**Not on the phone:**
- passive strikes
- tales
- proxy and freeze
- the `slow`, `error`, `drop` and `hang` tricks
- `allowWatchers`, `perDay` and `allowFreeze`
- `ygg strike`
- the append-only strike log (`6ebb10c`; the phone keeps the last 200)

**Different:**
- Gjallar and Ratatoskr can't be tricked.
- During a strike, Loki asks Ratatoskr for a round every 30 s.
- The blind spots are the phone's own (`K/loki/Loki.kt:190`).

### Elsewhere on the desktop

Not on the phone, and not meant to be:
- `ygg` from a terminal
- the OpenAPI contracts in `api/` (the bridge's contract is the only one
  here)
- `profile/`
- `workspace/`
- `deploy/`, `scripts/` and `bin/`

## The dashboard

The dashboard ([my-widget](https://github.com/SebbeJohansson/my-widget))
against the phone's tabs.

### Pages

| Dashboard | Phone | Notes |
| --- | --- | --- |
| Menu, Ctrl+P switcher | partly | tab bar, no switcher |
| Status line | partly | clock, ⚙, reactor and focus ring; no alerts line, hum, CLOUD chip, part chips, *Ask about this page*, or search |
| Overview | partly | the Today card stands in for DayRing, Today, GapTask, FocusOffer and NeedsYou. Not on the phone: Readouts, Goals, Tasks, Inbox, CalendarSuggestions, Away, BreakNote, MeetingNow, MeetingRecap, AfterMeeting, SavePoint, WeekPlan, PeopleHint |
| Ask (`chat.vue`) | partly | one column: Chats, Memory, New. No routing, agents, goals or starter prompts |
| Agents | partly | other machines' and cloud sessions, nearly the same; no local my-agent agents. *Forget* on cloud sessions not found |
| Promises | yes | Inbox → Promises, plus adding by hand |
| Tree, Runes | yes | under System. Different stones and branches: below |
| Jobs, Huginn | yes | under System |
| Health | yes | System → Health, and the reactor |
| Memory | yes | from Ask |
| Machine | partly | System → Phone and Performance |
| Performance | yes | the phone's has more: processes, battery, storage, other apps. No p50/p95 per step kind or model cost |
| Loki, Heimdall | partly | one Watchers page |
| Settings | partly | below |
| Goals, Review, Projects, Races, Search, Meeting mode, Focus workbench, session reader | no | |
| Inbox (Outlook, Teams, Slack, Gmail), People, Week, Plan, Rewind, Year | no | the phone's Inbox is Gjallar and Hermóðr |
| Services, Radar, Gossip, Mead hall, Holo-table, Dispatcher | no | |
| Fimbulwinter, hum, page transitions, custom scrollbars, Ask-about popup | no | |
| Desktop widgets | no | desktop only |

### Ported but different

- **Arc reactor:**
  - Its core breathes (faster while the model writes) instead of showing
    the machine's load.
  - It doesn't flicker during a Loki trick.
  - Tapping it opens Health.
- **Boot:**
  - Same timings.
  - Waits a fixed 700 ms for data instead of the first poll.
  - Its lines and voice are about the phone (battery, heat, network,
    calendar, notifications).
  - Not skipped during a focus.
- **Bifröst:**
  - Same timings and colours.
  - Missing: the rainbow bridge when a part comes back, breathing with the
    hum, storm weight for parts down or recovered and for Loki strikes, and
    an on/off setting.
  - Kept away while you type or the model writes, rather than during a
    focus.
- **Tree:**
  - Branches are Norn's facts, not merged goals.
  - Ravens are Huginn's reactions, not Loki's tricks.
  - Missing: the year/month picker, the creatures (`b680bbd`), and gliding
    between moments (`ab8b2d2`).
- **Runes:** the phone's own stones (first/100th/1000th branch, busy day,
  first and 100 ravens, quiet week), not goals or Loki catches.
- **Jobs:** missing goal jobs, proposed jobs, editing, *Lately*, and
  channels with a test.
- **Huginn:** missing proposed rules and *Huginn noticed*.
- **Watchers:** missing grouped findings, *Make it a goal*, tales, passive
  Loki, settings, and the `/loki` workshop.
- **Focus:** one kind. It is fitted to the calendar, with *25 min more*, a
  recap as a message, and a tile.
- **Day ring:** meetings / promises due / what needs you, instead of
  meetings / goals in play / what waits on you.

### Settings

**In both:**
- names to Claude
- model (partly)
- boot sequence
- watching your rhythm
- notifications

**Dashboard only:**
- where you are
- coding agent
- menu placement
- shortcuts
- hum
- Bifröst on/off
- Fimbulwinter
- page transitions
- on its own and the night shift
- self-repair
- why
- promises on/off
- last time with
- gossip
- sources and widgets

**Phone only:**
- the Mímir connection
- model and embedder downloads
- memory learning and recall
- quiet hours
- Do Not Disturb
- home screen
- updates
- the Claude bridge
- focus history
- developer tests

### Phone only

- Apps and the home screen
- Inbox → Messages, all of Hermóðr's history
- System → Phone
- the Claude bridge and Ygg's own Linux
- self-update
- the Focus tile and notification buttons
- Share to Ygg
- the Today card

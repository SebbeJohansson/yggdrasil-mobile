# AGENTS.md

Notes for coding agents (and people) working on Yggdrasil Mobile: how it is
put together, how to build and ship it, what bit us, and where and why it
differs from the desktop [Yggdrasil](https://github.com/SebbeJohansson/yggdrasil).
The README is the user's guide; this is the builder's.

## What it is

One Android app (Kotlin, Jetpack Compose) that rebuilds the desktop's parts
to run on the phone alone. The desktop runs one process per part talking
HTTP; here every part is a **package in one app**, calling each other
directly. Parts keep their desktop names and jobs.

| Package | Job |
| --- | --- |
| `norn/` | chat, memory of facts, saved chats, promises |
| `gjallar/` | alerts: dedupe, severity, snooze |
| `hermodr/` | notifications, quiet hours, held messages, Focus |
| `dellingr/` | jobs on a clock (cron from words) |
| `huginn/` | rules reacting to notifications, meetings, alerts |
| `vedrfolnir/` | the phone's health: battery, storage, heat |
| `saga/` | sync with the desktop through Mímir; the Code tab |
| `ratatoskr/` | "Watching you": the rhythm notes |
| `history/`, `today/`, `home/`, `boot/` | world tree, Today, launcher mode, boot sequence |
| `update/` | self-update from GitHub releases |
| `parts/` | Health and Performance screens |
| `core/` | database, Perf, process/disk/phone readers, loops |
| `llm/`, `model/` | Gemma via LiteRT-LM, the Claude provider and bridge client |
| `linux/` | Ygg's own Linux: Ubuntu under proot, its service, shell, sign-in |
| `ui/` | the HUD look: components, theme, Markdown |

`AppContainer` in `YggApp.kt` wires everything; screens get view models from
it (`MainActivity.kt`).

## Build, test, ship

- **Unit tests:** `./gradlew :app:testDebugUnitTest`. **Bridge tests:**
  `python3 bridge/test_ygg_bridge.py` (stdlib only, a fake `claude`, a
  file:// "GitHub"). `BridgeContractTest` runs the real bridge against the
  Kotlin client.
- **Needs** JDK 21 and the Android SDK (`platforms;android-37.2`). Cloud
  agent sessions usually have **no Android SDK**: you can't compile there.
  CI (`.github/workflows/build.yml`) compiles, tests and lints on every push
  to `main` and on pull requests; treat a green `main` build as the compile
  check, and read its log when it fails.
- **Release:** Actions → release → Run workflow on `main` with a version
  (`0.23.0`); the workflow makes the tag. Pushing a tag from a cloud session
  fails at the proxy even when git says otherwise; run the workflow instead,
  and only after `main`'s build is green. Versions: `MAJOR.MINOR.PATCH`, each
  below 100. Details: [docs/releasing.md](docs/releasing.md).
- **Style.** Comments and commit messages say *why*, in plain sentences,
  as the existing ones do: a subject that says what changed for the user,
  a body with the reason and what was tried. Keep pure logic (parsers,
  ranking, state) apart from Android calls so it gets a unit test.

## The Claude bridge

```
Ygg ──HTTP 127.0.0.1:8765 (key)──► ygg_bridge.py ──► claude (cloud starts, -p, auth)
                                         │
                                         └──► herdr server per account ──► claude in panes (local sessions)
```

- `app/src/main/assets/bridge/ygg_bridge.py`: one stdlib Python file, the
  contract in [docs/claude-bridge.md](docs/claude-bridge.md). Every call but
  `/health` needs the bridge's key. The Kotlin side is `llm/claude/Bridge.kt`
  (client), `BridgeUi.kt` (settings, cloud popup), `LocalUi.kt` (local
  sessions).
- **Where it runs:** by default in **Ygg's own Linux** (`linux/OwnLinux.kt`):
  Ubuntu 24.04's base, unpacked into app storage, run under proot shipped as
  a native library (`linux/Proot.kt`), kept up by `LinuxBridgeService`.
  `assets/linux/setup.sh` installs packages, Claude Code and herdr (both
  pinned and checksummed). UserLAnd and Termux are alternatives.
- **Accounts:** each Claude account is its own `CLAUDE_CONFIG_DIR`
  (`~/.ygg-bridge/accounts/<id>`); `default` is claude's own `~/.claude`.
  Every claude and herdr run gets its account's environment.
- **Cloud sessions:** `claude --cloud` takes the repo and branch from the
  folder it runs in, so the bridge makes one: a stub first (origin, the
  branch tip's trees, `--depth 1 --filter=blob:none`, a sparse checkout of
  nothing), a full clone if claude refuses the stub. Results are read in
  the Claude app (there's no supported API to read them back).
- **Local sessions:** Claude Code interactive in herdr, in the phone's own
  checkout (`~/.ygg-bridge/work/<owner>/<repo>`, cloned once, never reset).
  Ported from the desktop's own-session path (below).

## Proot: what bit us

Each of these cost a release. Keep them when changing the bridge.

| Trap | Fix |
| --- | --- |
| `--link2symlink` (dpkg needs it) turns hard links into symlinks; `claude --cloud` refuses a tree whose `.git` holds links | git runs with `core.createObject=rename` (bridge's `git()`, and `GIT_CONFIG_*` for claude and herdr); `plain_git_files()` rewrites any link left in `.git` |
| A unix socket path must fit 108 bytes, and proot's counts the host path (`/data/user/0/<pkg>/files/linux/ubuntu/...`) | each account's herdr listens on `/tmp/yh-<hash>.sock` via `HERDR_SOCKET_PATH` |
| Interactive claude stops at the theme picker and "trust this folder?" with nobody to answer | `prepare_claude()` writes `hasCompletedOnboarding` and the folder's trust into the account's `.claude.json` first |
| Claude's installer and auto-updater copy the binary over itself, which fails | `setup.sh` places the binary by hand; `DISABLE_AUTOUPDATER=1` |
| An app update brings a new bridge, but the guest kept the old copy | `LinuxBridgeService` calls `OwnLinux.refreshBridge()` before each start |
| `--kill-on-exit`: when the bridge restarts, herdr and every pane go with it | sessions keep their claude session id; a message to an ended one resumes it (`claude --resume`) |
| Android's per-app memory and CPU leave out child processes (all of Linux) | `core/Procs.kt` reads `/proc` for the app's uid |
| Tests importing the bridge from `assets/` leave `__pycache__` there, which would go into the APK | the bridge test sets `sys.dont_write_bytecode` |

## Desktop and phone: what differs and why

| Area | Desktop | Phone | Why |
| --- | --- | --- | --- |
| Parts | one process each, HTTP between them | packages in one app | one APK, no server; Android kills stray processes |
| Sindri (agents) | your sessions *and* its own "ask" agents in herdr, auto-allow, races | your sessions only (Sindri's Path A) | the phone starts sessions for you, not agents of its own |
| Session state | transcript (`ReadTranscriptState`), a herdr dialog overrides as blocked | the same algorithm, ported (`transcript_state`, `dialog_of`) | so both show sessions the same way |
| Finding a session's transcript | Claude's `cc-socks` + `sessions/<pid>.json` registry | herdr's `pane.process_info` pids → `sessions/<pid>.json`, the id kept in the session record | no socket dir under proot; the record survives the pane |
| Prompting | once; refused while a dialog shows | the same; an ended session is resumed first | bridge restarts end panes on the phone |
| Choosing in a dialog | only while the same question, key and label show | the same | a digit means something else in the next dialog |
| Resume | `claude --resume <id>` in a new herdr workspace | the same, under a new herdr name | |
| herdr | your own server, default socket | one headless server per account, short socket | accounts; the socket path limit |
| Claude accounts | one | several, by `CLAUDE_CONFIG_DIR` | personal and work on one phone |
| Cloud starts | in your real checkout | in a stub, or a full clone | the phone has no checkout |
| Sága's session list | publishes this machine's sessions to Mímir | reads other devices' and sends them requests; the phone's own sessions are **not published** | not ported yet |
| Model | the desktop's own | Gemma 4 on the phone (LiteRT-LM); Claude through the bridge if set up | no server to call |
| Performance | `perf.vue` | Perf steps, plus `/proc` processes, battery, storage, and other apps via Usage access | proot's processes are invisible to Android's per-app numbers |

For parity work, the desktop's sources: `sindri/conduit_claude.go`
(transcripts, state, titles, resume args), `sindri/conduit_herdr.go` (herdr
client, dialogs), `sindri/agent_sessions.go` (session routes),
`saga/sessions.go` (what the phone receives and how requests are carried
out), `sindri/herdr_engine.go` (Sindri's own agents, not ported).

## Don't

- Write a GitHub token into a repo's config: the bridge passes it as a
  header to its own git, and the work checkout's credential helper reads it
  from `~/.ygg-bridge/github` when git asks.
- Add a dependency to the bridge: it is one stdlib Python file the app
  copies into whatever Linux runs it.
- Send what Usage access shows (other apps' use) anywhere: it stays on the
  phone.
- Put model ids in commits, comments or docs.

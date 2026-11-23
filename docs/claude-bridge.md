# Claude bridge

Ygg's Claude bridge runs the real **Claude Code** CLI on the phone, with
your own Claude subscription, so the app can:

- **start Claude Code cloud sessions** for a repo, with no computer on;
- **send follow-ups** to any of your cloud sessions, including ones the
  desktop started;
- **answer for the Claude provider**, when you allow Claude to answer
  (behind `NameGuard`, see Privacy).

The app never calls Anthropic directly and never holds a Claude token.
Anthropic doesn't allow third-party apps to use claude.ai login or
subscription limits, so the app only talks to the bridge on `127.0.0.1`,
and the bridge runs `claude`.

```
Ygg ──HTTP 127.0.0.1:8765 (key)──► ygg_bridge.py ──► claude --cloud / claude -p
                                   (Ygg's own Linux: Ubuntu under proot)
```

Claude Code supports Ubuntu but not Android itself, so the bridge and
`claude` run in Linux. Three places can host it; Ygg's own Linux is the default.

| Place | What it is | Always on |
| --- | --- | --- |
| **Ygg's own Linux** (default) | Ubuntu 24.04's official base, under proot shipped inside Ygg | Yes: Ygg's own foreground service, starts with the phone |
| UserLAnd | UserLAnd's Ubuntu (Play Store, free), also proot | While its session runs |
| Termux | Termux with Ubuntu (proot-distro) | With Termux:Boot and its wake lock |

All three run the same `ygg_bridge.py` and set up with the same
`app/src/main/assets/linux/setup.sh`. proot shares the phone's network, so
Ygg reaches the bridge on `127.0.0.1` in each.

## Ygg's own Linux (default)

Settings → Claude bridge → *Ygg's own Linux*:

1. **Set up:**
   - Ygg downloads Ubuntu 24.04's base for arm64 (about 30 MB, checked
     against Ubuntu's SHA-256) and unpacks it into its own storage.
   - It then runs `setup.sh` inside it: Python, git, util-linux, and Claude
     Code's official binary from Anthropic's download host, checked against
     its manifest.
   - About 1 GB once done. *Update* runs it again, and *Remove* deletes it
     all.
2. **Sign in to Claude:**
   - Ygg runs `claude auth login` and shows its link. Open it, sign in, and
     paste the code back into Ygg.
   - The bridge's key is in Ygg's own storage, so Ygg takes it by itself.
3. **Always on:**
   - The bridge runs in a foreground service with a quiet notification
     (with *Stop*). It starts again if it stops, and starts with the phone.
   - Allow Ygg in the background (battery: Unrestricted) when asked.
   - If Android still stops it: Developer options → *Disable child process
     restrictions*.

**How it runs:**
- **proot:** Termux's builds of `proot`, its loader, `talloc` and
  `libandroid-shmem` ship as Ygg's native libraries, because Android runs no
  program written into app storage.
- **Updating proot:** `linux/prepare-proot.py` fetches and checks them;
  licences are in `docs/licenses/proot-NOTICE.md`.
- **Claude Code:**
  - It isn't installed with `claude install`, because its self-copy fails
    under proot.
  - Its auto-updater is off (`DISABLE_AUTOUPDATER=1`); *Update* brings the
    newest instead.
- **Tested:** `LinuxIntegrationTest` (run with `YGG_LINUX_IT=1`) does it all
  for real on a desktop Ubuntu with its own proot: unpack, `setup.sh`, Claude
  Code running, and the bridge answering on 127.0.0.1. What it can't cover is
  Android's proot build on the phone.

## UserLAnd or Termux

Settings → Claude bridge → *UserLAnd* or *Termux* → **Copy install
command**:

1. Paste the command there and run it. It carries `ygg_bridge.py`,
   `setup.sh` and the installer, because the repo is private.
2. Run `ygg-bridge login` to sign Claude Code in.
3. Paste `ygg-bridge key` into Ygg.
4. **Private repos:** `ygg-bridge github <token>`.
5. **Day to day:** `ygg-bridge start | stop | status | log`.

## Contract

`GET /health` (no key) returns `200 {ok, bridge, claude, github}` when
`claude` is installed, and `503` when it isn't. The app counts no answer as
"not running".

Everything else needs `Authorization: Bearer <key>`; without it the answer
is `401`. Errors are `{ "error": "…" }` with a non-2xx code.

| Call | Does | Answers |
| --- | --- | --- |
| `POST /v1/cloud {repo, task, branch?, account?}` | shallow-clones `owner/name` into `~/.ygg-bridge/repos` (or updates it), runs `claude --cloud "<task>"` there under `script` (it wants a terminal), and stops it once it prints the session link | `{id, url, repo, task, at}` |
| `GET /v1/cloud` | the sessions it started, newest first | `{sessions: [...]}` |
| `POST /v1/cloud/{id}/message {text, account?}` | `claude -p "<text>" --cloud <id> --output-format json`: queues it in any of your cloud sessions, as the account that started it (or the one given) | `{ok, id, url}` |
| `GET /v1/accounts` | the Claude accounts and who each is signed in as | `{accounts: [{id, name, signedIn, email, configDir}]}` |
| `POST /v1/accounts {name}` | adds an account; sign it in with `ygg_bridge.py login <id>` (or from the app, in Ygg's own Linux) | `{id, name, ...}` |
| `POST /v1/accounts/{id} {name}` / `POST /v1/accounts/{id}/remove` | renames / forgets an account and its sign-in | |

| `GET /v1/local` | the local sessions: Claude Code on the phone, in herdr | `{sessions: [{name, account, repo, task, at, status, dialog}]}` |
| `POST /v1/local {repo, task?, account?, branch?}` | clones the repo once into `~/.ygg-bridge/work`, later fetches and fast-forwards it (see below), starts `claude` in a herdr workspace there as the account, and types the task | a session, with `refreshed`: how the checkout was brought up to date |
| `GET /v1/local/{name}` | the session with its chat (from Claude's transcript) and, while there's none, its screen | a session with `messages`, `screen` |
| `GET /v1/local/{name}/repo` / `GET …/diff?path=` / `POST …/git {action: pull\|push}` | the checkout's branch, ahead/behind, changed files (+/−) and last commits; one file's diff; a fast-forward pull or a push of its branch, with the bridge's GitHub token | |
| `POST /v1/local/{name}/prompt {text}` / `choose {question, key, label}` / `keys {keys}` / `stop` | types a message; picks a dialog's choice while that dialog still shows; presses esc etc.; closes the workspace (the checkout stays) | `{ok}` |

Local sessions run Claude Code interactively in herdr (https://herdr.dev), as
the desktop's Sindri does: one headless `herdr server` per account (its socket at a short
`/tmp/yh-<hash>.sock`: under proot a long path overflows a socket's 108 bytes), started with the account's `CLAUDE_CONFIG_DIR`. herdr says
whether Claude works, waits or shows a dialog, and types for you; what Claude
said is read from its transcript (`sessions/<pid>.json` gives the session id).
Setup installs herdr, pinned and checked against its sums.

**Up to date before each new session:** a new local session starts on
what's on GitHub now. The bridge fetches, then fast-forwards the checkout's
branch to its upstream, but only when nothing of yours is in the way:

| Checkout | What happens |
| --- | --- |
| behind, clean, nothing unpushed | pulled (`git merge --ff-only`) |
| up to date | nothing |
| uncommitted changes | left as it is |
| commits of yours not pushed (diverged) | left as it is |
| a branch with no upstream | left as it is |

When it's left as it is, the reason and the number of commits it skipped
come back in `refreshed`, and Ygg shows them under the session in amber;
*Pull* is there when you're ready. A resumed session is never touched.

Accounts: each Claude account has its own claude config folder
(`CLAUDE_CONFIG_DIR`, in `~/.ygg-bridge/accounts/<id>`), so a personal and a
work account each keep their own sign-in. `default` is claude's own
(`~/.claude`). Results stay in Claude: Ygg opens a session there.
| `GET /v1/repos` | its checkouts | `{repos: [{repo, branch}]}` |
| `POST /v1/complete {system, maxOutputTokens, messages}` | the messages as one prompt, `claude -p --output-format json` with no tools, in an empty folder | `{text}` |

`claude --cloud` clones the GitHub remote at the checkout's branch, so the
bridge needs only a shallow checkout, and the Claude GitHub App must be on
the repo. Anthropic has no API to list cloud sessions, so the bridge lists
the ones it started. The desktop's appear through Mímir.

`bridge/test_ygg_bridge.py` tests all of this over HTTP with a fake
`claude` and a local git repo, and runs in CI. `BridgeContractTest` runs the
app's client against the real bridge.

## Privacy

What you send to a cloud session goes to Anthropic, as in the Claude app.
For the Claude *provider* (Ygg's own questions), the router only sends
requests marked `Privacy.MAY_LEAVE_DEVICE`, and the bridge sits behind
`NameGuard`. It works like Yggdrasil's Norn, ported from `internal/pseudo`
with its tests:

- **Swapping:** people's names and email addresses become placeholders
  (Person 1, Person 2…) before anything goes, and the answer gets the names
  back.
- **Finding names:** from People who matter, the names the local model
  (Gemma, on the phone) finds in pieces of up to 4,000 characters, and the
  shape of an email address.
- **Legend:** what a placeholder is (e.g. "someone who matters to the user")
  is told to Claude beside it.
- **If unsure, nothing is sent:** when the local model can't look for names,
  or a known name is still there after the swap.
- **Turning it off for a while:** *Send names 2 h*, next to the Claude
  switch, sends names as they are for up to two hours. *Swap again* ends it.

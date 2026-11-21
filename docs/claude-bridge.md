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
                                   (Termux → Ubuntu via proot-distro)
```

Claude Code supports Ubuntu but not Android itself, so the bridge and
`claude` live in an Ubuntu inside Termux. proot shares the phone's
network, so the app reaches it on `127.0.0.1`.

The bridge is one Python file with no dependencies:
`app/src/main/assets/bridge/ygg_bridge.py`. Its installer is next to it.
Both ship inside the app, because the repo is private.

## Set up (once)

1. **Termux:** install it from F-Droid. Optionally install Termux:Boot and
   open it once, so the bridge starts with the phone.
2. **Install:** in Ygg, go to Settings → Claude bridge → **Copy install
   command**. Paste it into Termux and run it. It sets up Ubuntu (a few
   hundred MB), Python, git and Claude Code (the official
   `claude.ai/install.sh`), then starts the bridge.
3. **Sign in:** run `ygg-bridge login`. That's `claude auth login` inside
   Ubuntu: open the link, sign in, paste the code back. Cloud sessions need
   this sign-in, not a `setup-token`.
4. **Key:** run `ygg-bridge key` and paste the key into Ygg (Settings →
   Claude bridge). Every call but `/health` needs it, so other apps on the
   phone can't use your account through the bridge.
5. **Private repos:** run `ygg-bridge github <token>` with a GitHub token
   that can read them. It's only used to clone, sent as a header to
   github.com, and never written into a checkout.

After that: `ygg-bridge start | stop | status | log`. While it runs it holds
Termux's wake lock, so Android doesn't pause it.

## Contract

`GET /health` (no key) returns `200 {ok, bridge, claude, github}` when
`claude` is installed, and `503` when it isn't. The app counts no answer as
"not running".

Everything else needs `Authorization: Bearer <key>`; without it the answer
is `401`. Errors are `{ "error": "…" }` with a non-2xx code.

| Call | Does | Answers |
| --- | --- | --- |
| `POST /v1/cloud {repo, task, branch?}` | shallow-clones `owner/name` into `~/.ygg-bridge/repos` (or updates it), runs `claude --cloud "<task>"` there under `script` (it wants a terminal), and stops it once it prints the session link | `{id, url, repo, task, at}` |
| `GET /v1/cloud` | the sessions it started, newest first | `{sessions: [...]}` |
| `POST /v1/cloud/{id}/message {text}` | `claude -p "<text>" --cloud <id> --output-format json`: queues it in any of your cloud sessions | `{ok, id, url}` |
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

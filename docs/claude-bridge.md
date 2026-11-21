# Claude bridge

For phones that can't run a local model, or for jobs too big for one, the
app can use Claude through **Claude Code itself**, running on the phone with
your own subscription.

The app never calls Anthropic directly and never holds a Claude token.
Anthropic doesn't allow third-party apps to use claude.ai login or
subscription limits, so the app only talks to a small bridge on
`127.0.0.1`, and the bridge runs the real `claude` CLI.

```
app ──HTTP 127.0.0.1:8765──► bridge ──► claude -p --output-format json
                              (Linux Terminal VM, or Termux + Debian)
```

Status: the app side (`ClaudeBridgeProvider`) is in place. The bridge itself
isn't written yet.

## Set up (once)

In Android's Linux Terminal (Settings → System → Developer options → Linux
development environment):

```bash
curl -fsSL https://claude.ai/install.sh | bash
claude setup-token          # sign in in the browser, copy the token
echo 'export CLAUDE_CODE_OAUTH_TOKEN=<token>' >> ~/.bashrc
```

Then forward port 8765 from the terminal's port-forwarding settings, so the
app can reach the bridge on `127.0.0.1:8765`.

## Contract

`GET /health` → `200` when the bridge is up and `claude` is signed in.

`POST /v1/complete`

```json
{
  "system": "optional system prompt, or null",
  "maxOutputTokens": null,
  "messages": [
    { "role": "user", "text": "…" },
    { "role": "assistant", "text": "…" },
    { "role": "user", "text": "…" }
  ]
}
```

→ `200 { "text": "the answer" }`, or a non-2xx code with an error text.

The bridge turns the messages into one prompt, runs
`claude -p --output-format json` with `--append-system-prompt` for `system`
and no tools, and returns the `result` field.

## Privacy

The text goes to Anthropic, so the router only sends requests marked
`Privacy.MAY_LEAVE_DEVICE` to the bridge, and the bridge sits behind
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
  switch, sends names as they are for up to two hours, like the desktop's
  `PUT /v1/settings/send-names`. *Swap again* ends it.

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

The text goes to Anthropic. The router only sends requests marked
`Privacy.MAY_LEAVE_DEVICE` to the bridge. A part that has people's names in
its text swaps them for placeholders first, as Yggdrasil's Norn does. If
the swap isn't sure, nothing is sent.

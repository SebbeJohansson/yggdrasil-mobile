# Claude usage ledger (planned)

**Not built.** This describes a feature to build later: a page in the app
showing how much Claude the desktop has used. No app code exists for it yet.

The desktop's Sindri keeps a ledger: it records every agent turn and every
`POST /v1/complete` call (tokens in, tokens out, cost). The phone would read
the totals from Sindri and show them. The phone keeps no ledger of its own:
its Claude calls go through the bridge and are not counted by Sindri
([desktop-parity.md](desktop-parity.md), Sindri).

## What it would show

- **Window share:** a bar for the rolling window (5 hours by default): tokens
  used against the budget, as a percent. No budget set means no bar, only the
  token count.
- **Totals:** calls, tokens in, tokens out and cost in USD, for the period.
- **By day:** one row per day, newest first.
- **By goal:** one row per goal, and one for work with no goal.
- **By caller:** `agent` (Sindri's agents), `norn` (chat), `norn:<feature>`
  (a Norn feature, once Norn sends one).
- **The `claude-usage` warning:** Gjallar raises it (severity warning) when
  the window share reaches 0.8 and resolves it below that. The phone already
  shows desktop alerts the way Gjallar's own are shown; the ledger page would
  also show the share itself.

## Where the data comes from

Sindri on the desktop, with the owner token as `Authorization: Bearer`, over
the same route the phone already uses to reach Sindri.

    GET /v1/usage?since=<2026-11-01 | RFC3339 | epoch ms>

`since` is optional; the default is the last 7 days. `503` means the
ledger is off on the desktop. A missing `SINDRI_USAGE_BUDGET` is not an error:
it only sets `budget` and `share` to `0`.

Response:

```json
{
  "since": 1700000000000,
  "total":    {"key": "total", "calls": 12, "tokensIn": 9000, "tokensOut": 1200, "costUsd": 1.25},
  "byDay":    [{"key": "2026-11-23", "calls": 5, "tokensIn": 4000, "tokensOut": 500, "costUsd": 0.5}],
  "byGoal":   [{"key": "42", "calls": 3, "tokensIn": 2000, "tokensOut": 300, "costUsd": 0.3}],
  "byCaller": [{"key": "agent", "calls": 7, "tokensIn": 6000, "tokensOut": 800, "costUsd": 0.9}],
  "window":   {"hours": 5, "tokens": 650, "budget": 1000, "share": 0.65}
}
```

- Every list item and `total` is a `UsageTotal`: `key`, `calls`, `tokensIn`,
  `tokensOut`, `costUsd`.
- `since` is epoch milliseconds, whatever form the request used.
- `byDay` keys are dates, newest first. `byGoal` keys are the goal id as
  text; `""` means no goal. `byCaller` keys are `agent`, `norn` or
  `norn:<feature>`.
- `window.share` is `tokens / budget`, `0` when `budget` is `0` (unset).

Desktop side: `api/sindri.yaml` (`/v1/usage`, `UsageTotal`) and
`sindri/usage.go`. The desktop also has `ygg usage [--since D] [--json]`.

## Not built on the desktop either

- A Dellingr job that pauses low-priority agents while `claude-usage` is open.
- Norn sending `feature` on `/v1/complete` calls, so `norn:<feature>` rows
  don't exist yet.

## Building it

1. Add `GET /v1/usage` to the Sindri client in `saga/` (parse into a data
   class; keep the parsing apart from Android calls so it gets a unit test).
2. Add a usage page next to Health and Performance (`parts/`), using the
   HUD components in `ui/`.
3. Update README.md (the page) and docs/desktop-parity.md (Sindri), and add
   a screenshot under `docs/screenshots`.

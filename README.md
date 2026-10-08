# slack-notifier

Publish a notification to a named Slack channel from the command line.

```bash
slack-notifier login
slack-notifier send --channel "#alerts" --message "Deploy finished ✅"
```

No Slack app, no admin approval, no tokens to copy by hand — it signs in with
the Slack session in your own browser.

## Why this exists

[`shaharia-lab/slackcli`](https://github.com/shaharia-lab/slackcli) does all of
this and much more. This is the one slice we need — post a message — rebuilt so
there is no untrusted third-party dependency in the chain. The approach is
borrowed; the code is ours.

**It has no runtime dependencies at all.** Bun's built-in `fetch`, `WebSocket`
and `spawn` cover everything, so `bun install` installs only TypeScript types.

## Install

Requires [Bun](https://bun.sh) 1.4+.

```bash
bun install
bun run build          # -> ./dist/slack-notifier
```

The result is a single self-contained binary. Put it on your `PATH`:

```bash
mkdir -p ~/.local/bin
ln -sf "$PWD/dist/slack-notifier" ~/.local/bin/slack-notifier
```

A symlink means a later `bun run build` updates the installed command too. Copy
it instead if you would rather not depend on the checkout staying put.

`dist/` is not committed, so a fresh clone needs `bun install && bun run build`.

## Usage

```bash
slack-notifier login [--headless] [--workspace <name>] [--timeout <seconds>]
slack-notifier send --channel <name> [--message <text>] [--json]
slack-notifier whoami [--json]
slack-notifier logout [--keep-browser-session]
```

### Sign in

```bash
slack-notifier login
```

A browser window opens on Slack. Sign in as you normally would and the tokens
are captured. If you are in several workspaces, the first is used — pass
`--workspace "Acme Corp"` to pick another.

The profile persists, so **only the first run needs interaction**. After that,
`slack-notifier login --headless` refreshes the tokens with no window and no
prompt — useful on a schedule, since browser tokens expire with the web session.

### Send

```bash
slack-notifier send -c "#alerts" -m "Deploy finished"
slack-notifier send -c alerts -m "Build 42 failed"
slack-notifier send -c C0123456 -m "By channel ID"
slack-notifier send -c "https://acme.slack.com/archives/C0123456" -m "By link"

# Message from stdin
./run-build.sh 2>&1 | tail -5 | slack-notifier send -c "#alerts"

# Machine-readable
slack-notifier send -c "#alerts" -m "hi" --json
# {"ok":true,"channel":"C0123456","ts":"1700000000.000100","permalink":"https://…"}
```

`--channel` takes a name, a `#name`, a channel ID, or an archive link. Names are
resolved once and cached, so repeat notifications cost a single API call.

Exit code is `0` on success and `1` on any failure, so it drops straight into a
script.

## How the login works

Slack's web client authenticates with a pair of credentials: an `xoxc-…` API
token sent in request bodies, and an `xoxd-…` value in the `d` cookie. The
cookie is **HttpOnly**, so page JavaScript cannot read it — the only way to get
it is from the browser's own cookie store, which is why this drives a real
browser over the Chrome DevTools Protocol rather than injecting a script.

Those credentials are not bearer tokens, so requests go to your workspace host
rather than `slack.com/api`:

```
POST <workspace_url>/api/chat.postMessage
Cookie: d=<urlencoded xoxd>
body:   token=<xoxc>&channel=<id>&text=<message>
```

Requires Chrome, Edge, Chromium or Brave. Nothing is downloaded and no browser
is installed — it drives the one you already have.

## Security notes

- **Token values are never printed or logged**, by any command.
- Credentials live in `~/.config/slack-notifier/config.json` (mode `0600`); the
  browser profile is `0700`.
- The browser runs against **its own profile** in
  `~/.config/slack-notifier/browser-profile`, separate from your everyday
  browsing. It has to: since Chrome 136 the browser refuses remote debugging
  against the default profile, so your existing session cannot be reused. That
  is why you sign in once the first time.
- **The browser profile is a credential store.** While it exists, `login` can
  re-mint working tokens with no prompt. `logout` therefore deletes it along
  with the config; pass `--keep-browser-session` to keep it signed in.
- While the browser is open it exposes a DevTools port on loopback that any
  local process could connect to. The browser is closed as soon as capture
  finishes, and also on `Ctrl-C` or termination.
- Only `https://` URLs on a `slack.com` host are ever paired with the session
  cookie — a URL read from the browser that points anywhere else is refused.
- `chat.postMessage` is **never** retried after a server or network error,
  because a write that failed that way may still have been applied and retrying
  could double-post. A rate limit (HTTP 429) *is* retried, since Slack rejected
  the call outright.

## Environment

| Variable | Purpose |
|---|---|
| `SLACK_NOTIFIER_BROWSER` | Path to a browser, if yours is not auto-detected |
| `SLACK_NOTIFIER_CONFIG_DIR` | Override `~/.config/slack-notifier` |

## Development

```bash
bun test            # unit tests — no browser, no network
bun run typecheck
bun run build
```

```
src/index.ts            argv parsing, command dispatch, exit codes
src/commands/           login, send, whoami/logout
src/lib/browser.ts      browser discovery, launch, CDP connection
src/lib/auto-login.ts   capture policy: poll until credentials are readable
src/lib/capture.ts      pure extractors for the cookie and localConfig_v2
src/lib/slack.ts        browser-auth request(), retry policy, error classes
src/lib/channels.ts     channel name -> ID resolution and cache
src/lib/config.ts       config storage and permissions
```

Anything with a rule in it is pure and unit tested — the extractors run against
fixtures, and capture runs against a fake CDP session, so the suite needs
neither a browser nor Slack.

## Unaffiliated

Not affiliated with, endorsed by, or supported by Slack Technologies.

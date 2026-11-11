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

## Quick install

Use your agent to help you install with this prompt:

> "Help me install the latest https://github.com/robintegg-createfuture/slack-notifier and also install the skill globally into my copilot skills folder"

## Install

Download the latest macOS release for your Mac. Prebuilt binaries are
self-contained; **Bun is not required**.

```bash
case "$(uname -m)" in
  arm64) asset="slack-notifier-darwin-arm64" ;; # Apple silicon
  x86_64) asset="slack-notifier-darwin-x64" ;;  # Intel
  *) echo "Unsupported Mac architecture: $(uname -m)" >&2; exit 1 ;;
esac

mkdir -p "$HOME/.local/bin"
curl -fL "https://github.com/robintegg-createfuture/slack-notifier/releases/latest/download/$asset" \
  -o "$HOME/.local/bin/slack-notifier"
chmod 755 "$HOME/.local/bin/slack-notifier"
```

Make sure `$HOME/.local/bin` is on your `PATH`. To keep it on `PATH` in zsh,
add this line to `~/.zshrc`:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Open a new terminal, then check the installation:

```bash
slack-notifier --help
```

Each GitHub Release includes binaries for Apple silicon (`arm64`) and Intel
(`x64`), plus `checksums.txt`. If no release is available yet, use the
[build from source](#build-from-source) instructions below.

## Usage

```bash
slack-notifier login [--headless] [--workspace <name>] [--timeout <seconds>]
slack-notifier send --channel <name> [--message <text>] [--json]
slack-notifier whoami [--json]
slack-notifier logout [--keep-browser-session]
slack-notifier help [<command>] [--json]
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

## Help, and using this from an agent

Help is written for a caller that has nothing else to go on. Every command
carries its own options, examples, `--json` shape, and the errors it can return
with what to do about each:

```bash
slack-notifier --help            # every command, with a one-line summary
slack-notifier send --help       # options, examples, JSON shape, errors
slack-notifier help send         # the same thing
```

For a caller that would rather not parse prose, the whole specification is
available as data:

```bash
slack-notifier help --json       # the whole CLI
slack-notifier help send --json  # one command
```

```jsonc
{
  "program": "slack-notifier",
  "commands": [
    {
      "name": "send",
      "summary": "Post a message to a channel.",
      "usage": "slack-notifier send --channel <name> [--message <text>] [--json]",
      "options": [{ "name": "channel", "alias": "c", "required": true, "description": "…" }],
      "examples": ["…"],
      "jsonShape": { "ok": "boolean — …", "ts": "string — …" },
      "errors": [{ "condition": "Not signed in", "fix": "Run slack-notifier login." }]
    }
  ],
  "environment": [{ "name": "SLACK_NOTIFIER_BROWSER", "description": "…" }],
  "exit_codes": { "0": "success", "1": "failure" }
}
```

Three guarantees that make this safe to drive unattended:

- **Streams are separated.** Human-readable output goes to stdout; progress and
  errors go to stderr. With `--json`, stdout carries one JSON object and nothing
  else — a failure exits `1` and writes nothing to stdout.
- **A usage error names the help to read.** `slack-notifier send -m hi` prints
  `(run "slack-notifier send --help" for usage and examples)`.
- **`--help` never does anything.** `login --help` prints help without opening a
  browser.

The specification is also the dispatch table, so a command cannot exist without
being documented, and the test suite fails if any command is missing a
description, an example, an option description, a `--json` shape, or a fix for a
documented error.

### Install the agent skill

Help only helps an agent that already knows this tool exists. The skill in
[`skills/slack-notifier`](skills/slack-notifier/SKILL.md) is the part that
makes an agent reach for it unprompted, and it ships the operating rules the
CLI itself cannot enforce — confirm the channel and message before posting, and
never retry a failed send.

Install it from GitHub with the [`skills`](https://github.com/vercel-labs/skills)
CLI:

```bash
npx skills add robintegg-createfuture/slack-notifier --skill slack-notifier -g                      # every detected agent, user-wide
npx skills add robintegg-createfuture/slack-notifier --skill slack-notifier -g -a claude-code -y    # just one agent, no prompts
npx skills add robintegg-createfuture/slack-notifier --skill slack-notifier                         # this project only
```

A global install puts the skill in the shared canonical directory
`~/.agents/skills/slack-notifier` and symlinks the agents that use their own
location. Agents reading the shared directory — GitHub Copilot and Codex among
them — pick it up from there, so it is available in every project.

The repository also includes a [`tag-release`](skills/tag-release/SKILL.md)
skill for creating the next numbered release tag from `origin/main`.

Remove either skill with `npx skills remove -g slack-notifier` or
`npx skills remove -g tag-release`.

The `slack-notifier` skill deliberately does not restate the CLI's options or
errors — it points at `slack-notifier help --json`, so there is one source of
truth. A test parses its `SKILL.md` and checks every `slack-notifier …` command
and flag it mentions against the real specification, so the skill cannot drift
without the suite going red.

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

## Enterprise Grid

Grid works, with one wrinkle. A browser session on Grid is scoped to the **org**
rather than to a workspace, so `conversations.list` is refused outright with
`enterprise_is_restricted` — posting is fine, it is only looking a channel *name*
up that Slack blocks.

When that happens, the name is resolved from the client boot payload instead,
which covers the channels you are a member of, including private ones. This is
automatic; there is nothing to configure. Passing a channel ID or an archive
link skips name resolution altogether.

Requests go to the org host (`https://<org>.enterprise.slack.com`), which is
what `auth.test` reports, and that is what `whoami` shows.

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

### Build from source

Requires [Bun](https://bun.sh) 1.4+.

```bash
bun install
bun run build          # -> ./dist/slack-notifier
```

The result is a self-contained binary for the current machine. Put it on your
`PATH`:

```bash
mkdir -p ~/.local/bin
ln -sf "$PWD/dist/slack-notifier" ~/.local/bin/slack-notifier
```

A symlink means a later `bun run build` updates the installed command too. Copy
it instead if you would rather not depend on the checkout staying put.

`dist/` is not committed, so a fresh clone needs `bun install && bun run build`.

### Release

Releases use whole-number tags only: `v1`, `v2`, `v3`, and so on. Do not use
point releases such as `v1.0.0`. To create the next tag from the latest `main`:

```bash
git fetch origin
git tag v1 origin/main
git push origin v1
```

Replace `v1` with the next unused tag for each release, and use the same tag in
both commands. Pushing the tag runs the release workflow, which tests and
typechecks the code, then publishes the macOS binaries and checksums as a
GitHub Release.

```
src/index.ts            argv parsing, command dispatch, exit codes
src/commands/           login, send, whoami/logout
src/lib/help.ts         the command specification, rendered as text or JSON
src/lib/browser.ts      browser discovery, launch, CDP connection
src/lib/auto-login.ts   capture policy: poll until credentials are readable
src/lib/capture.ts      pure extractors for the cookie and localConfig_v2
src/lib/slack.ts        browser-auth request(), retry policy, error classes
src/lib/channels.ts     channel name -> ID resolution and cache
src/lib/config.ts       config storage and permissions
skills/slack-notifier/  the installable agent skill
```

Anything with a rule in it is pure and unit tested — the extractors run against
fixtures, and capture runs against a fake CDP session, so the suite needs
neither a browser nor Slack.

## Unaffiliated

Not affiliated with, endorsed by, or supported by Slack Technologies.

---
name: slack-notifier
description: Post a message or notification to a named Slack channel from the command line with the slack-notifier CLI. Use when asked to send or post something to Slack, notify or message a channel such as #alerts or #general, announce or share a result with a team, alert someone in Slack, ping a channel when a job finishes, or pipe build, test or deploy output into Slack. Also covers signing in to Slack (login), checking which Slack account is in use (whoami), and signing out (logout).
---

# slack-notifier

A Java command-line application that posts a message to a Slack channel.
Requires Java 21 or later. Exit code is `0` on success and `1` on failure.

## Always confirm before posting

A Slack post is immediately visible to everyone in the channel and awkward to
retract. Before the first send of a task, show the user:

- the channel, exactly as it will be passed
- the full message text, verbatim

Then wait for an explicit go-ahead. **Do not skip this when running
unattended** — unattended is precisely when a wrong channel goes unnoticed.
Once the user has approved a run, further sends to that same channel in the
same task need no new approval.

## Preflight

Run this first. It costs one API call and distinguishes every failure mode:

```bash
slack-notifier whoami --json
```

| Result | Meaning | Do this |
| --- | --- | --- |
| exit `0`, JSON on stdout | Signed in | Proceed |
| `command not found` | Not installed | See *If the command is missing* |
| `Not signed in` | No stored credentials | Ask the user to run `slack-notifier login` |
| `invalid_auth` / `token_expired` | Browser session ended | Ask the user to run `slack-notifier login` |

`slack-notifier login` opens a real browser window and needs a human to sign
in. Never run it unattended — ask the user to run it and report back.

## Sending

```bash
slack-notifier send --channel "#alerts" --message "Deploy finished" --json
```

Always pass `--json`. On success stdout carries one object with the resolved
`channel`, the `ts`, and a `permalink` worth quoting back to the user. On
failure nothing goes to stdout and the exit code is `1`.

- Quote any channel starting with `#`, or the shell treats it as a comment.
- `--channel` also accepts a bare name, a channel ID, or a Slack archive link.
  Prefer an ID when the user gives one; it skips name resolution entirely.
- Omit `--message` to read the message body from stdin, which is how to get
  command output into a channel:

```bash
./run-build.sh 2>&1 | tail -20 | slack-notifier send -c "#alerts"
```

Keep messages short. For long output, send a summary plus a link rather than
pasting hundreds of lines into a channel.

## Never retry a failed send

If a send fails with a server or network error, the message may still have been
posted. Re-running risks double-posting. Report the failure to the user
instead. The CLI follows the same rule internally and deliberately does not
retry writes.

## Everything else

The CLI documents itself, including every option, the exact JSON shape, and
each error paired with its fix. Read that rather than guessing:

```bash
slack-notifier help --json        # the whole CLI as data
slack-notifier help send --json   # one command as data
slack-notifier send --help        # the same, human-readable
```

When a command fails with a Slack error code, look it up in the `errors` array
for that command — each entry carries a `fix`.

## If the command is missing

With Java 21 or later installed, download the latest release and install a
launcher:

```bash
mkdir -p "$HOME/.local/share/slack-notifier" "$HOME/.local/bin" &&
curl -fL "https://github.com/robintegg-createfuture/slack-notifier/releases/latest/download/slack-notifier.jar" \
  -o "$HOME/.local/share/slack-notifier/slack-notifier.jar" &&
curl -fL "https://github.com/robintegg-createfuture/slack-notifier/releases/latest/download/checksums.txt" \
  -o "$HOME/.local/share/slack-notifier/checksums.txt" &&
(cd "$HOME/.local/share/slack-notifier" && shasum -a 256 -c checksums.txt) &&
cat > "$HOME/.local/bin/slack-notifier" <<'EOF' &&
#!/usr/bin/env bash
exec java -jar "$HOME/.local/share/slack-notifier/slack-notifier.jar" "$@"
EOF
chmod 755 "$HOME/.local/bin/slack-notifier"
```

Confirm `~/.local/bin` is on `PATH` and run `slack-notifier help`. See the
repository README for checksum verification alternatives and building from source.

## Notes

- Never print, echo, or cat the stored credentials in
  `~/.config/slack-notifier`. No command ever needs to, and no command prints
  token values.
- On Enterprise Grid, resolving a channel by *name* only finds channels the
  signed-in account is a member of. If a name is not found, ask for the channel
  ID. See `slack-notifier help send` for detail.
- `slack-notifier logout` deletes the stored credentials and the browser
  profile. Only run it when the user asks.

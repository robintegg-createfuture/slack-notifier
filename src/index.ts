#!/usr/bin/env bun
import { parseArgs } from "./lib/args.ts";
import { login } from "./commands/login.ts";
import { send } from "./commands/send.ts";
import { logout, whoami } from "./commands/session.ts";

const ALIASES: Record<string, string> = {
  c: "channel",
  m: "message",
  h: "help",
};

const HELP = `slack-notifier — publish a notification to a named Slack channel.

Usage:
  slack-notifier login [--headless] [--workspace <name>] [--timeout <seconds>]
  slack-notifier send --channel <name> [--message <text>] [--json]
  slack-notifier whoami [--json]
  slack-notifier logout [--keep-browser-session]

Commands:
  login     Sign in by capturing the tokens of a Slack session in your browser.
            The first run opens a window and you sign in as you normally would;
            the profile persists, so later runs can use --headless.
  send      Post a message. --channel takes "#alerts", "alerts", a channel ID,
            or a Slack archive link. With no --message, the message is read
            from stdin.
  whoami    Verify the stored credentials against Slack.
  logout    Delete the stored credentials and the browser profile.

Options:
  -c, --channel <name>      Channel to post to.
  -m, --message <text>      Message to post.
      --workspace <name>    Pick a workspace when signed into several.
      --headless            Sign in with no visible window (after a first login).
      --timeout <seconds>   How long login waits for sign-in. Default 300.
      --keep-browser-session  Leave the browser profile in place on logout.
      --json                Machine-readable output for send and whoami.
  -h, --help                Show this help.

Environment:
  SLACK_NOTIFIER_BROWSER      Path to a browser, if yours is not auto-detected.
  SLACK_NOTIFIER_CONFIG_DIR   Override ~/.config/slack-notifier.

Notes:
  The browser profile is a credential store: while it exists, login can re-mint
  working tokens with no prompt. Token values are never printed.

Exit codes: 0 on success, 1 on any failure.
`;

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2), ALIASES);

  if (args.flags.help === true || args.command === "help" || args.command === undefined) {
    console.log(HELP);
    return args.command === undefined && args.flags.help !== true ? 1 : 0;
  }

  switch (args.command) {
    case "login":
      return await login(args);
    case "send":
      return await send(args);
    case "whoami":
      return await whoami(args);
    case "logout":
      return await logout(args);
    default:
      console.error(`Unknown command "${args.command}".`);
      console.error('Run "slack-notifier --help" for usage.');
      return 1;
  }
}

try {
  process.exitCode = await main();
} catch (err) {
  console.error(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
}

/**
 * The command specification, and the two ways it is rendered.
 *
 * Help is written for an agent that has nothing else to go on: every command
 * carries its own examples, the shape of its `--json` output, and the errors it
 * can return with what to do about each. `help --json` emits the whole
 * specification as data so a caller can discover the CLI without parsing prose.
 *
 * This is the single source of truth. `src/index.ts` dispatches on the same
 * `COMMANDS` table, so a command cannot exist without being documented, and the
 * tests in `test/help.test.ts` fail if any part of an entry is missing.
 */

export interface OptionSpec {
  /** Long flag name, without the leading dashes. */
  name: string;
  /** Single-character alias, if there is one. */
  alias?: string;
  /** Placeholder for the value, or omitted when the flag is a boolean. */
  value?: string;
  description: string;
  default?: string;
  required?: boolean;
}

export interface ErrorSpec {
  /** What the user sees, or the Slack error code behind it. */
  condition: string;
  fix: string;
}

export interface CommandSpec {
  name: string;
  /** One line, used in the command list. */
  summary: string;
  /** The full explanation, shown by `<command> --help`. */
  description: string;
  usage: string;
  options: OptionSpec[];
  examples: string[];
  /** Keys written to stdout under `--json`, when the command supports it. */
  jsonShape?: Record<string, string>;
  notes?: string[];
  errors?: ErrorSpec[];
}

export const PROGRAM = "slack-notifier";
export const SUMMARY = "Publish a notification to a named Slack channel.";

export const GLOBAL_NOTES = [
  "Exit code is 0 on success and 1 on any failure, so this drops straight into a script.",
  "Human-readable output goes to stdout; progress and errors go to stderr. With --json, stdout carries one JSON object and nothing else.",
  "Token values are never printed, by any command.",
];

export const ENVIRONMENT: Array<{ name: string; description: string }> = [
  {
    name: "SLACK_NOTIFIER_BROWSER",
    description: "Path to a browser, if yours is not auto-detected.",
  },
  {
    name: "SLACK_NOTIFIER_CONFIG_DIR",
    description: "Override ~/.config/slack-notifier.",
  },
];

export const COMMANDS: CommandSpec[] = [
  {
    name: "login",
    summary: "Sign in by capturing the tokens of a Slack session in your browser.",
    description:
      "Opens a browser on Slack and captures the session tokens once you sign in. " +
      "No Slack app and no admin approval are needed. The first run needs you to " +
      "sign in; the browser profile persists, so later runs can use --headless to " +
      "refresh the tokens with no window and no prompt. Browser tokens expire with " +
      "the web session, so a scheduled headless login keeps a long-lived notifier " +
      "working. Requires Chrome, Edge, Chromium or Brave.",
    usage: `${PROGRAM} login [--headless] [--workspace <name>] [--timeout <seconds>]`,
    options: [
      {
        name: "headless",
        description:
          "Sign in with no visible window. Only works once a normal login has signed the profile in.",
      },
      {
        name: "workspace",
        value: "<name>",
        description:
          "Pick a workspace by name, ID or URL when signed into several. Defaults to the first found.",
      },
      {
        name: "timeout",
        value: "<seconds>",
        description: "How long to wait for you to sign in.",
        default: "300",
      },
    ],
    examples: [
      `${PROGRAM} login`,
      `${PROGRAM} login --headless`,
      `${PROGRAM} login --workspace "Acme Corp" --timeout 600`,
    ],
    notes: [
      "The browser runs against its own profile in ~/.config/slack-notifier/browser-profile, not your everyday one: since Chrome 136 the browser refuses remote debugging against the default profile.",
      "The browser is closed as soon as capture finishes, and also on Ctrl-C.",
    ],
    errors: [
      {
        condition: "No Chrome, Edge, Chromium or Brave installation was found",
        fix: "Install one, or set SLACK_NOTIFIER_BROWSER to the browser's path.",
      },
      {
        condition: "Timed out waiting for a signed-in Slack session",
        fix: `Run ${PROGRAM} login again and sign in, or raise --timeout.`,
      },
      {
        condition: "Timed out with no visible window (--headless)",
        fix: `Run ${PROGRAM} login without --headless once, to sign the profile in.`,
      },
    ],
  },
  {
    name: "send",
    summary: "Post a message to a channel.",
    description:
      "Posts a message to a channel. --channel takes a name, a #name, a channel ID, " +
      "or a Slack archive link; a name is resolved once and cached, so repeat " +
      "notifications cost a single API call. With no --message, the message is read " +
      "from stdin, which is how you pipe build output into a channel.",
    usage: `${PROGRAM} send --channel <name> [--message <text>] [--json]`,
    options: [
      {
        name: "channel",
        alias: "c",
        value: "<name>",
        description: "Channel to post to: a name, a #name, a channel ID, or an archive link.",
        required: true,
      },
      {
        name: "message",
        alias: "m",
        value: "<text>",
        description: "Message to post. Read from stdin when omitted.",
      },
      { name: "json", description: "Write the result to stdout as one JSON object." },
    ],
    examples: [
      `${PROGRAM} send --channel "#alerts" --message "Deploy finished"`,
      `${PROGRAM} send -c alerts -m "Build 42 failed"`,
      `${PROGRAM} send -c C0123456 -m "By channel ID"`,
      `./run-build.sh 2>&1 | tail -5 | ${PROGRAM} send -c "#alerts"`,
      `${PROGRAM} send -c "#alerts" -m "Deploy finished" --json`,
    ],
    jsonShape: {
      ok: "boolean — always true; a failure exits 1 and writes nothing to stdout",
      channel: "string — the resolved channel ID",
      ts: "string — the message timestamp, which identifies the message",
      permalink: "string|null — a link to the posted message",
    },
    notes: [
      "A send is never retried after a server or network error, because a write that failed that way may still have been applied and retrying could double-post. A rate limit (HTTP 429) is retried, since Slack rejected the call outright.",
      "On Enterprise Grid, looking a channel up by name uses the client boot payload, which covers the channels you are a member of. Pass a channel ID or an archive link to skip name resolution entirely.",
    ],
    errors: [
      {
        condition: "Not signed in",
        fix: `Run ${PROGRAM} login.`,
      },
      {
        condition: 'No channel named "..." is visible to this account',
        fix: "Check the spelling, and that the account is a member of the channel. Or pass the channel ID.",
      },
      {
        condition: "channel_not_found",
        fix: "The channel ID does not exist, or this account cannot see it.",
      },
      {
        condition: "invalid_auth, token_expired, token_revoked, not_authed, account_inactive",
        fix: `The browser session behind the stored tokens has ended. Run ${PROGRAM} login.`,
      },
    ],
  },
  {
    name: "whoami",
    summary: "Verify the stored credentials against Slack.",
    description:
      "Answers which workspace and user the CLI is acting as, and whether the stored " +
      "credentials still work. It makes one call to Slack, so there is no offline " +
      "mode — the check is the point. Use it as a gate in a script before sending.",
    usage: `${PROGRAM} whoami [--json]`,
    options: [{ name: "json", description: "Write the result to stdout as one JSON object." }],
    examples: [`${PROGRAM} whoami`, `${PROGRAM} whoami --json`],
    jsonShape: {
      status: 'string — "ok"; a failed check exits 1 and writes nothing to stdout',
      workspace_name: "string — the workspace or organisation name",
      workspace_id: "string — the Slack team or enterprise ID",
      workspace_url: "string — the host requests are sent to",
      user: "string — the signed-in user as Slack reports it",
      user_id: "string — that user's Slack ID",
    },
    errors: [
      { condition: "Not signed in", fix: `Run ${PROGRAM} login.` },
      {
        condition: "invalid_auth, token_expired, token_revoked, not_authed, account_inactive",
        fix: `The stored credentials were refused. Run ${PROGRAM} login.`,
      },
    ],
  },
  {
    name: "logout",
    summary: "Delete the stored credentials and the browser profile.",
    description:
      "Removes the stored credentials. The browser profile goes too, because while " +
      "it exists a login can re-mint working tokens with no prompt — it is itself a " +
      "credential store. Pass --keep-browser-session to leave it signed in.",
    usage: `${PROGRAM} logout [--keep-browser-session]`,
    options: [
      {
        name: "keep-browser-session",
        description:
          "Leave the browser profile in place, so a later login needs no sign-in.",
      },
    ],
    examples: [`${PROGRAM} logout`, `${PROGRAM} logout --keep-browser-session`],
    notes: ["Succeeds whether or not anything was stored."],
  },
];

export function findCommand(name: string | undefined): CommandSpec | undefined {
  return COMMANDS.find((command) => command.name === name);
}

/** The hint printed after a usage error, pointing at the command's own help. */
export function usageHint(command: string): string {
  return `(run "${PROGRAM} ${command} --help" for usage and examples)`;
}

export function renderRootHelp(): string {
  const lines: string[] = [`${PROGRAM} — ${SUMMARY}`, "", "Usage:"];
  for (const command of COMMANDS) lines.push(`  ${command.usage}`);
  lines.push(`  ${PROGRAM} help [<command>] [--json]`);

  lines.push("", "Commands:");
  const width = Math.max(...COMMANDS.map((c) => c.name.length));
  for (const command of COMMANDS) {
    lines.push(`  ${command.name.padEnd(width)}  ${command.summary}`);
  }

  lines.push(
    "",
    `Run "${PROGRAM} <command> --help" for that command's options, examples and errors.`,
    `Run "${PROGRAM} help --json" for the whole specification as JSON.`,
  );

  lines.push("", "Environment:");
  const envWidth = Math.max(...ENVIRONMENT.map((e) => e.name.length));
  for (const entry of ENVIRONMENT) {
    lines.push(`  ${entry.name.padEnd(envWidth)}  ${entry.description}`);
  }

  lines.push("", "Notes:");
  for (const note of GLOBAL_NOTES) lines.push(...wrapBullet(note));

  return lines.join("\n");
}

export function renderCommandHelp(command: CommandSpec): string {
  const lines: string[] = [`${PROGRAM} ${command.name} — ${command.summary}`, ""];
  lines.push(...wrap(command.description, 78).map((line) => line));
  lines.push("", "Usage:", `  ${command.usage}`);

  if (command.options.length > 0) {
    lines.push("", "Options:");
    const rendered = command.options.map((option) => ({
      left: renderOptionFlags(option),
      option,
    }));
    const width = Math.max(...rendered.map((r) => r.left.length));
    for (const { left, option } of rendered) {
      const extras: string[] = [];
      if (option.required) extras.push("required");
      if (option.default !== undefined) extras.push(`default ${option.default}`);
      const suffix = extras.length > 0 ? ` [${extras.join(", ")}]` : "";
      lines.push(`  ${left.padEnd(width)}  ${option.description}${suffix}`);
    }
  }

  lines.push("", "Examples:");
  for (const example of command.examples) lines.push(`  ${example}`);

  if (command.jsonShape) {
    lines.push("", "With --json, stdout carries one object:");
    for (const [key, description] of Object.entries(command.jsonShape)) {
      lines.push(`  ${key}: ${description}`);
    }
  }

  if (command.notes && command.notes.length > 0) {
    lines.push("", "Notes:");
    for (const note of command.notes) lines.push(...wrapBullet(note));
  }

  if (command.errors && command.errors.length > 0) {
    lines.push("", "Errors:");
    for (const error of command.errors) {
      lines.push(`  ${error.condition}`);
      lines.push(...wrap(error.fix, 72).map((line) => `      ${line}`));
    }
  }

  return lines.join("\n");
}

/** The whole specification as data, for a caller that would rather not parse prose. */
export function helpAsJson(command?: CommandSpec): string {
  if (command) return JSON.stringify(command, null, 2);
  return JSON.stringify(
    {
      program: PROGRAM,
      summary: SUMMARY,
      commands: COMMANDS,
      environment: ENVIRONMENT,
      notes: GLOBAL_NOTES,
      exit_codes: { "0": "success", "1": "failure" },
    },
    null,
    2,
  );
}

function renderOptionFlags(option: OptionSpec): string {
  const long = `--${option.name}${option.value ? ` ${option.value}` : ""}`;
  return option.alias ? `-${option.alias}, ${long}` : `    ${long}`;
}

function wrapBullet(text: string): string[] {
  const [first, ...rest] = wrap(text, 74);
  return [`  - ${first}`, ...rest.map((line) => `    ${line}`)];
}

function wrap(text: string, width: number): string[] {
  const lines: string[] = [];
  let current = "";
  for (const word of text.split(" ")) {
    if (current.length === 0) current = word;
    else if (current.length + 1 + word.length <= width) current += ` ${word}`;
    else {
      lines.push(current);
      current = word;
    }
  }
  if (current.length > 0) lines.push(current);
  return lines;
}

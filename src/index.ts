#!/usr/bin/env bun
import { flagBoolean, parseArgs, type ParsedArgs } from "./lib/args.ts";
import { login } from "./commands/login.ts";
import { send } from "./commands/send.ts";
import { logout, whoami } from "./commands/session.ts";
import {
  COMMANDS,
  findCommand,
  helpAsJson,
  PROGRAM,
  renderCommandHelp,
  renderRootHelp,
} from "./lib/help.ts";

const ALIASES: Record<string, string> = {
  c: "channel",
  m: "message",
  h: "help",
};

type Handler = (args: ParsedArgs) => Promise<number>;

const HANDLERS: Record<string, Handler> = { login, send, whoami, logout };

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2), ALIASES);
  const json = flagBoolean(args.flags, "json");

  // `help <command>` and `<command> --help` ask the same question.
  if (args.command === "help") {
    const topic = args.positional[0];
    if (topic === undefined) {
      console.log(json ? helpAsJson() : renderRootHelp());
      return 0;
    }
    const spec = findCommand(topic);
    if (!spec) return unknownCommand(topic);
    console.log(json ? helpAsJson(spec) : renderCommandHelp(spec));
    return 0;
  }

  if (args.command === undefined) {
    // Nothing was asked for, so this is a usage error — but still print the
    // help that says what to ask for.
    const wantsHelp = flagBoolean(args.flags, "help");
    console.log(json ? helpAsJson() : renderRootHelp());
    return wantsHelp ? 0 : 1;
  }

  const spec = findCommand(args.command);
  if (!spec) return unknownCommand(args.command);

  if (flagBoolean(args.flags, "help")) {
    console.log(json ? helpAsJson(spec) : renderCommandHelp(spec));
    return 0;
  }

  return await HANDLERS[spec.name]!(args);
}

function unknownCommand(name: string): number {
  console.error(`Unknown command "${name}".`);
  console.error(`Commands: ${COMMANDS.map((c) => c.name).join(", ")}.`);
  console.error(`Run "${PROGRAM} --help" for usage.`);
  return 1;
}

try {
  process.exitCode = await main();
} catch (err) {
  console.error(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
}

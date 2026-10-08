import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { COMMANDS, findCommand, PROGRAM } from "../src/lib/help.ts";

const SKILL_PATH = join(import.meta.dir, "..", "skills", "slack-notifier", "SKILL.md");
const skill = readFileSync(SKILL_PATH, "utf8");

function frontmatter(source: string): Record<string, string> {
  const match = /^---\n([\s\S]*?)\n---\n/.exec(source);
  if (!match?.[1]) throw new Error("SKILL.md has no YAML frontmatter");
  const fields: Record<string, string> = {};
  for (const line of match[1].split("\n")) {
    const field = /^([a-z][a-z0-9_-]*):\s*(.*)$/i.exec(line);
    if (field?.[1] && field[2]) fields[field[1]] = field[2].trim();
  }
  return fields;
}

interface Invocation {
  command: string;
  flags: string[];
  line: string;
}

/**
 * Every `slack-notifier ...` invocation written anywhere in the skill, so the
 * skill cannot reference a command or flag the CLI does not have.
 */
function invocations(source: string): Invocation[] {
  const found: Invocation[] = [];
  const sp = "[^\\S\\n]"; // horizontal whitespace only, so a match never spans lines
  const arg = `(?:-{1,2}[a-z-]+|"[^"\\n]*"|[^\\s|<>]+)`;
  const pattern = new RegExp(`${PROGRAM}${sp}+([a-z-]+)((?:${sp}+${arg})*)`, "g");
  for (const match of source.matchAll(pattern)) {
    const command = match[1];
    if (!command) continue;
    const flags = [...(match[2] ?? "").matchAll(/(?:^|\s)-{1,2}([a-z][a-z-]*)/g)]
      .map((flag) => flag[1])
      .filter((flag): flag is string => flag !== undefined);
    found.push({ command, flags, line: match[0] });
  }
  return found;
}

describe("skill frontmatter satisfies the skills CLI", () => {
  const fields = frontmatter(skill);

  test("declares the required name and description, and nothing surprising", () => {
    expect(fields.name).toBe(PROGRAM);
    expect(Object.keys(fields).sort()).toEqual(["description", "name"]);
  });

  test("the description is rich enough to trigger on real phrasings", () => {
    const description = fields.description ?? "";
    expect(description.length).toBeGreaterThan(200);
    for (const phrasing of ["Slack", "channel", "notif", "post"]) {
      expect(description.toLowerCase()).toContain(phrasing.toLowerCase());
    }
  });

  test("names every command, so the skill covers the whole CLI", () => {
    const body = skill.toLowerCase();
    for (const command of COMMANDS) {
      expect(body).toContain(command.name);
    }
  });
});

describe("the skill cannot drift from the CLI", () => {
  const used = invocations(skill);

  test("finds the invocations it is meant to be checking", () => {
    expect(used.length).toBeGreaterThan(5);
  });

  test("every invocation names a real command", () => {
    const known = new Set([...COMMANDS.map((command) => command.name), "help"]);
    for (const { command, line } of used) {
      expect({ line, known: known.has(command) }).toEqual({ line, known: true });
    }
  });

  test("every flag exists on the command it is passed to", () => {
    for (const { command, flags, line } of used) {
      // `help <command>` takes the target command's --json, not its own flags.
      const spec = command === "help" ? findCommand("whoami") : findCommand(command);
      if (!spec) continue;
      const allowed = new Set<string>(["help"]);
      for (const option of spec.options) {
        allowed.add(option.name);
        if (option.alias) allowed.add(option.alias);
      }
      for (const flag of flags) {
        expect({ line, flag, allowed: allowed.has(flag) }).toEqual({ line, flag, allowed: true });
      }
    }
  });

  test("a required option is never omitted from a full send example", () => {
    // `send --help` only prints help, so it is exempt from send's requirements.
    const sends = used.filter(({ command, flags }) => command === "send" && !flags.includes("help"));
    expect(sends.length).toBeGreaterThan(0);
    for (const { flags, line } of sends) {
      const hasChannel = flags.includes("channel") || flags.includes("c");
      expect({ line, hasChannel }).toEqual({ line, hasChannel: true });
    }
  });
});

describe("the skill carries the operating policy the CLI cannot", () => {
  test("tells the agent to confirm before posting", () => {
    expect(skill).toContain("Always confirm before posting");
  });

  test("tells the agent never to retry a failed send", () => {
    expect(skill).toContain("Never retry a failed send");
  });

  test("points at the CLI's own help rather than restating it", () => {
    expect(skill).toContain(`${PROGRAM} help --json`);
  });

  test("stays short enough to be cheap to load", () => {
    expect(skill.split("\n").length).toBeLessThan(140);
  });
});

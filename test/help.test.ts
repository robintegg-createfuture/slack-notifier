import { describe, expect, test } from "bun:test";
import {
  COMMANDS,
  findCommand,
  helpAsJson,
  PROGRAM,
  renderCommandHelp,
  renderRootHelp,
  usageHint,
} from "../src/lib/help.ts";

describe("every command is documented well enough for an agent", () => {
  for (const command of COMMANDS) {
    describe(command.name, () => {
      test("has a summary, a description and a usage line", () => {
        expect(command.summary.length).toBeGreaterThan(10);
        expect(command.description.length).toBeGreaterThan(40);
        expect(command.usage.startsWith(`${PROGRAM} ${command.name}`)).toBe(true);
      });

      test("describes every option", () => {
        for (const option of command.options) {
          expect(option.name).not.toBe("");
          expect(option.description.length).toBeGreaterThan(10);
        }
      });

      test("has examples that start with its own path and use only its flags", () => {
        expect(command.examples.length).toBeGreaterThan(0);
        const known = new Set<string>(["json", "help"]);
        for (const option of command.options) {
          known.add(option.name);
          if (option.alias) known.add(option.alias);
        }
        for (const example of command.examples) {
          expect(example).toContain(`${PROGRAM} ${command.name}`);
          for (const flag of example.match(/(?<=\s)--?[a-z][\w-]*/g) ?? []) {
            expect(known).toContain(flag.replace(/^--?/, ""));
          }
        }
      });

      test("documents the --json shape when it offers --json", () => {
        const offersJson = command.options.some((option) => option.name === "json");
        if (offersJson) {
          expect(command.jsonShape).toBeDefined();
          expect(Object.keys(command.jsonShape!).length).toBeGreaterThan(0);
          expect(command.examples.some((e) => e.includes("--json"))).toBe(true);
        } else {
          expect(command.jsonShape).toBeUndefined();
        }
      });

      test("pairs every documented error with a fix", () => {
        for (const error of command.errors ?? []) {
          expect(error.condition.length).toBeGreaterThan(5);
          expect(error.fix.length).toBeGreaterThan(10);
        }
      });
    });
  }
});

describe("renderRootHelp", () => {
  const help = renderRootHelp();

  test("lists every command with its summary", () => {
    for (const command of COMMANDS) {
      expect(help).toContain(command.name);
      expect(help).toContain(command.summary);
    }
  });

  test("points at per-command help and at the JSON specification", () => {
    expect(help).toContain(`${PROGRAM} <command> --help`);
    expect(help).toContain(`${PROGRAM} help --json`);
  });

  test("documents the environment variables and exit codes", () => {
    expect(help).toContain("SLACK_NOTIFIER_BROWSER");
    expect(help).toContain("SLACK_NOTIFIER_CONFIG_DIR");
    expect(help).toContain("Exit code is 0 on success");
  });
});

describe("renderCommandHelp", () => {
  test("includes usage, options, examples, notes and errors", () => {
    const help = renderCommandHelp(findCommand("send")!);
    expect(help).toContain("Usage:");
    expect(help).toContain("-c, --channel");
    expect(help).toContain("[required]");
    expect(help).toContain("Examples:");
    expect(help).toContain("Errors:");
    expect(help).toContain("Enterprise Grid");
  });

  test("shows the JSON shape for a command that offers --json", () => {
    const help = renderCommandHelp(findCommand("whoami")!);
    expect(help).toContain("With --json, stdout carries one object:");
    expect(help).toContain("workspace_id");
  });

  test("shows a default where there is one", () => {
    expect(renderCommandHelp(findCommand("login")!)).toContain("[default 300]");
  });
});

describe("helpAsJson", () => {
  test("describes the whole CLI as data", () => {
    const spec = JSON.parse(helpAsJson());
    expect(spec.program).toBe(PROGRAM);
    expect(spec.commands.map((c: any) => c.name)).toEqual(COMMANDS.map((c) => c.name));
    expect(spec.exit_codes["0"]).toBe("success");
    expect(spec.environment.length).toBeGreaterThan(0);
  });

  test("describes a single command as data", () => {
    const spec = JSON.parse(helpAsJson(findCommand("send")!));
    expect(spec.name).toBe("send");
    expect(spec.options.find((o: any) => o.name === "channel").required).toBe(true);
    expect(spec.jsonShape.ts).toContain("timestamp");
  });
});

describe("usageHint", () => {
  test("points at the failing command's own help", () => {
    expect(usageHint("send")).toBe(`(run "${PROGRAM} send --help" for usage and examples)`);
  });
});

describe("findCommand", () => {
  test("finds a known command and nothing else", () => {
    expect(findCommand("send")?.name).toBe("send");
    expect(findCommand("nope")).toBeUndefined();
    expect(findCommand(undefined)).toBeUndefined();
  });
});

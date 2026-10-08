export interface ParsedArgs {
  command: string | undefined;
  flags: Record<string, string | true>;
  positional: string[];
}

/** Supports `--flag`, `--flag=value`, `--flag value`, and short aliases. */
export function parseArgs(argv: string[], aliases: Record<string, string> = {}): ParsedArgs {
  const flags: Record<string, string | true> = {};
  const positional: string[] = [];
  let command: string | undefined;

  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]!;

    if (token.startsWith("--")) {
      const body = token.slice(2);
      const eq = body.indexOf("=");
      if (eq !== -1) {
        flags[body.slice(0, eq)] = body.slice(eq + 1);
        continue;
      }
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("-")) {
        flags[body] = next;
        i++;
      } else {
        flags[body] = true;
      }
      continue;
    }

    if (token.startsWith("-") && token.length > 1) {
      const name = aliases[token.slice(1)];
      if (name) {
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith("-")) {
          flags[name] = next;
          i++;
        } else {
          flags[name] = true;
        }
        continue;
      }
    }

    if (command === undefined) command = token;
    else positional.push(token);
  }

  return { command, flags, positional };
}

export function flagString(flags: ParsedArgs["flags"], name: string): string | undefined {
  const value = flags[name];
  return typeof value === "string" ? value : undefined;
}

export function flagBoolean(flags: ParsedArgs["flags"], name: string): boolean {
  return flags[name] === true || flags[name] === "true";
}

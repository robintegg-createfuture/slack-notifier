import { describe, expect, test } from "bun:test";
import { flagBoolean, flagString, parseArgs } from "../src/lib/args.ts";
import { chooseWorkspace } from "../src/commands/login.ts";
import type { CapturedWorkspace } from "../src/lib/capture.ts";

const ALIASES = { c: "channel", m: "message" };

describe("parseArgs", () => {
  test("reads the command, long flags, values and aliases", () => {
    const args = parseArgs(["send", "-c", "#alerts", "--message", "hello", "--json"], ALIASES);
    expect(args.command).toBe("send");
    expect(flagString(args.flags, "channel")).toBe("#alerts");
    expect(flagString(args.flags, "message")).toBe("hello");
    expect(flagBoolean(args.flags, "json")).toBe(true);
  });

  test("reads --flag=value", () => {
    const args = parseArgs(["send", "--channel=#alerts"], ALIASES);
    expect(flagString(args.flags, "channel")).toBe("#alerts");
  });

  test("treats a trailing flag with no value as a boolean", () => {
    const args = parseArgs(["login", "--headless"], ALIASES);
    expect(flagBoolean(args.flags, "headless")).toBe(true);
  });
});

function workspace(name: string, id: string): CapturedWorkspace {
  return {
    workspace_name: name,
    workspace_id: id,
    workspace_url: `https://${name.toLowerCase()}.slack.com`,
    xoxc: `xoxc-${id}`,
  };
}

describe("chooseWorkspace", () => {
  const workspaces = [workspace("Acme", "T111"), workspace("Other", "T222")];

  test("takes the first when nothing is asked for", () => {
    expect(chooseWorkspace(workspaces, undefined)?.workspace_id).toBe("T111");
  });

  test("matches by name, ID or URL, case-insensitively", () => {
    expect(chooseWorkspace(workspaces, "other")?.workspace_id).toBe("T222");
    expect(chooseWorkspace(workspaces, "T222")?.workspace_id).toBe("T222");
    expect(chooseWorkspace(workspaces, "other.slack.com")?.workspace_id).toBe("T222");
  });

  test("returns nothing when the request matches no workspace", () => {
    expect(chooseWorkspace(workspaces, "nope")).toBeNull();
    expect(chooseWorkspace([], undefined)).toBeNull();
  });
});

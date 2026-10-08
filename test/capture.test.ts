import { describe, expect, test } from "bun:test";
import {
  extractWorkspacesFromLocalConfig,
  extractXoxdFromCookies,
  isSafeSlackUrl,
  normaliseWorkspaceUrl,
} from "../src/lib/capture.ts";

describe("extractXoxdFromCookies", () => {
  test("finds the d cookie and URL-decodes it", () => {
    const cookies = [
      { name: "b", value: "irrelevant" },
      { name: "d", value: "xoxd-abc%2Fdef%3D%3D" },
    ];
    expect(extractXoxdFromCookies(cookies)).toBe("xoxd-abc/def==");
  });

  test("ignores a d cookie that is not a Slack session value", () => {
    expect(extractXoxdFromCookies([{ name: "d", value: "something-else" }])).toBeNull();
  });

  test("tolerates junk input", () => {
    expect(extractXoxdFromCookies(null)).toBeNull();
    expect(extractXoxdFromCookies([null, 42, { name: "d" }])).toBeNull();
  });
});

describe("isSafeSlackUrl", () => {
  test("accepts https Slack hosts", () => {
    expect(isSafeSlackUrl("https://acme.slack.com")).toBe(true);
    expect(isSafeSlackUrl("https://slack.com")).toBe(true);
  });

  test("refuses anything else, so credentials cannot be redirected", () => {
    expect(isSafeSlackUrl("http://acme.slack.com")).toBe(false);
    expect(isSafeSlackUrl("https://evil.com")).toBe(false);
    expect(isSafeSlackUrl("https://slack.com.evil.com")).toBe(false);
    expect(isSafeSlackUrl("not a url")).toBe(false);
    expect(isSafeSlackUrl(undefined)).toBe(false);
  });
});

describe("normaliseWorkspaceUrl", () => {
  test("drops trailing slashes", () => {
    expect(normaliseWorkspaceUrl("https://acme.slack.com//")).toBe("https://acme.slack.com");
  });
});

describe("extractWorkspacesFromLocalConfig", () => {
  const raw = JSON.stringify({
    teams: {
      T111: {
        id: "T111",
        name: "Acme Corp",
        url: "https://acme.slack.com/",
        token: "xoxc-one",
        user_id: "U111",
      },
      T222: {
        id: "T222",
        name: "Other Team",
        url: "https://other.slack.com",
        token: "xoxc-two",
      },
    },
  });

  test("reads every signed-in workspace", () => {
    const workspaces = extractWorkspacesFromLocalConfig(raw);
    expect(workspaces).toHaveLength(2);
    expect(workspaces[0]).toEqual({
      workspace_url: "https://acme.slack.com",
      workspace_name: "Acme Corp",
      workspace_id: "T111",
      user_id: "U111",
      xoxc: "xoxc-one",
    });
    expect(workspaces[1]?.user_id).toBeUndefined();
  });

  test("accepts an already-parsed object", () => {
    expect(extractWorkspacesFromLocalConfig(JSON.parse(raw))).toHaveLength(2);
  });

  test("skips entries with a missing or wrong-shaped token", () => {
    const config = { teams: { T1: { url: "https://a.slack.com" }, T2: { url: "https://a.slack.com", token: "xoxb-nope" } } };
    expect(extractWorkspacesFromLocalConfig(config)).toEqual([]);
  });

  test("skips entries pointing off Slack", () => {
    const config = { teams: { T1: { url: "https://evil.com", token: "xoxc-one" } } };
    expect(extractWorkspacesFromLocalConfig(config)).toEqual([]);
  });

  test("degrades rather than throwing when the shape changes", () => {
    expect(extractWorkspacesFromLocalConfig("not json")).toEqual([]);
    expect(extractWorkspacesFromLocalConfig(null)).toEqual([]);
    expect(extractWorkspacesFromLocalConfig({})).toEqual([]);
    expect(extractWorkspacesFromLocalConfig({ teams: "nope" })).toEqual([]);
  });
});

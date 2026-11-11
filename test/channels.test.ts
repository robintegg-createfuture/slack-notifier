import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  looksLikeChannelId,
  normaliseChannelInput,
  resolveChannel,
} from "../src/lib/channels.ts";
import type { SlackClient } from "../src/lib/slack.ts";
import { SlackApiError } from "../src/lib/slack.ts";
import { readConfig, type StoredConfig } from "../src/lib/config.ts";

function config(overrides: Partial<StoredConfig> = {}): StoredConfig {
  return {
    workspace_url: "https://acme.slack.com",
    workspace_name: "Acme Corp",
    workspace_id: "T111",
    credentials: { xoxc: "xoxc-one", xoxd: "xoxd-one" },
    channel_cache: {},
    ...overrides,
  };
}

/** Stands in for SlackClient, recording the calls made. */
function fakeClient(
  pages: object[],
  overrides: Record<string, object> = {},
): SlackClient & { calls: Array<{ method: string; params: Record<string, string> }> } {
  const calls: Array<{ method: string; params: Record<string, string> }> = [];
  let index = 0;
  return {
    calls,
    async request(method: string, params: Record<string, string> = {}) {
      calls.push({ method, params });
      const override = overrides[method];
      if (override) {
        const body = override as any;
        if (body.__throw) throw body.__throw;
        return body;
      }
      return pages[Math.min(index++, pages.length - 1)] as any;
    },
  } as any;
}

let dir: string;
const original = process.env.SLACK_NOTIFIER_CONFIG_DIR;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "slack-notifier-channels-"));
  process.env.SLACK_NOTIFIER_CONFIG_DIR = dir;
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
  if (original === undefined) delete process.env.SLACK_NOTIFIER_CONFIG_DIR;
  else process.env.SLACK_NOTIFIER_CONFIG_DIR = original;
});

describe("normaliseChannelInput", () => {
  test("accepts a name, a hash-prefixed name, an ID, and an archive link", () => {
    expect(normaliseChannelInput(" #alerts ")).toBe("alerts");
    expect(normaliseChannelInput("alerts")).toBe("alerts");
    expect(normaliseChannelInput("C0123456")).toBe("C0123456");
    expect(normaliseChannelInput("https://acme.slack.com/archives/C0123456/p170")).toBe(
      "C0123456",
    );
  });
});

describe("looksLikeChannelId", () => {
  test("distinguishes IDs from names", () => {
    expect(looksLikeChannelId("C0123456")).toBe(true);
    expect(looksLikeChannelId("G0123456")).toBe(true);
    expect(looksLikeChannelId("alerts")).toBe(false);
    expect(looksLikeChannelId("CAT")).toBe(false);
  });
});

describe("resolveChannel", () => {
  test("uses an ID directly, with no API call", async () => {
    const client = fakeClient([]);
    expect(await resolveChannel(client, config(), "C0123456")).toBe("C0123456");
    expect(client.calls).toHaveLength(0);
  });

  test("looks a name up and caches it to disk", async () => {
    const client = fakeClient([{ ok: true, channels: [{ id: "C999", name: "alerts" }] }]);
    const stored = config();
    expect(await resolveChannel(client, stored, "#Alerts")).toBe("C999");
    expect((await readConfig())?.channel_cache).toEqual({ alerts: "C999" });
  });

  test("serves a cached name without calling Slack", async () => {
    const client = fakeClient([]);
    const stored = config({ channel_cache: { alerts: "C999" } });
    expect(await resolveChannel(client, stored, "#alerts")).toBe("C999");
    expect(client.calls).toHaveLength(0);
  });

  test("pages through the channel list", async () => {
    const client = fakeClient([
      { ok: true, channels: [{ id: "C1", name: "general" }], response_metadata: { next_cursor: "n1" } },
      { ok: true, channels: [{ id: "C2", name: "alerts" }] },
    ]);
    expect(await resolveChannel(client, config(), "alerts")).toBe("C2");
    expect(client.calls).toHaveLength(2);
    expect(client.calls[1]?.params.cursor).toBe("n1");
  });

  test("asks for private channels too", async () => {
    const client = fakeClient([{ ok: true, channels: [{ id: "C1", name: "alerts" }] }]);
    await resolveChannel(client, config(), "alerts");
    expect(client.calls[0]?.params.types).toBe("public_channel,private_channel");
  });

  test("fails with guidance when the channel is not visible", async () => {
    const client = fakeClient([{ ok: true, channels: [] }]);
    await expect(resolveChannel(client, config(), "missing")).rejects.toThrow(
      'No channel named "missing"',
    );
  });

  describe("on Enterprise Grid", () => {
    // A Grid browser session is scoped to the org, so conversations.list is
    // refused outright and the boot payload is the only route to a name.
    const restricted = {
      "conversations.list": { __throw: new SlackApiError("enterprise_is_restricted") },
    };

    test("falls back to the boot payload, including private channels", async () => {
      const client = fakeClient([], {
        ...restricted,
        "client.userBoot": {
          ok: true,
          channels: [
            { id: "C1", name: "general" },
            { id: "C999", name: "private-updates", is_private: true },
          ],
        },
      });
      expect(await resolveChannel(client, config(), "#private-updates")).toBe("C999");
      expect(client.calls.map((c) => c.method)).toEqual([
        "conversations.list",
        "client.userBoot",
      ]);
    });

    test("caches the fallback result, so the restricted call is made once", async () => {
      const stored = config();
      const client = fakeClient([], {
        ...restricted,
        "client.userBoot": { ok: true, channels: [{ id: "C999", name: "alerts" }] },
      });
      await resolveChannel(client, stored, "alerts");
      expect((await readConfig())?.channel_cache).toEqual({ alerts: "C999" });

      const second = fakeClient([], restricted);
      expect(await resolveChannel(second, stored, "alerts")).toBe("C999");
      expect(second.calls).toHaveLength(0);
    });

    test("still reports an unknown channel clearly", async () => {
      const client = fakeClient([], {
        ...restricted,
        "client.userBoot": { ok: true, channels: [{ id: "C1", name: "general" }] },
      });
      await expect(resolveChannel(client, config(), "missing")).rejects.toThrow(
        'No channel named "missing"',
      );
    });

    test("does not mask an unrelated Slack error", async () => {
      const client = fakeClient([], {
        "conversations.list": { __throw: new SlackApiError("ratelimited") },
      });
      await expect(resolveChannel(client, config(), "alerts")).rejects.toThrow("ratelimited");
    });
  });
});

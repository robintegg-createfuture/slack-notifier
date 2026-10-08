import { describe, expect, test } from "bun:test";
import { captureSlackTokens, type CdpLike } from "../src/lib/auto-login.ts";

const SIGNED_IN_LOCAL_CONFIG = JSON.stringify({
  teams: {
    T111: {
      id: "T111",
      name: "Acme Corp",
      url: "https://acme.slack.com",
      token: "xoxc-one",
    },
  },
});

interface FakeOptions {
  localConfigAfterPoll?: number;
  cookies?: unknown[];
  supportedCookieMethods?: string[];
  pageUrl?: string | null;
}

/** A CDP connection stand-in, so capture policy is testable with no browser. */
function fakeSession(options: FakeOptions = {}): CdpLike & { polls: number } {
  const {
    localConfigAfterPoll = 0,
    cookies = [{ name: "d", value: "xoxd-token" }],
    supportedCookieMethods = ["Storage.getCookies"],
    pageUrl = "https://app.slack.com/client",
  } = options;

  let polls = 0;
  const session = {
    get polls() {
      return polls;
    },
    async send<T>(method: string): Promise<T> {
      if (method === "Target.getTargets") {
        const targetInfos = pageUrl
          ? [{ type: "page", url: pageUrl, targetId: "TARGET-1" }]
          : [];
        return { targetInfos } as T;
      }
      if (method === "Target.attachToTarget") {
        return { sessionId: "SESSION-1" } as T;
      }
      if (method === "Runtime.evaluate") {
        const value = polls++ >= localConfigAfterPoll ? SIGNED_IN_LOCAL_CONFIG : null;
        return { result: { value } } as T;
      }
      if (supportedCookieMethods.includes(method)) {
        return { cookies } as T;
      }
      throw new Error(`unsupported: ${method}`);
    },
  };
  return session;
}

const noWait = { sleep: async () => {}, pollIntervalMs: 0 };

describe("captureSlackTokens", () => {
  test("captures the cookie and every signed-in workspace", async () => {
    const result = await captureSlackTokens(fakeSession(), noWait);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.xoxd).toBe("xoxd-token");
    expect(result.workspaces).toHaveLength(1);
    expect(result.workspaces[0]?.xoxc).toBe("xoxc-one");
  });

  test("waits for the user to sign in", async () => {
    const session = fakeSession({ localConfigAfterPoll: 3 });
    const progress: string[] = [];
    const result = await captureSlackTokens(session, {
      ...noWait,
      onProgress: (m) => progress.push(m),
    });
    expect(result.ok).toBe(true);
    expect(session.polls).toBe(4);
    expect(progress[0]).toContain("Waiting for you to sign in");
  });

  test("falls back to Network.getCookies on older protocol builds", async () => {
    const result = await captureSlackTokens(
      fakeSession({ supportedCookieMethods: ["Network.getCookies"] }),
      noWait,
    );
    expect(result.ok).toBe(true);
  });

  test("reports a signed-in session with no readable cookie", async () => {
    const result = await captureSlackTokens(fakeSession({ cookies: [] }), noWait);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe("no_cookie");
  });

  test("times out when no Slack tab ever signs in", async () => {
    let clock = 0;
    const result = await captureSlackTokens(fakeSession({ pageUrl: null }), {
      ...noWait,
      timeoutMs: 100,
      now: () => (clock += 40),
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe("timeout");
  });
});

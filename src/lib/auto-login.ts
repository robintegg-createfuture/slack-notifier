import {
  extractWorkspacesFromLocalConfig,
  extractXoxdFromCookies,
  type CapturedWorkspace,
} from "./capture.ts";

/** The slice of a CDP connection capture needs, so tests can supply a fake. */
export interface CdpLike {
  send<T = any>(method: string, params?: object, sessionId?: string): Promise<T>;
}

export type CaptureResult =
  | { ok: true; xoxd: string; workspaces: CapturedWorkspace[] }
  | { ok: false; reason: "timeout" | "no_cookie"; message: string };

export interface CaptureOptions {
  timeoutMs?: number;
  pollIntervalMs?: number;
  onProgress?: (message: string) => void;
  sleep?: (ms: number) => Promise<void>;
  now?: () => number;
}

/**
 * Poll an attached CDP connection until Slack credentials are readable.
 *
 * Takes an established connection rather than opening one, so the capture
 * policy — what counts as a hit, when to give up — is testable with no browser
 * involved.
 */
export async function captureSlackTokens(
  session: CdpLike,
  options: CaptureOptions = {},
): Promise<CaptureResult> {
  const timeoutMs = options.timeoutMs ?? 300_000;
  const pollIntervalMs = options.pollIntervalMs ?? 2_000;
  const onProgress = options.onProgress ?? (() => {});
  const sleep = options.sleep ?? ((ms: number) => Bun.sleep(ms));
  const now = options.now ?? (() => Date.now());

  const deadline = now() + timeoutMs;
  let announcedWait = false;

  while (now() < deadline) {
    const workspaces = await readWorkspaces(session);
    if (workspaces.length > 0) {
      onProgress("Session detected — reading tokens…");
      const xoxd = await readSessionCookie(session);
      if (!xoxd) {
        return {
          ok: false,
          reason: "no_cookie",
          message:
            "Signed-in session found, but the `d` session cookie was not readable.\n" +
            "   Try again, leaving the Slack tab open until capture finishes.",
        };
      }
      return { ok: true, xoxd, workspaces };
    }

    if (!announcedWait) {
      announcedWait = true;
      onProgress("Waiting for you to sign in to Slack in the browser…");
    }
    await sleep(pollIntervalMs);
  }

  return {
    ok: false,
    reason: "timeout",
    message:
      "Timed out waiting for a signed-in Slack session.\n" +
      "   Run the command again and sign in, or raise --timeout.",
  };
}

async function readWorkspaces(session: CdpLike): Promise<CapturedWorkspace[]> {
  const targetSessionId = await attachToSlackPage(session);
  if (!targetSessionId) return [];

  try {
    const result = await session.send<{ result?: { value?: unknown } }>(
      "Runtime.evaluate",
      {
        expression: "window.localStorage.getItem('localConfig_v2')",
        returnByValue: true,
      },
      targetSessionId,
    );
    return extractWorkspacesFromLocalConfig(result?.result?.value);
  } catch {
    // The tab may be mid-navigation through an SSO provider. Try again later.
    return [];
  }
}

async function attachToSlackPage(session: CdpLike): Promise<string | null> {
  let targets: { targetInfos?: any[] };
  try {
    targets = await session.send("Target.getTargets");
  } catch {
    return null;
  }

  const page = (targets?.targetInfos ?? []).find(
    (target) =>
      target?.type === "page" &&
      typeof target?.url === "string" &&
      target.url.includes("slack.com"),
  );
  if (!page?.targetId) return null;

  try {
    const attached = await session.send<{ sessionId?: string }>("Target.attachToTarget", {
      targetId: page.targetId,
      flatten: true,
    });
    return attached?.sessionId ?? null;
  } catch {
    return null;
  }
}

/**
 * Read the `d` cookie from the browser's whole cookie store.
 *
 * `Storage.getCookies` is the browser-wide call. `Network.getCookies` returns
 * cookies for the current URL only — which happens to work while the tab sits
 * on Slack, and silently returns nothing when sign-in has parked it on an SSO
 * origin. It stays as a fallback for older protocol builds.
 */
async function readSessionCookie(session: CdpLike): Promise<string | null> {
  for (const method of ["Storage.getCookies", "Network.getCookies"] as const) {
    try {
      const result = await session.send<{ cookies?: unknown }>(method);
      const found = extractXoxdFromCookies(result?.cookies ?? []);
      if (found) return found;
    } catch {
      // Method unsupported on this build; fall through to the next.
    }
  }
  return null;
}

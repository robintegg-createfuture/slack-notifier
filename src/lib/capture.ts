/**
 * Pure extractors for Slack browser-session credentials.
 *
 * Slack's web client authenticates with a pair: an `xoxc-*` API token sent in
 * request bodies, and an `xoxd-*` value in the `d` cookie. The cookie is
 * HttpOnly, so page JavaScript cannot read it — it has to come from the
 * browser's own cookie store, which is why capture drives CDP rather than
 * injecting a script.
 *
 * These functions carry all the parsing rules and touch neither a browser nor
 * the network, so they are testable against fixtures. `localConfig_v2` is Slack
 * client internals and may change shape without notice, so everything here
 * parses defensively and returns nothing rather than throwing.
 */

export interface CapturedWorkspace {
  workspace_url: string;
  workspace_name: string;
  workspace_id: string;
  user_id?: string;
  xoxc: string;
}

interface CdpCookie {
  name?: unknown;
  value?: unknown;
  domain?: unknown;
}

/** Find the `d` session cookie among the browser's whole cookie store. */
export function extractXoxdFromCookies(cookies: unknown): string | null {
  if (!Array.isArray(cookies)) return null;
  for (const raw of cookies as CdpCookie[]) {
    if (!raw || typeof raw !== "object") continue;
    if (raw.name !== "d") continue;
    const value = raw.value;
    if (typeof value !== "string") continue;
    const decoded = safeDecode(value);
    if (decoded.startsWith("xoxd-")) return decoded;
  }
  return null;
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/**
 * Only `https://` URLs on a `slack.com` host are ever paired with the session
 * cookie. A URL read out of the browser that points anywhere else is refused,
 * so a hostile or stale entry cannot redirect credentials off-workspace.
 */
export function isSafeSlackUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0) return false;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.protocol !== "https:") return false;
  return url.hostname === "slack.com" || url.hostname.endsWith(".slack.com");
}

/** Strip any trailing slash so request URLs join cleanly. */
export function normaliseWorkspaceUrl(value: string): string {
  return value.replace(/\/+$/, "");
}

/**
 * Read every workspace the user is signed into out of `localConfig_v2`.
 *
 * One sign-in therefore enrols workspaces the user never opened this session.
 * The raw value is whatever `localStorage.getItem` returned — a JSON string, or
 * already-parsed JSON — so both are accepted.
 */
export function extractWorkspacesFromLocalConfig(raw: unknown): CapturedWorkspace[] {
  const config = coerceJson(raw);
  if (!config || typeof config !== "object") return [];

  const teams = (config as Record<string, unknown>).teams;
  if (!teams || typeof teams !== "object") return [];

  const found: CapturedWorkspace[] = [];
  for (const [key, value] of Object.entries(teams as Record<string, unknown>)) {
    const team = value as Record<string, unknown> | null;
    if (!team || typeof team !== "object") continue;

    const xoxc = team.token;
    if (typeof xoxc !== "string" || !xoxc.startsWith("xoxc-")) continue;

    const url = team.url ?? team.domain;
    if (!isSafeSlackUrl(url)) continue;

    const workspaceId = typeof team.id === "string" ? team.id : key;
    found.push({
      workspace_url: normaliseWorkspaceUrl(url),
      workspace_name: typeof team.name === "string" ? team.name : workspaceId,
      workspace_id: workspaceId,
      user_id: typeof team.user_id === "string" ? team.user_id : undefined,
      xoxc,
    });
  }
  return found;
}

function coerceJson(raw: unknown): unknown {
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

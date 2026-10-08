import type { StoredCredentials } from "./config.ts";

/** Slack error codes that mean the stored credentials are no longer usable. */
const AUTH_ERROR_CODES = new Set([
  "invalid_auth",
  "token_expired",
  "token_revoked",
  "not_authed",
  "account_inactive",
]);

/**
 * Methods that only read. A method that is not on this list — including any new
 * one — is treated as a write, because a write that failed with a 5xx or a
 * network error may still have been applied and retrying could double-post.
 */
const READ_METHODS = new Set(["auth.test", "conversations.list"]);

const MAX_RETRIES = 3;
const MAX_WAIT_MS = 60_000;

export class SlackAuthError extends Error {
  readonly code: string;
  constructor(code: string) {
    super(
      `Slack rejected the stored credentials (${code}).\n` +
        `   ${meaningOf(code)}\n` +
        `   To fix: slack-notifier login`,
    );
    this.name = "SlackAuthError";
    this.code = code;
  }
}

export class SlackApiError extends Error {
  readonly code: string;
  constructor(code: string) {
    super(`Slack returned an error: ${code}`);
    this.name = "SlackApiError";
    this.code = code;
  }
}

function meaningOf(code: string): string {
  switch (code) {
    case "token_expired":
      return "The browser session behind these tokens has expired.";
    case "token_revoked":
      return "The token was revoked by you or a workspace admin.";
    case "account_inactive":
      return "The account these tokens belong to is deactivated.";
    case "not_authed":
      return "No credentials reached Slack.";
    default:
      return "The token was not accepted.";
  }
}

export interface SlackClientOptions {
  /** Injectable for tests so retry policy can be exercised without waiting. */
  sleep?: (ms: number) => Promise<void>;
  fetch?: typeof fetch;
}

export interface SlackOk {
  ok: true;
  [key: string]: unknown;
}

/**
 * Talks to a workspace with browser-session credentials.
 *
 * These are not bearer tokens: they go to the workspace host rather than
 * `slack.com/api`, as form data carrying the `xoxc` token, with the `xoxd`
 * value supplied as the `d` cookie.
 */
export class SlackClient {
  private readonly workspaceUrl: string;
  private readonly credentials: StoredCredentials;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly fetchImpl: typeof fetch;

  constructor(
    workspaceUrl: string,
    credentials: StoredCredentials,
    options: SlackClientOptions = {},
  ) {
    this.workspaceUrl = workspaceUrl.replace(/\/+$/, "");
    this.credentials = credentials;
    this.sleep = options.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    this.fetchImpl = options.fetch ?? fetch;
  }

  async request(method: string, params: Record<string, string> = {}): Promise<SlackOk> {
    const isRead = READ_METHODS.has(method);
    let lastError: unknown;

    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      let response: Response;
      try {
        response = await this.attempt(method, params);
      } catch (err) {
        // The request may or may not have reached Slack. Only a read is safe
        // to repeat.
        if (!isRead || attempt === MAX_RETRIES) throw err;
        lastError = err;
        await this.sleep(backoffMs(attempt));
        continue;
      }

      if (response.status === 429) {
        // Slack rejected the call outright, so repeating it cannot apply it
        // twice — this is safe for writes too.
        if (attempt === MAX_RETRIES) {
          throw new Error(`Slack rate-limited ${method} and did not recover.`);
        }
        await this.sleep(retryAfterMs(response, attempt));
        continue;
      }

      if (response.status >= 500) {
        if (!isRead || attempt === MAX_RETRIES) {
          throw new Error(`Slack returned HTTP ${response.status} for ${method}.`);
        }
        lastError = new Error(`HTTP ${response.status}`);
        await this.sleep(backoffMs(attempt));
        continue;
      }

      if (!response.ok) {
        throw new Error(`Slack returned HTTP ${response.status} for ${method}.`);
      }

      let body: Record<string, unknown>;
      try {
        body = (await response.json()) as Record<string, unknown>;
      } catch {
        throw new Error(`Slack returned an unreadable response for ${method}.`);
      }

      if (body.ok === true) return body as SlackOk;

      const code = typeof body.error === "string" ? body.error : "unknown_error";
      if (AUTH_ERROR_CODES.has(code)) throw new SlackAuthError(code);
      throw new SlackApiError(code);
    }

    throw lastError instanceof Error ? lastError : new Error(`${method} failed.`);
  }

  private attempt(method: string, params: Record<string, string>): Promise<Response> {
    const form = new FormData();
    form.set("token", this.credentials.xoxc);
    for (const [key, value] of Object.entries(params)) form.set(key, value);

    return this.fetchImpl(`${this.workspaceUrl}/api/${method}`, {
      method: "POST",
      headers: { Cookie: `d=${encodeURIComponent(this.credentials.xoxd)}` },
      body: form,
    });
  }

  authTest(): Promise<SlackOk> {
    return this.request("auth.test");
  }

  postMessage(channel: string, text: string): Promise<SlackOk> {
    return this.request("chat.postMessage", { channel, text });
  }
}

function backoffMs(attempt: number): number {
  const base = Math.min(1000 * 2 ** attempt, MAX_WAIT_MS);
  return base + Math.floor(Math.random() * 250);
}

function retryAfterMs(response: Response, attempt: number): number {
  const header = response.headers.get("retry-after");
  const seconds = header === null ? NaN : Number(header);
  if (Number.isFinite(seconds) && seconds > 0) {
    return Math.min(seconds * 1000, MAX_WAIT_MS);
  }
  return backoffMs(attempt);
}

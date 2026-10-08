import { mkdir, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { browserProfileDir } from "./config.ts";

/**
 * Drive a real browser over the Chrome DevTools Protocol.
 *
 * The `d` cookie holding the `xoxd` value is HttpOnly, so page JavaScript
 * cannot read it. The only way to get it without asking the user to copy
 * things out of DevTools is to read the browser's own cookie store, which is
 * what CDP gives us.
 *
 * Nothing is downloaded and no browser is installed — this drives the one the
 * user already has.
 */

const CANDIDATE_BROWSERS = [
  // macOS
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
  // Linux
  "/usr/bin/google-chrome",
  "/usr/bin/google-chrome-stable",
  "/usr/bin/chromium",
  "/usr/bin/chromium-browser",
  "/usr/bin/microsoft-edge",
  "/usr/bin/brave-browser",
  // Windows
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
];

export async function findBrowser(): Promise<string | null> {
  const override = process.env.SLACK_NOTIFIER_BROWSER;
  if (override) return override;
  for (const candidate of CANDIDATE_BROWSERS) {
    if (await Bun.file(candidate).exists()) return candidate;
  }
  return null;
}

interface PendingCall {
  resolve: (value: any) => void;
  reject: (reason: Error) => void;
}

/** One CDP connection, with optional per-target sessions multiplexed over it. */
export class CdpSession {
  private readonly socket: WebSocket;
  private readonly pending = new Map<number, PendingCall>();
  private nextId = 1;

  private constructor(socket: WebSocket) {
    this.socket = socket;
    this.socket.addEventListener("message", (event) => this.onMessage(event));
    this.socket.addEventListener("close", () => {
      for (const call of this.pending.values()) {
        call.reject(new Error("The browser connection closed."));
      }
      this.pending.clear();
    });
  }

  static connect(url: string, timeoutMs = 15_000): Promise<CdpSession> {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      const timer = setTimeout(() => {
        socket.close();
        reject(new Error("Timed out connecting to the browser."));
      }, timeoutMs);

      socket.addEventListener("open", () => {
        clearTimeout(timer);
        resolve(new CdpSession(socket));
      });
      socket.addEventListener("error", () => {
        clearTimeout(timer);
        reject(new Error("Could not connect to the browser's debugging port."));
      });
    });
  }

  private onMessage(event: MessageEvent): void {
    let payload: any;
    try {
      payload = JSON.parse(String(event.data));
    } catch {
      return;
    }
    if (typeof payload?.id !== "number") return;
    const call = this.pending.get(payload.id);
    if (!call) return;
    this.pending.delete(payload.id);
    if (payload.error) {
      call.reject(new Error(payload.error.message ?? "CDP call failed."));
      return;
    }
    call.resolve(payload.result);
  }

  send<T = any>(method: string, params: object = {}, sessionId?: string): Promise<T> {
    const id = this.nextId++;
    const message: Record<string, unknown> = { id, method, params };
    if (sessionId) message.sessionId = sessionId;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      try {
        this.socket.send(JSON.stringify(message));
      } catch (err) {
        this.pending.delete(id);
        reject(err instanceof Error ? err : new Error(String(err)));
      }
    });
  }

  close(): void {
    try {
      this.socket.close();
    } catch {
      // Already gone; nothing to do.
    }
  }
}

export interface LaunchedBrowser {
  session: CdpSession;
  /** Terminates the browser. Safe to call more than once. */
  close(): Promise<void>;
}

export interface LaunchOptions {
  headless: boolean;
  startUrl: string;
}

/**
 * Launch the browser against our own profile directory and attach to it.
 *
 * The dedicated profile is not a preference: since Chrome 136 the browser
 * refuses remote debugging against the default profile, so the user's existing
 * session cannot be reused. The profile persists, so only the first run needs
 * interaction.
 */
export async function launchBrowser(options: LaunchOptions): Promise<LaunchedBrowser> {
  const binary = await findBrowser();
  if (!binary) {
    throw new Error(
      "No Chrome, Edge, Chromium or Brave installation was found.\n" +
        "   Install one, or set SLACK_NOTIFIER_BROWSER to the browser's path.",
    );
  }

  const profile = browserProfileDir();
  await mkdir(profile, { recursive: true, mode: 0o700 });
  // A stale port file would otherwise be read as this run's port.
  await rm(join(profile, "DevToolsActivePort"), { force: true });

  const args = [
    binary,
    "--remote-debugging-port=0",
    `--user-data-dir=${profile}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-background-networking",
  ];
  if (options.headless) args.push("--headless=new");
  args.push(options.startUrl);

  const child = Bun.spawn(args, { stdout: "ignore", stderr: "ignore" });

  let closed = false;
  const close = async (): Promise<void> => {
    if (closed) return;
    closed = true;
    try {
      child.kill();
      await child.exited;
    } catch {
      // The browser already exited.
    }
  };

  try {
    const port = await waitForDebuggingPort(profile);
    const wsUrl = await readWebSocketUrl(port);
    const session = await CdpSession.connect(wsUrl);
    return {
      session,
      close: async () => {
        session.close();
        await close();
      },
    };
  } catch (err) {
    await close();
    throw err;
  }
}

/** Chrome writes the port it actually bound to into the profile directory. */
async function waitForDebuggingPort(profile: string, timeoutMs = 30_000): Promise<number> {
  const path = join(profile, "DevToolsActivePort");
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const contents = await readFile(path, "utf8");
      const port = Number(contents.split("\n")[0]);
      if (Number.isInteger(port) && port > 0) return port;
    } catch {
      // Not written yet.
    }
    await Bun.sleep(100);
  }
  throw new Error("The browser did not open a debugging port.");
}

async function readWebSocketUrl(port: number): Promise<string> {
  const response = await fetch(`http://127.0.0.1:${port}/json/version`);
  const body = (await response.json()) as { webSocketDebuggerUrl?: string };
  if (!body.webSocketDebuggerUrl) {
    throw new Error("The browser did not report a debugging endpoint.");
  }
  return body.webSocketDebuggerUrl;
}

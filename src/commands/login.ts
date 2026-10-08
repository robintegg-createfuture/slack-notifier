import { launchBrowser } from "../lib/browser.ts";
import { captureSlackTokens } from "../lib/auto-login.ts";
import { SlackClient } from "../lib/slack.ts";
import { writeConfig, type StoredConfig } from "../lib/config.ts";
import { flagBoolean, flagString, type ParsedArgs } from "../lib/args.ts";
import type { CapturedWorkspace } from "../lib/capture.ts";

const START_URL = "https://app.slack.com/client";

export async function login(args: ParsedArgs): Promise<number> {
  const headless = flagBoolean(args.flags, "headless");
  const wanted = flagString(args.flags, "workspace");
  const timeoutSeconds = Number(flagString(args.flags, "timeout") ?? 300);
  if (!Number.isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
    console.error("--timeout must be a positive number of seconds.");
    return 1;
  }

  console.error(headless ? "Refreshing tokens headlessly…" : "Opening a browser…");

  const browser = await launchBrowser({ headless, startUrl: START_URL });

  // While the browser is open it exposes a debugging port on loopback that any
  // local process could attach to, so it is closed the moment capture finishes
  // — including on Ctrl-C.
  let released = false;
  const release = async () => {
    if (released) return;
    released = true;
    await browser.close();
  };
  const onSignal = () => {
    void release().then(() => process.exit(130));
  };
  process.on("SIGINT", onSignal);
  process.on("SIGTERM", onSignal);

  let captured;
  try {
    captured = await captureSlackTokens(browser.session, {
      timeoutMs: timeoutSeconds * 1000,
      onProgress: (message) => console.error(message),
    });
  } finally {
    process.off("SIGINT", onSignal);
    process.off("SIGTERM", onSignal);
    await release();
  }

  if (!captured.ok) {
    if (captured.reason === "timeout" && headless) {
      console.error(
        "Timed out with no visible window.\n" +
          "   --headless only works once a normal `slack-notifier login` has signed the profile in.",
      );
      return 1;
    }
    console.error(captured.message);
    return 1;
  }

  const chosen = chooseWorkspace(captured.workspaces, wanted);
  if (!chosen) {
    console.error(
      `No signed-in workspace matched "${wanted}".\n` +
        `   Found: ${captured.workspaces.map((w) => w.workspace_name).join(", ")}`,
    );
    return 1;
  }

  const client = new SlackClient(chosen.workspace_url, {
    xoxc: chosen.xoxc,
    xoxd: captured.xoxd,
  });

  // Verify before storing, so a stale token fails now rather than at first use.
  const identity = await client.authTest();

  const config: StoredConfig = {
    workspace_url: chosen.workspace_url,
    workspace_name:
      typeof identity.team === "string" ? identity.team : chosen.workspace_name,
    workspace_id:
      typeof identity.team_id === "string" ? identity.team_id : chosen.workspace_id,
    user_id: typeof identity.user_id === "string" ? identity.user_id : chosen.user_id,
    credentials: { xoxc: chosen.xoxc, xoxd: captured.xoxd },
    channel_cache: {},
  };
  await writeConfig(config);

  console.log(`Signed in to ${config.workspace_name} as ${identity.user ?? "unknown"}.`);
  if (captured.workspaces.length > 1 && !wanted) {
    const others = captured.workspaces
      .filter((w) => w.workspace_id !== chosen.workspace_id)
      .map((w) => w.workspace_name);
    console.error(
      `Also signed into: ${others.join(", ")}. ` +
        "Use --workspace <name> to notify one of those instead.",
    );
  }
  return 0;
}

export function chooseWorkspace(
  workspaces: CapturedWorkspace[],
  wanted: string | undefined,
): CapturedWorkspace | null {
  if (workspaces.length === 0) return null;
  if (!wanted) return workspaces[0]!;

  const needle = wanted.toLowerCase();
  return (
    workspaces.find(
      (w) =>
        w.workspace_name.toLowerCase() === needle ||
        w.workspace_id.toLowerCase() === needle ||
        w.workspace_url.toLowerCase().includes(needle),
    ) ?? null
  );
}

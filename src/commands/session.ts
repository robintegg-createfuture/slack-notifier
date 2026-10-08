import { clearConfig, readConfig } from "../lib/config.ts";
import { SlackClient } from "../lib/slack.ts";
import { flagBoolean, type ParsedArgs } from "../lib/args.ts";

export async function whoami(args: ParsedArgs): Promise<number> {
  const json = flagBoolean(args.flags, "json");

  const config = await readConfig();
  if (!config) {
    console.error("Not signed in.\n   To fix: slack-notifier login");
    return 1;
  }

  const client = new SlackClient(config.workspace_url, config.credentials);
  // The call to Slack is the point: there is no offline mode.
  const identity = await client.authTest();

  if (json) {
    console.log(
      JSON.stringify({
        status: "ok",
        workspace_name: config.workspace_name,
        workspace_id: config.workspace_id,
        workspace_url: config.workspace_url,
        user: identity.user,
        user_id: identity.user_id,
      }),
    );
  } else {
    console.log(config.workspace_name);
    console.log(`  URL: ${config.workspace_url}`);
    console.log(`  User: ${identity.user} (${identity.user_id})`);
    console.log("  Status: verified");
  }
  return 0;
}

export async function logout(args: ParsedArgs): Promise<number> {
  const keepBrowserSession = flagBoolean(args.flags, "keep-browser-session");
  await clearConfig(keepBrowserSession);

  console.log(
    keepBrowserSession
      ? "Credentials removed. The browser profile was kept, so it can still re-mint tokens."
      : "Credentials and the browser profile were removed.",
  );
  return 0;
}

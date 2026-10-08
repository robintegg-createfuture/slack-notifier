import { readConfig } from "../lib/config.ts";
import { SlackClient } from "../lib/slack.ts";
import { resolveChannel } from "../lib/channels.ts";
import { flagBoolean, flagString, type ParsedArgs } from "../lib/args.ts";

export async function send(args: ParsedArgs): Promise<number> {
  const json = flagBoolean(args.flags, "json");

  const config = await readConfig();
  if (!config) {
    console.error("Not signed in.\n   To fix: slack-notifier login");
    return 1;
  }

  const channelInput = flagString(args.flags, "channel");
  if (!channelInput) {
    console.error("--channel is required.");
    return 1;
  }

  const message = await resolveMessage(args);
  if (message === null) {
    console.error("--message is required, or pipe the message on stdin.");
    return 1;
  }

  const client = new SlackClient(config.workspace_url, config.credentials);
  const channelId = await resolveChannel(client, config, channelInput);
  const result = await client.postMessage(channelId, message);

  if (json) {
    console.log(
      JSON.stringify({
        ok: true,
        channel: result.channel ?? channelId,
        ts: result.ts,
        permalink: permalinkFor(config.workspace_url, channelId, result.ts),
      }),
    );
  } else {
    console.log(`Sent to ${channelInput}.`);
  }
  return 0;
}

async function resolveMessage(args: ParsedArgs): Promise<string | null> {
  const flag = flagString(args.flags, "message");
  if (flag !== undefined && flag.length > 0) return flag;

  if (!process.stdin.isTTY) {
    const piped = (await Bun.stdin.text()).trim();
    if (piped.length > 0) return piped;
  }
  return null;
}

function permalinkFor(workspaceUrl: string, channelId: string, ts: unknown): string | null {
  if (typeof ts !== "string") return null;
  return `${workspaceUrl}/archives/${channelId}/p${ts.replace(".", "")}`;
}

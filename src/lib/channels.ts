import type { SlackClient } from "./slack.ts";
import type { StoredConfig } from "./config.ts";
import { writeConfig } from "./config.ts";

/** Accepts `#alerts`, `alerts`, a channel ID, or a Slack channel link. */
export function normaliseChannelInput(input: string): string {
  const trimmed = input.trim();
  const fromLink = trimmed.match(/^https:\/\/[^/]*slack\.com\/archives\/([A-Z0-9]+)/i);
  if (fromLink?.[1]) return fromLink[1];
  return trimmed.replace(/^#/, "");
}

/** Slack channel IDs start with C (public), G (private) or D (DM). */
export function looksLikeChannelId(value: string): boolean {
  return /^[CGD][A-Z0-9]{6,}$/.test(value);
}

/**
 * Turn a channel name into the ID `chat.postMessage` needs.
 *
 * Resolved IDs are cached in the config so the common path — notifying the same
 * channel over and over — costs one API call rather than a full channel list.
 */
export async function resolveChannel(
  client: SlackClient,
  config: StoredConfig,
  input: string,
): Promise<string> {
  const name = normaliseChannelInput(input);
  if (looksLikeChannelId(name)) return name;

  const cached = config.channel_cache[name.toLowerCase()];
  if (cached) return cached;

  const id = await lookupChannelId(client, name);
  if (!id) {
    throw new Error(
      `No channel named "${name}" is visible to this account.\n` +
        "   Check the spelling, and that the account is a member of private channels.",
    );
  }

  config.channel_cache[name.toLowerCase()] = id;
  await writeConfig(config);
  return id;
}

async function lookupChannelId(client: SlackClient, name: string): Promise<string | null> {
  const wanted = name.toLowerCase();
  let cursor = "";

  do {
    const params: Record<string, string> = {
      types: "public_channel,private_channel",
      exclude_archived: "true",
      limit: "1000",
    };
    if (cursor) params.cursor = cursor;

    const body = await client.request("conversations.list", params);
    const channels = (body.channels ?? []) as Array<{ id?: string; name?: string }>;
    for (const channel of channels) {
      if (channel?.name?.toLowerCase() === wanted && channel.id) return channel.id;
    }

    const metadata = body.response_metadata as { next_cursor?: string } | undefined;
    cursor = metadata?.next_cursor ?? "";
  } while (cursor);

  return null;
}

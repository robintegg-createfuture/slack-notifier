import { homedir } from "node:os";
import { join } from "node:path";
import { chmod, mkdir, rm } from "node:fs/promises";

export interface StoredCredentials {
  /** The `d` cookie value from a signed-in Slack web session. */
  xoxd: string;
  /** The API token Slack's web client sends in request bodies. */
  xoxc: string;
}

export interface StoredConfig {
  workspace_url: string;
  workspace_name: string;
  workspace_id: string;
  user_id?: string;
  credentials: StoredCredentials;
  /** Channel name (without `#`) to channel ID, cached from conversations.list. */
  channel_cache: Record<string, string>;
}

export function configDir(): string {
  const override = process.env.SLACK_NOTIFIER_CONFIG_DIR;
  if (override) return override;
  return join(homedir(), ".config", "slack-notifier");
}

export function configPath(): string {
  return join(configDir(), "config.json");
}

export function browserProfileDir(): string {
  return join(configDir(), "browser-profile");
}

export async function readConfig(): Promise<StoredConfig | null> {
  const file = Bun.file(configPath());
  if (!(await file.exists())) return null;
  try {
    const parsed = JSON.parse(await file.text()) as StoredConfig;
    if (!parsed?.credentials?.xoxc || !parsed?.credentials?.xoxd) return null;
    parsed.channel_cache ??= {};
    return parsed;
  } catch {
    return null;
  }
}

export async function writeConfig(config: StoredConfig): Promise<void> {
  const dir = configDir();
  await mkdir(dir, { recursive: true, mode: 0o700 });
  await chmod(dir, 0o700);
  const path = configPath();
  await Bun.write(path, JSON.stringify(config, null, 2) + "\n");
  await chmod(path, 0o600);
}

/**
 * Remove every stored credential.
 *
 * The browser profile is itself a credential store: while it exists, a login
 * can silently re-mint working tokens with no prompt. Deleting it is the point
 * of logging out, so it goes unless the caller explicitly keeps it.
 */
export async function clearConfig(keepBrowserProfile: boolean): Promise<void> {
  await rm(configPath(), { force: true });
  if (!keepBrowserProfile) {
    await rm(browserProfileDir(), { recursive: true, force: true });
  }
}

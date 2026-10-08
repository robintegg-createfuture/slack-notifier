import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdtemp, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  browserProfileDir,
  clearConfig,
  configPath,
  readConfig,
  writeConfig,
  type StoredConfig,
} from "../src/lib/config.ts";
import { mkdir } from "node:fs/promises";

const CONFIG: StoredConfig = {
  workspace_url: "https://acme.slack.com",
  workspace_name: "Acme Corp",
  workspace_id: "T111",
  user_id: "U111",
  credentials: { xoxc: "xoxc-one", xoxd: "xoxd-one" },
  channel_cache: { alerts: "C123" },
};

let dir: string;
const original = process.env.SLACK_NOTIFIER_CONFIG_DIR;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), "slack-notifier-test-"));
  process.env.SLACK_NOTIFIER_CONFIG_DIR = dir;
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
  if (original === undefined) delete process.env.SLACK_NOTIFIER_CONFIG_DIR;
  else process.env.SLACK_NOTIFIER_CONFIG_DIR = original;
});

describe("config storage", () => {
  test("round-trips a stored config", async () => {
    await writeConfig(CONFIG);
    expect(await readConfig()).toEqual(CONFIG);
  });

  test("keeps credentials unreadable by other users", async () => {
    await writeConfig(CONFIG);
    expect((await stat(configPath())).mode & 0o777).toBe(0o600);
    expect((await stat(dir)).mode & 0o777).toBe(0o700);
  });

  test("reports no config rather than throwing when nothing is stored", async () => {
    expect(await readConfig()).toBeNull();
  });

  test("treats a corrupt or incomplete config as absent", async () => {
    await Bun.write(configPath(), "{ not json");
    expect(await readConfig()).toBeNull();
    await Bun.write(configPath(), JSON.stringify({ workspace_url: "x" }));
    expect(await readConfig()).toBeNull();
  });

  test("logout removes the browser profile, which can re-mint tokens", async () => {
    await writeConfig(CONFIG);
    await mkdir(browserProfileDir(), { recursive: true });
    await clearConfig(false);
    expect(await readConfig()).toBeNull();
    expect(await Bun.file(join(browserProfileDir(), "x")).exists()).toBe(false);
    await expect(stat(browserProfileDir())).rejects.toThrow();
  });

  test("logout can keep the browser profile when asked", async () => {
    await writeConfig(CONFIG);
    await mkdir(browserProfileDir(), { recursive: true });
    await clearConfig(true);
    expect((await stat(browserProfileDir())).isDirectory()).toBe(true);
  });
});

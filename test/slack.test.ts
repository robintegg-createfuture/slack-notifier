import { describe, expect, test } from "bun:test";
import { SlackApiError, SlackAuthError, SlackClient } from "../src/lib/slack.ts";

const CREDENTIALS = { xoxc: "xoxc-token", xoxd: "xoxd-value/with=padding" };

function client(
  responses: Array<Response | (() => Response | Promise<Response>)>,
  calls: Request[] = [],
) {
  let index = 0;
  const fetchImpl = (async (input: any, init?: any) => {
    calls.push(new Request(input, init));
    const next = responses[Math.min(index++, responses.length - 1)]!;
    return typeof next === "function" ? await next() : next;
  }) as unknown as typeof fetch;

  return new SlackClient("https://acme.slack.com/", CREDENTIALS, {
    fetch: fetchImpl,
    sleep: async () => {},
  });
}

function ok(body: object = {}): Response {
  return Response.json({ ok: true, ...body });
}

describe("SlackClient.request", () => {
  test("posts to the workspace host with the token and the d cookie", async () => {
    const calls: Request[] = [];
    await client([ok({ user: "alice" })], calls).authTest();

    const call = calls[0]!;
    expect(call.url).toBe("https://acme.slack.com/api/auth.test");
    expect(call.method).toBe("POST");
    expect(call.headers.get("cookie")).toBe("d=xoxd-value%2Fwith%3Dpadding");
    const form = await call.formData();
    expect(form.get("token")).toBe("xoxc-token");
  });

  test("sends channel and text for a message", async () => {
    const calls: Request[] = [];
    await client([ok({ ts: "1.2" })], calls).postMessage("C123", "hello");
    const form = await calls[0]!.formData();
    expect(form.get("channel")).toBe("C123");
    expect(form.get("text")).toBe("hello");
  });

  test("classifies a refused credential so the fix is actionable", async () => {
    const promise = client([Response.json({ ok: false, error: "token_expired" })]).authTest();
    await expect(promise).rejects.toBeInstanceOf(SlackAuthError);
    await expect(promise).rejects.toThrow("slack-notifier login");
  });

  test("passes other Slack errors through unchanged", async () => {
    const promise = client([Response.json({ ok: false, error: "channel_not_found" })]).authTest();
    await expect(promise).rejects.toBeInstanceOf(SlackApiError);
  });

  test("retries a read after a server error", async () => {
    const calls: Request[] = [];
    const body = await client(
      [new Response("boom", { status: 503 }), ok({ user: "alice" })],
      calls,
    ).authTest();
    expect(body.user).toBe("alice");
    expect(calls).toHaveLength(2);
  });

  test("never retries a message after a server error, so it cannot double-post", async () => {
    const calls: Request[] = [];
    const promise = client([new Response("boom", { status: 503 })], calls).postMessage(
      "C123",
      "hello",
    );
    await expect(promise).rejects.toThrow("HTTP 503");
    expect(calls).toHaveLength(1);
  });

  test("never retries a message after a network error", async () => {
    const calls: Request[] = [];
    const promise = client(
      [
        () => {
          throw new Error("network down");
        },
      ],
      calls,
    ).postMessage("C123", "hello");
    await expect(promise).rejects.toThrow("network down");
    expect(calls).toHaveLength(1);
  });

  test("retries a rate-limited message, because Slack rejected it outright", async () => {
    const calls: Request[] = [];
    const body = await client(
      [new Response("", { status: 429, headers: { "retry-after": "1" } }), ok({ ts: "1.2" })],
      calls,
    ).postMessage("C123", "hello");
    expect(body.ts).toBe("1.2");
    expect(calls).toHaveLength(2);
  });
});

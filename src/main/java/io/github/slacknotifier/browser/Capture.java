package io.github.slacknotifier.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class Capture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Capture() {}

    public record Workspace(String url, String name, String id, String userId, String token) {
        @Override public String toString() { return "Workspace[" + name + ", " + id + "]"; }
    }

    public record Result(String cookie, List<Workspace> workspaces, String reason, String message) {
        public boolean ok() { return reason == null; }
        @Override public String toString() { return "Capture.Result[" + (ok() ? "ok" : reason) + "]"; }
    }

    @FunctionalInterface public interface Session {
        JsonNode call(String method, Map<String, ?> params, String sessionId) throws Exception;
    }
    @FunctionalInterface public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public static Result capture(Session session, long timeoutMs, Consumer<String> progress)
        throws Exception {
        return capture(session, timeoutMs, 2_000, progress, Thread::sleep, System::currentTimeMillis);
    }

    public static Result capture(Session session, long timeoutMs, long pollMs,
        Consumer<String> progress, Sleeper sleeper, LongSupplier clock) throws Exception {
        long start = clock.getAsLong();
        long deadline = timeoutMs > Long.MAX_VALUE - start ? Long.MAX_VALUE : start + timeoutMs;
        boolean announced = false;
        while (clock.getAsLong() < deadline) {
            List<Workspace> workspaces = readWorkspaces(session);
            if (!workspaces.isEmpty()) {
                progress.accept("Session detected — reading tokens…");
                String cookie = readCookie(session);
                if (cookie == null) {
                    return new Result(null, List.of(), "no_cookie",
                        "Signed-in session found, but the `d` session cookie was not readable.\n" +
                        "   Try again, leaving the Slack tab open until capture finishes.");
                }
                return new Result(cookie, workspaces, null, null);
            }
            if (!announced) {
                progress.accept("Waiting for you to sign in to Slack in the browser…");
                announced = true;
            }
            sleeper.sleep(pollMs);
        }
        return new Result(null, List.of(), "timeout",
            "Timed out waiting for a signed-in Slack session.\n" +
            "   Run the command again and sign in, or raise --timeout.");
    }

    private static List<Workspace> readWorkspaces(Session session) throws InterruptedException {
        try {
            JsonNode targets = session.call("Target.getTargets", Map.of(), null).path("targetInfos");
            for (JsonNode target : targets) {
                if (!"page".equals(target.path("type").asText()) ||
                    !safeSlackUrl(target.path("url").asText())) continue;
                String targetId = target.path("targetId").asText();
                if (targetId.isEmpty()) continue;
                String id = session.call("Target.attachToTarget",
                    Map.of("targetId", targetId, "flatten", true), null).path("sessionId").asText();
                if (id.isEmpty()) continue;
                JsonNode raw = session.call("Runtime.evaluate", Map.of(
                    "expression", "window.localStorage.getItem('localConfig_v2')",
                    "returnByValue", true), id).path("result").path("value");
                return extractWorkspaces(raw);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception ignored) {
            // Slack may be navigating through an SSO provider.
        }
        return List.of();
    }

    private static String readCookie(Session session) throws InterruptedException {
        for (String method : List.of("Storage.getCookies", "Network.getCookies")) {
            try {
                String found = extractCookie(session.call(method, Map.of(), null).path("cookies"));
                if (found != null) return found;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception ignored) {
                // Older browser builds may not support Storage.getCookies.
            }
        }
        return null;
    }

    public static String extractCookie(JsonNode cookies) {
        if (cookies == null || !cookies.isArray()) return null;
        for (JsonNode item : cookies) {
            if (!"d".equals(item.path("name").asText()) || !item.path("value").isTextual()) continue;
            String value = item.path("value").asText();
            try {
                value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // Keep the original value if it is not valid percent encoding.
            }
            if (value.startsWith("xoxd-")) return value;
        }
        return null;
    }

    public static boolean safeSlackUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
                return false;
            }

            host = host.toLowerCase(Locale.ROOT);
            return host.equals("slack.com") || host.endsWith(".slack.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Workspace chooseWorkspace(List<Workspace> workspaces, String wanted) {
        if (workspaces.isEmpty()) return null;
        if (wanted == null || wanted.isEmpty()) return workspaces.getFirst();
        String needle = wanted.toLowerCase(Locale.ROOT);
        return workspaces.stream().filter(workspace ->
            workspace.name().toLowerCase(Locale.ROOT).equals(needle)
                || workspace.id().toLowerCase(Locale.ROOT).equals(needle)
                || workspace.url().toLowerCase(Locale.ROOT).contains(needle))
            .findFirst().orElse(null);
    }

    public static List<Workspace> extractWorkspaces(JsonNode raw) {
        if (raw == null) return List.of();
        if (raw.isTextual()) {
            try {
                raw = JSON.readTree(raw.asText());
            } catch (java.io.IOException e) {
                return List.of();
            }
        }
        if (raw == null || !raw.path("teams").isObject()) return List.of();
        List<Workspace> result = new ArrayList<>();
        raw.path("teams").properties().forEach(entry -> {
            JsonNode team = entry.getValue();
            String token = team.path("token").asText();
            String url = team.hasNonNull("url") ? team.path("url").asText() : team.path("domain").asText();
            if (!token.startsWith("xoxc-") || !safeSlackUrl(url)) return;
            String id = team.path("id").isTextual() ? team.path("id").asText() : entry.getKey();
            String name = team.path("name").isTextual() ? team.path("name").asText() : id;
            String userId = team.path("user_id").isTextual() ? team.path("user_id").asText() : null;
            result.add(new Workspace(url.replaceAll("/+$", ""), name, id, userId, token));
        });
        return result;
    }
}

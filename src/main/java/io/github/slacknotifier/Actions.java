package io.github.slacknotifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.slacknotifier.browser.Browser;
import io.github.slacknotifier.browser.Capture;
import io.github.slacknotifier.help.Help;
import io.github.slacknotifier.slack.ChannelResolver;
import io.github.slacknotifier.slack.ConfigStore;
import io.github.slacknotifier.slack.SlackApi;
import io.github.slacknotifier.slack.SlackClient;
import io.github.slacknotifier.slack.StoredConfig;
import io.github.slacknotifier.slack.StoredCredentials;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class Actions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Actions() {}

    static int login(boolean headless, String wanted, String timeoutValue) throws Exception {
        double seconds;
        try {
            seconds = Double.parseDouble(timeoutValue);
        } catch (NumberFormatException e) {
            seconds = Double.NaN;
        }
        if (!Double.isFinite(seconds) || seconds <= 0) {
            System.err.println("--timeout must be a positive number of seconds.");
            System.err.println(Help.usageHint("login"));
            return 1;
        }
        long timeoutMs = Math.max(1, (long) Math.min(seconds * 1000, Long.MAX_VALUE));
        System.err.println(headless ? "Refreshing tokens headlessly…" : "Opening a browser…");
        ConfigStore store = new ConfigStore();
        Capture.Result captured;
        try (Browser browser = Browser.launch(store.browserProfileDir(), headless)) {
            captured = Capture.capture(browser.session()::call, timeoutMs, System.err::println);
        }
        if (!captured.ok()) {
            if (headless && "timeout".equals(captured.reason())) {
                System.err.println("Timed out with no visible window.\n"
                    + "   --headless only works once a normal `slack-notifier login` has signed the profile in.");
            } else {
                System.err.println(captured.message());
            }
            return 1;
        }
        List<Capture.Workspace> workspaces = captured.workspaces();
        Capture.Workspace chosen = Capture.chooseWorkspace(workspaces, wanted);
        if (chosen == null) {
            System.err.println("No signed-in workspace matched \"" + wanted + "\".\n   Found: "
                + workspaces.stream().map(Capture.Workspace::name).collect(Collectors.joining(", ")));
            return 1;
        }
        StoredCredentials credentials = new StoredCredentials(chosen.token(), captured.cookie());
        JsonNode identity = new SlackClient(chosen.url(), credentials).authTest();
        StoredConfig config = new StoredConfig(chosen.url(), textOr(identity, "team", chosen.name()),
            textOr(identity, "team_id", chosen.id()), textOr(identity, "user_id", chosen.userId()),
            credentials, new LinkedHashMap<>());
        store.write(config);
        System.out.println("Signed in to " + config.getWorkspaceName() + " as "
            + textOr(identity, "user", "unknown") + ".");
        if (workspaces.size() > 1 && (wanted == null || wanted.isEmpty())) {
            String others = workspaces.stream().filter(workspace -> !workspace.id().equals(chosen.id()))
                .map(Capture.Workspace::name).collect(Collectors.joining(", "));
            System.err.println("Also signed into: " + others
                + ". Use --workspace <name> to notify one of those instead.");
        }
        return 0;
    }

    static int send(String channelInput, String message, boolean json) throws Exception {
        return send(channelInput, message, json, new ConfigStore(),
            config -> new SlackClient(config.getWorkspaceUrl(), config.getCredentials()),
            System.in, System.console() != null);
    }

    static int send(String channelInput, String message, boolean json, ConfigStore store,
        Function<StoredConfig, SlackApi> clientFactory, InputStream input, boolean stdinIsTerminal)
        throws Exception {
        StoredConfig config = store.read();
        if (config == null) return notSignedIn();
        if (channelInput == null || channelInput.isEmpty()) {
            System.err.println("--channel is required.");
            System.err.println(Help.usageHint("send"));
            return 1;
        }
        if (message == null || message.isEmpty()) {
            if (!stdinIsTerminal) {
                message = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (message == null || message.isEmpty()) {
                System.err.println("--message is required, or pipe the message on stdin.");
                System.err.println(Help.usageHint("send"));
                return 1;
            }
        }
        SlackApi client = clientFactory.apply(config);
        String channelId = new ChannelResolver(store).resolve(client, config, channelInput);
        JsonNode result = client.request("chat.postMessage", Map.of("channel", channelId, "text", message));
        if (json) {
            ObjectNode output = JSON.createObjectNode().put("ok", true);
            output.put("channel", textOr(result, "channel", channelId));
            if (result.has("ts")) output.set("ts", result.get("ts"));
            if (result.path("ts").isTextual()) {
                output.put("permalink", config.getWorkspaceUrl() + "/archives/" + channelId + "/p"
                    + result.path("ts").asText().replaceFirst("\\.", ""));
            } else {
                output.putNull("permalink");
            }
            System.out.println(JSON.writeValueAsString(output));
        } else {
            System.out.println("Sent to " + channelInput + ".");
        }
        return 0;
    }

    static int whoami(boolean json) throws Exception {
        StoredConfig config = new ConfigStore().read();
        if (config == null) return notSignedIn();
        JsonNode identity = new SlackClient(config.getWorkspaceUrl(), config.getCredentials()).authTest();
        if (json) {
            ObjectNode output = JSON.createObjectNode().put("status", "ok");
            output.put("workspace_name", config.getWorkspaceName());
            output.put("workspace_id", config.getWorkspaceId());
            output.put("workspace_url", config.getWorkspaceUrl());
            if (identity.has("user")) output.set("user", identity.get("user"));
            if (identity.has("user_id")) output.set("user_id", identity.get("user_id"));
            System.out.println(JSON.writeValueAsString(output));
        } else {
            System.out.println(config.getWorkspaceName());
            System.out.println("  URL: " + config.getWorkspaceUrl());
            System.out.println("  User: " + textOr(identity, "user", "undefined") + " ("
                + textOr(identity, "user_id", "undefined") + ")");
            System.out.println("  Status: verified");
        }
        return 0;
    }

    static int logout(boolean keepBrowserSession) throws Exception {
        new ConfigStore().clear(keepBrowserSession);
        System.out.println(keepBrowserSession
            ? "Credentials removed. The browser profile was kept, so it can still re-mint tokens."
            : "Credentials and the browser profile were removed.");
        return 0;
    }

    private static int notSignedIn() {
        System.err.println("Not signed in.\n   To fix: slack-notifier login");
        return 1;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        return node.path(field).isTextual() ? node.path(field).asText() : fallback;
    }
}

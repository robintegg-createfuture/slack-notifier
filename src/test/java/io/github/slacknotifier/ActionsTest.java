package io.github.slacknotifier;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.slacknotifier.slack.ConfigStore;
import io.github.slacknotifier.slack.SlackApi;
import io.github.slacknotifier.slack.StoredConfig;
import io.github.slacknotifier.slack.StoredCredentials;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionsTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test void sendResolvesAndCachesChannelAndReadsPipedMessage() throws Exception {
        ConfigStore store = new ConfigStore(temp);
        store.write(new StoredConfig("https://acme.slack.com", "Acme", "T1", "U1",
            new StoredCredentials("xoxc-test", "xoxd-test"), Map.of()));
        List<String> calls = new ArrayList<>();
        SlackApi client = (method, params) -> {
            calls.add(method);
            if (method.equals("conversations.list")) {
                return json.readTree("{\"ok\":true,\"channels\":[{\"id\":\"C0123456\",\"name\":\"alerts\"}]}");
            }
            assertEquals("C0123456", params.get("channel"));
            assertEquals("Deploy finished\nsuccessfully", params.get("text"));
            return json.readTree("{\"ok\":true,\"channel\":\"C0123456\",\"ts\":\"123.456\"}");
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            assertEquals(0, Actions.send("#alerts", null, true, store, ignored -> client,
                new ByteArrayInputStream("  Deploy finished\nsuccessfully  \n".getBytes(StandardCharsets.UTF_8)), false));
        } finally {
            System.setOut(original);
        }
        JsonNode output = json.readTree(bytes.toString(StandardCharsets.UTF_8));
        assertTrue(output.path("ok").asBoolean());
        assertEquals("C0123456", output.path("channel").asText());
        assertEquals("https://acme.slack.com/archives/C0123456/p123456", output.path("permalink").asText());
        assertEquals("C0123456", store.read().getChannelCache().get("alerts"));
        assertEquals(List.of("conversations.list", "chat.postMessage"), calls);
    }

    @Test void messageFlagPreservesWhitespaceAndChannelIdsSkipLookup() throws Exception {
        ConfigStore store = new ConfigStore(temp);
        store.write(new StoredConfig("https://acme.slack.com", "Acme", "T1", "U1",
            new StoredCredentials("xoxc-test", "xoxd-test"), Map.of()));
        SlackApi client = (method, params) -> {
            assertEquals("chat.postMessage", method);
            assertEquals("  quoted message  ", params.get("text"));
            return json.readTree("{\"ok\":true,\"ts\":\"1.2\"}");
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            assertEquals(0, Actions.send("C0123456", "  quoted message  ", true, store,
                ignored -> client, new ByteArrayInputStream(new byte[0]), false));
        } finally {
            System.setOut(original);
        }
        assertEquals("C0123456", json.readTree(bytes.toString(StandardCharsets.UTF_8)).path("channel").asText());
    }
}

package io.github.slacknotifier.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode json(String text) throws Exception { return JSON.readTree(text); }

    @Test void extractsValidWorkspacesAndRejectsUnsafeHosts() throws Exception {
        JsonNode input = json("""
            {"teams":{"T1":{"url":"https://team.slack.com/","token":"xoxc-one","name":"Team"},
            "T2":{"url":"https://slack.com.evil.test","token":"xoxc-two"},
            "T3":{"url":"https://team.slack.com","token":"bad"}}}
            """);
        List<Capture.Workspace> result = Capture.extractWorkspaces(input);
        assertEquals(1, result.size());
        assertEquals("https://team.slack.com", result.getFirst().url());
        assertEquals("T1", result.getFirst().id());
        assertFalse(Capture.safeSlackUrl("http://team.slack.com"));
        assertFalse(Capture.safeSlackUrl("https://slack.com.evil.test"));
        assertTrue(Capture.safeSlackUrl("https://org.enterprise.slack.com"));
        assertTrue(Capture.extractWorkspaces(json("\"not json\"")).isEmpty());
    }

    @Test void cookieIsDecodedAndPlusIsPreserved() throws Exception {
        assertEquals("xoxd-abc/def+ghi", Capture.extractCookie(
            json("[{\"name\":\"d\",\"value\":\"xoxd-abc%2Fdef+ghi\"}]")));
        assertNull(Capture.extractCookie(json("[{\"name\":\"d\",\"value\":\"other\"}]")));
    }

    @Test void choosesWorkspaceByNameIdOrUrl() {
        var first = new Capture.Workspace("https://first.slack.com", "First", "T1", null, "xoxc-one");
        var second = new Capture.Workspace("https://second.slack.com", "Second", "T2", null, "xoxc-two");
        var workspaces = List.of(first, second);
        assertSame(first, Capture.chooseWorkspace(workspaces, null));
        assertSame(second, Capture.chooseWorkspace(workspaces, "SECOND"));
        assertSame(second, Capture.chooseWorkspace(workspaces, "t2"));
        assertSame(second, Capture.chooseWorkspace(workspaces, "second.slack.com"));
        assertNull(Capture.chooseWorkspace(workspaces, "missing"));
        assertNull(Capture.chooseWorkspace(List.of(), null));
    }

    @Test void waitsForLoginAndFallsBackToOlderCookieMethod() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        List<String> progress = new ArrayList<>();
        Capture.Session session = (method, params, sessionId) -> switch (method) {
            case "Target.getTargets" -> json("{\"targetInfos\":[{\"type\":\"page\",\"url\":\"https://app.slack.com/client\",\"targetId\":\"target\"}]}");
            case "Target.attachToTarget" -> json("{\"sessionId\":\"session\"}");
            case "Runtime.evaluate" -> polls.getAndIncrement() == 0 ? json("{\"result\":{\"value\":null}}")
                : json("{\"result\":{\"value\":{\"teams\":{\"T\":{\"url\":\"https://team.slack.com\",\"token\":\"xoxc-test\"}}}}}");
            case "Storage.getCookies" -> throw new IllegalStateException("unsupported");
            case "Network.getCookies" -> json("{\"cookies\":[{\"name\":\"d\",\"value\":\"xoxd-test\"}]}");
            default -> throw new AssertionError(method);
        };
        Capture.Result result = Capture.capture(session, 100, 0, progress::add, ms -> {}, () -> 0);
        assertTrue(result.ok());
        assertEquals("xoxd-test", result.cookie());
        assertEquals(1, result.workspaces().size());
        assertEquals(2, polls.get());
        assertTrue(progress.getFirst().contains("Waiting"));
    }

    @Test void reportsNoCookieAndTimeout() throws Exception {
        Capture.Session noCookie = (method, params, sessionId) -> switch (method) {
            case "Target.getTargets" -> json("{\"targetInfos\":[{\"type\":\"page\",\"url\":\"https://app.slack.com\",\"targetId\":\"a\"}]}");
            case "Target.attachToTarget" -> json("{\"sessionId\":\"b\"}");
            case "Runtime.evaluate" -> json("{\"result\":{\"value\":{\"teams\":{\"T\":{\"url\":\"https://team.slack.com\",\"token\":\"xoxc-test\"}}}}}");
            default -> json("{\"cookies\":[]}");
        };
        assertEquals("no_cookie", Capture.capture(noCookie, 100, 0, ignored -> {}, ms -> {}, () -> 0).reason());
        AtomicLong clock = new AtomicLong();
        Capture.Session none = (method, params, sessionId) -> json("{\"targetInfos\":[]}");
        assertEquals("timeout", Capture.capture(none, 100, 0, ignored -> {}, ms -> {},
            () -> clock.getAndAdd(40)).reason());
    }
}

package io.github.slacknotifier.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class CdpClient implements AutoCloseable, WebSocket.Listener {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final StringBuilder incoming = new StringBuilder();
    private WebSocket socket;

    public static CdpClient connect(URI uri) throws Exception {
        CdpClient client = new CdpClient();
        try {
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15)).buildAsync(uri, client)
                .get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out connecting to the browser.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not connect to the browser's debugging port.", e);
        }
        return client;
    }

    public JsonNode call(String method, Map<String, ?> params, String sessionId) throws Exception {
        int id = nextId.incrementAndGet();
        ObjectNode message = JSON.createObjectNode().put("id", id).put("method", method);
        message.set("params", JSON.valueToTree(params));
        if (sessionId != null) message.put("sessionId", sessionId);
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        pending.put(id, result);
        try {
            socket.sendText(JSON.writeValueAsString(message), true).get(15, TimeUnit.SECONDS);
            return result.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw e;
        } finally {
            pending.remove(id);
        }
    }

    @Override public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override public java.util.concurrent.CompletionStage<?> onText(
        WebSocket webSocket, CharSequence data, boolean last) {
        incoming.append(data);
        if (last) {
            try {
                JsonNode message = JSON.readTree(incoming.toString());
                if (message.has("id")) {
                    CompletableFuture<JsonNode> call = pending.remove(message.path("id").asInt());
                    if (call != null) {
                        if (message.has("error")) {
                            call.completeExceptionally(new IllegalStateException(
                                message.path("error").path("message").asText("CDP call failed.")));
                        } else {
                            call.complete(message.path("result"));
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // Ignore non-JSON browser events.
            } finally {
                incoming.setLength(0);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override public java.util.concurrent.CompletionStage<?> onClose(
        WebSocket webSocket, int statusCode, String reason) {
        failPending();
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        failPending();
    }

    private void failPending() {
        pending.values().forEach(call ->
            call.completeExceptionally(new IllegalStateException("The browser connection closed.")));
        pending.clear();
    }

    @Override public void close() {
        if (socket != null) socket.abort();
        failPending();
    }
}

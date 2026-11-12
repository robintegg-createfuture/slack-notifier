package io.github.slacknotifier.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CdpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void exchangesCdpRequestsAndReportsProtocolErrors() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    String headers = readHeaders(in);
                    String key = headers.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    JsonNode first = JSON.readTree(readFrame(in));
                    assertEquals("Target.getTargets", first.path("method").asText());
                    sendFrame(out, "{\"id\":" + first.path("id").asInt()
                        + ",\"result\":{\"targetInfos\":[]}}");
                    JsonNode second = JSON.readTree(readFrame(in));
                    assertEquals("unsupported", second.path("method").asText());
                    sendFrame(out, "{\"id\":" + second.path("id").asInt()
                        + ",\"error\":{\"message\":\"unsupported method\"}}");
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            try (CdpClient client = CdpClient.connect(URI.create(
                "ws://127.0.0.1:" + server.getLocalPort() + "/devtools/browser/test"))) {
                assertTrue(client.call("Target.getTargets", Map.of(), null).path("targetInfos").isArray());
                Exception error = assertThrows(Exception.class, () -> client.call("unsupported", Map.of(), null));
                assertTrue(error.getMessage().contains("unsupported method"));
            }
            peer.get(10, TimeUnit.SECONDS);
        }
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new java.io.EOFException();
            bytes.write(b);
            state = switch (state) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static String readFrame(InputStream in) throws Exception {
        int first = in.read();
        int second = in.read();
        if (first < 0 || second < 0) throw new java.io.EOFException();
        int length = second & 0x7f;
        if (length == 126) length = (in.read() << 8) | in.read();
        if (length == 127) throw new IllegalStateException("Unexpected long test frame");
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes(length);
        assertEquals(length, payload.length);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void sendFrame(OutputStream out, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length < 126);
        out.write(0x81);
        out.write(bytes.length);
        out.write(bytes);
        out.flush();
    }
}

package io.github.slacknotifier.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class SlackClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final StoredCredentials CREDENTIALS =
      new StoredCredentials("xoxc-token", "xoxd-value/with=padding");

  @Test
  void postsToWorkspaceWithSessionCredentials() throws Exception {
    List<URI> uris = new ArrayList<>();
    List<Map<String, String>> forms = new ArrayList<>();
    List<Map<String, String>> headers = new ArrayList<>();
    SlackClient client =
        client(
            (uri, form, requestHeaders) -> {
              uris.add(uri);
              forms.add(form);
              headers.add(requestHeaders);
              return ok("{\"ok\":true,\"user\":\"alice\"}");
            },
            ignored -> {});

    JsonNode result = client.authTest();

    assertEquals("https://acme.slack.com/api/auth.test", uris.getFirst().toString());
    assertEquals("xoxc-token", forms.getFirst().get("token"));
    assertEquals("d=xoxd-value%2Fwith%3Dpadding", headers.getFirst().get("Cookie"));
    assertEquals("alice", result.path("user").asText());
  }

  @Test
  void defaultTransportUsesEncodedPostFormAndCookieHeader() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat.postMessage",
        exchange -> {
          calls.incrementAndGet();
          assertEquals("d=xoxd-value%2Fwith%3Dpadding", exchange.getRequestHeaders().getFirst("Cookie"));
          assertTrue(
              exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"));
          String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(form.contains("token=xoxc-token"));
          assertTrue(form.contains("channel=C123"));
          assertTrue(form.contains("text=hello+world"));
          byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      SlackClient client =
          new SlackClient(
              "https://acme.slack.com", CREDENTIALS,
              (uri, form, headers) -> SlackClient.httpPost(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + uri.getPath()),
                  form, headers),
              ignored -> {});
      assertTrue(client.postMessage("C123", "hello world").path("ok").asBoolean());
      assertEquals(1, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsUntrustedWorkspaceUrlsBeforeSendingCredentials() {
    assertThrows(IllegalArgumentException.class,
        () -> new SlackClient("http://acme.slack.com", CREDENTIALS));
    assertThrows(IllegalArgumentException.class,
        () -> new SlackClient("https://slack.com.example.org", CREDENTIALS));
  }

  @Test
  void sendsChannelAndTextForMessages() throws Exception {
    List<Map<String, String>> forms = new ArrayList<>();
    SlackClient client =
        client(
            (uri, form, headers) -> {
              forms.add(form);
              return ok("{\"ok\":true,\"ts\":\"1.2\"}");
            },
            ignored -> {});

    assertEquals("1.2", client.postMessage("C123", "hello").path("ts").asText());
    assertEquals("C123", forms.getFirst().get("channel"));
    assertEquals("hello", forms.getFirst().get("text"));
  }

  @Test
  void classifiesAuthenticationErrorsSeparately() {
    SlackClient client =
        client(
            (uri, form, headers) -> ok("{\"ok\":false,\"error\":\"token_expired\"}"),
            ignored -> {});

    SlackAuthException error = assertThrows(SlackAuthException.class, client::authTest);
    assertEquals("token_expired", error.getCode());
    assertTrue(error.getMessage().contains("slack-notifier login"));
  }

  @Test
  void doesNotRetryWritesAfterServerOrNetworkErrors() {
    AtomicInteger calls = new AtomicInteger();
    SlackClient serverFailure =
        client(
            (uri, form, headers) -> {
              calls.incrementAndGet();
              return new SlackHttpResponse(503, Map.of(), "boom");
            },
            ignored -> {});
    IOException httpError =
        assertThrows(IOException.class, () -> serverFailure.postMessage("C123", "hello"));
    assertTrue(httpError.getMessage().contains("HTTP 503"));
    assertEquals(1, calls.get());

    SlackClient networkFailure =
        client(
            (uri, form, headers) -> {
              calls.incrementAndGet();
              throw new IOException("network down");
            },
            ignored -> {});
    IOException networkError =
        assertThrows(IOException.class, () -> networkFailure.postMessage("C123", "hello"));
    assertEquals("network down", networkError.getMessage());
    assertEquals(2, calls.get());
  }

  @Test
  void retriesReadsOnServerErrorsAndWritesWhenRateLimited() throws Exception {
    AtomicInteger readCalls = new AtomicInteger();
    List<Long> readSleeps = new ArrayList<>();
    SlackClient readClient =
        client(
            (uri, form, headers) ->
                readCalls.incrementAndGet() == 1
                    ? new SlackHttpResponse(503, Map.of(), "boom")
                    : ok("{\"ok\":true,\"user\":\"alice\"}"),
            readSleeps::add);

    assertEquals("alice", readClient.authTest().path("user").asText());
    assertEquals(2, readCalls.get());
    assertEquals(1, readSleeps.size());
    assertTrue(readSleeps.getFirst() >= 1_000 && readSleeps.getFirst() < 1_250);

    AtomicInteger writeCalls = new AtomicInteger();
    List<Long> writeSleeps = new ArrayList<>();
    SlackClient rateLimitedClient =
        client(
            (uri, form, headers) ->
                writeCalls.incrementAndGet() == 1
                    ? new SlackHttpResponse(
                        429, Map.of("Retry-After", List.of("1")), "")
                    : ok("{\"ok\":true,\"ts\":\"1.2\"}"),
            writeSleeps::add);
    assertEquals("1.2", rateLimitedClient.postMessage("C123", "hello").path("ts").asText());
    assertEquals(2, writeCalls.get());
    assertEquals(List.of(1_000L), writeSleeps);
  }

  @Test
  void reportsOtherSlackErrorsAsApiErrors() {
    SlackClient client =
        client(
            (uri, form, headers) -> ok("{\"ok\":false,\"error\":\"channel_not_found\"}"),
            ignored -> {});

    SlackApiException error = assertThrows(SlackApiException.class, client::authTest);
    assertEquals("channel_not_found", error.getCode());
  }

  private static SlackClient client(SlackHttpTransport transport, SlackSleeper sleeper) {
    return new SlackClient("https://acme.slack.com/", CREDENTIALS, transport, sleeper);
  }

  private static SlackHttpResponse ok(String body) {
    return new SlackHttpResponse(200, Map.of(), body);
  }
}

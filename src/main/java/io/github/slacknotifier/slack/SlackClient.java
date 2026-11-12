package io.github.slacknotifier.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.slacknotifier.browser.Capture;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Calls Slack using the browser-session credentials for the workspace host.
 *
 * <p>The xoxc token is sent in the POST form body and xoxd is sent as the {@code d} cookie. These
 * credentials are not bearer tokens and must not be sent to slack.com/api.
 */
public final class SlackClient implements SlackApi {
  private static final Set<String> AUTH_ERROR_CODES =
      Set.of("invalid_auth", "token_expired", "token_revoked", "not_authed", "account_inactive");
  private static final Set<String> READ_METHODS =
      Set.of("auth.test", "conversations.list", "client.userBoot");
  private static final int MAX_RETRIES = 3;
  private static final long MAX_WAIT_MILLIS = 60_000;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final String workspaceUrl;
  private final StoredCredentials credentials;
  private final SlackHttpTransport transport;
  private final SlackSleeper sleeper;

  public SlackClient(String workspaceUrl, StoredCredentials credentials) {
    this(workspaceUrl, credentials, SlackClient::httpPost, Thread::sleep);
  }

  public SlackClient(
      String workspaceUrl,
      StoredCredentials credentials,
      SlackHttpTransport transport,
      SlackSleeper sleeper) {
    if (!Capture.safeSlackUrl(workspaceUrl)) {
      throw new IllegalArgumentException("The stored workspace URL is not a valid HTTPS Slack URL.");
    }
    this.workspaceUrl = workspaceUrl.replaceAll("/+$", "");
    this.credentials = credentials;
    this.transport = transport;
    this.sleeper = sleeper;
  }

  @Override
  public JsonNode request(String method, Map<String, String> params)
      throws IOException, InterruptedException {
    boolean isRead = READ_METHODS.contains(method);
    IOException lastError = null;

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      SlackHttpResponse response;
      try {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", credentials.xoxc());
        if (params != null) {
          form.putAll(params);
        }
        response =
            transport.post(
                URI.create(workspaceUrl + "/api/" + method),
                Map.copyOf(form),
                Map.of("Cookie", "d=" + encodeURIComponent(credentials.xoxd())));
      } catch (IOException error) {
        if (!isRead || attempt == MAX_RETRIES) {
          throw error;
        }
        lastError = error;
        sleeper.sleep(backoffMillis(attempt));
        continue;
      }

      if (response.statusCode() == 429) {
        if (attempt == MAX_RETRIES) {
          throw new IOException("Slack rate-limited " + method + " and did not recover.");
        }
        sleeper.sleep(retryAfterMillis(response, attempt));
        continue;
      }

      if (response.statusCode() >= 500) {
        if (!isRead || attempt == MAX_RETRIES) {
          throw new IOException(
              "Slack returned HTTP " + response.statusCode() + " for " + method + ".");
        }
        lastError = new IOException("HTTP " + response.statusCode());
        sleeper.sleep(backoffMillis(attempt));
        continue;
      }

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException(
            "Slack returned HTTP " + response.statusCode() + " for " + method + ".");
      }

      JsonNode body;
      try {
        body = JSON.readTree(response.body());
      } catch (IOException | RuntimeException error) {
        throw new IOException("Slack returned an unreadable response for " + method + ".", error);
      }
      if (body != null && body.path("ok").isBoolean() && body.path("ok").booleanValue()) {
        return body;
      }

      String code =
          body != null && body.path("error").isTextual()
              ? body.path("error").asText()
              : "unknown_error";
      if (AUTH_ERROR_CODES.contains(code)) {
        throw new SlackAuthException(code);
      }
      throw new SlackApiException(code);
    }

    throw lastError == null ? new IOException(method + " failed.") : lastError;
  }

  public JsonNode authTest() throws IOException, InterruptedException {
    return request("auth.test", Map.of());
  }

  public JsonNode postMessage(String channel, String text) throws IOException, InterruptedException {
    return request("chat.postMessage", Map.of("channel", channel, "text", text));
  }

  static SlackHttpResponse httpPost(
      URI uri, Map<String, String> form, Map<String, String> headers)
      throws IOException, InterruptedException {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> entry : form.entrySet()) {
      if (!body.isEmpty()) {
        body.append('&');
      }
      body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      body.append('=');
      body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }

    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    headers.forEach(request::header);

    HttpResponse<String> response =
        HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new SlackHttpResponse(
        response.statusCode(), response.headers().map(), response.body());
  }

  private static String encodeURIComponent(String value) {
    StringBuilder encoded = new StringBuilder();
    for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
      int b = current & 0xff;
      if ((b >= 'A' && b <= 'Z')
          || (b >= 'a' && b <= 'z')
          || (b >= '0' && b <= '9')
          || "-_.!~*'()".indexOf(b) >= 0) {
        encoded.append((char) b);
      } else {
        encoded.append('%');
        encoded.append("0123456789ABCDEF".charAt(b >>> 4));
        encoded.append("0123456789ABCDEF".charAt(b & 0xf));
      }
    }
    return encoded.toString();
  }

  private static long backoffMillis(int attempt) {
    long base = Math.min(1_000L * (1L << attempt), MAX_WAIT_MILLIS);
    return base + ThreadLocalRandom.current().nextInt(250);
  }

  private static long retryAfterMillis(SlackHttpResponse response, int attempt) {
    String header = response.header("retry-after");
    if (header != null) {
      try {
        double seconds = Double.parseDouble(header);
        if (Double.isFinite(seconds) && seconds > 0) {
          return Math.min((long) (seconds * 1_000), MAX_WAIT_MILLIS);
        }
      } catch (NumberFormatException ignored) {
        // Use the normal exponential backoff when Slack provides no valid delay.
      }
    }
    return backoffMillis(attempt);
  }
}

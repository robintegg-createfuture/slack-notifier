package io.github.slacknotifier.slack;

import java.util.List;
import java.util.Map;

/** Minimal HTTP response data needed by the Slack client. */
public record SlackHttpResponse(int statusCode, Map<String, List<String>> headers, String body) {
  public SlackHttpResponse {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    body = body == null ? "" : body;
  }

  public String header(String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .flatMap(entry -> entry.getValue().stream())
        .findFirst()
        .orElse(null);
  }
}

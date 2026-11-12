package io.github.slacknotifier.slack;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Injectable HTTP boundary for deterministic Slack client tests. */
@FunctionalInterface
public interface SlackHttpTransport {
  SlackHttpResponse post(URI uri, Map<String, String> form, Map<String, String> headers)
      throws IOException, InterruptedException;
}

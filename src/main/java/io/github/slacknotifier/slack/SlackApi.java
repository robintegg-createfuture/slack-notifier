package io.github.slacknotifier.slack;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/** The Slack API surface used by channel resolution and command handlers. */
@FunctionalInterface
public interface SlackApi {
  JsonNode request(String method, Map<String, String> params)
      throws IOException, InterruptedException;
}

package io.github.slacknotifier.slack;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Slack browser-session credentials stored by the notifier. */
public record StoredCredentials(
    @JsonProperty("xoxc") String xoxc,
    @JsonProperty("xoxd") String xoxd) {
  @Override
  public String toString() {
    return "StoredCredentials[redacted]";
  }
}

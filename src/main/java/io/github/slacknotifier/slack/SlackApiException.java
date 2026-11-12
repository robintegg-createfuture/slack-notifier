package io.github.slacknotifier.slack;

/** An error returned in a successful HTTP response by Slack's API. */
public class SlackApiException extends RuntimeException {
  private final String code;

  public SlackApiException(String code) {
    super("Slack returned an error: " + code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}

package io.github.slacknotifier.slack;

/** Indicates that the stored browser-session credentials are no longer usable. */
public final class SlackAuthException extends RuntimeException {
  private final String code;

  public SlackAuthException(String code) {
    super(
        "Slack rejected the stored credentials ("
            + code
            + ").\n"
            + "   "
            + meaningOf(code)
            + "\n"
            + "   To fix: slack-notifier login");
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  private static String meaningOf(String code) {
    return switch (code) {
      case "token_expired" -> "The browser session behind these tokens has expired.";
      case "token_revoked" -> "The token was revoked by you or a workspace admin.";
      case "account_inactive" -> "The account these tokens belong to is deactivated.";
      case "not_authed" -> "No credentials reached Slack.";
      default -> "The token was not accepted.";
    };
  }
}

package io.github.slacknotifier.slack;

/** Injectable delay used by Slack API retries. */
@FunctionalInterface
public interface SlackSleeper {
  void sleep(long milliseconds) throws InterruptedException;
}

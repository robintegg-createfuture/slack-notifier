package io.github.slacknotifier.slack;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts channel names, IDs, and archive links into the ID needed to post a message. */
public final class ChannelResolver {
  private static final Set<String> RESTRICTED_ON_GRID =
      Set.of("enterprise_is_restricted", "not_allowed_token_type");
  private static final Pattern ARCHIVE_LINK =
      Pattern.compile("^https://[^/]*slack\\.com/archives/([A-Z0-9]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CHANNEL_ID = Pattern.compile("^[CGD][A-Z0-9]{6,}$");

  private final ConfigStore store;

  public ChannelResolver(ConfigStore store) {
    this.store = store;
  }

  public static String normaliseChannelInput(String input) {
    String trimmed = input.trim();
    Matcher link = ARCHIVE_LINK.matcher(trimmed);
    if (link.find()) {
      return link.group(1);
    }
    return trimmed.replaceFirst("^#", "");
  }

  public static boolean looksLikeChannelId(String value) {
    return CHANNEL_ID.matcher(value).matches();
  }

  public String resolve(SlackApi client, StoredConfig config, String input)
      throws IOException, InterruptedException {
    String name = normaliseChannelInput(input);
    if (looksLikeChannelId(name)) {
      return name;
    }

    String cacheKey = name.toLowerCase(Locale.ROOT);
    String cached = config.getChannelCache().get(cacheKey);
    if (cached != null && !cached.isEmpty()) {
      return cached;
    }

    String id = lookupChannelId(client, name);
    if (id == null) {
      throw new IllegalArgumentException(
          "No channel named \""
              + name
              + "\" is visible to this account.\n"
              + "   Check the spelling, and that the account is a member of private channels.");
    }

    config.getChannelCache().put(cacheKey, id);
    store.write(config);
    return id;
  }

  private static String lookupChannelId(SlackApi client, String name)
      throws IOException, InterruptedException {
    try {
      return lookupViaConversationsList(client, name);
    } catch (SlackApiException error) {
      if (RESTRICTED_ON_GRID.contains(error.getCode())) {
        return lookupViaUserBoot(client, name);
      }
      throw error;
    }
  }

  private static String lookupViaConversationsList(SlackApi client, String name)
      throws IOException, InterruptedException {
    String wanted = name.toLowerCase(Locale.ROOT);
    String cursor = "";

    do {
      Map<String, String> params =
          cursor.isEmpty()
              ? Map.of(
                  "types", "public_channel,private_channel",
                  "exclude_archived", "true",
                  "limit", "1000")
              : Map.of(
                  "types", "public_channel,private_channel",
                  "exclude_archived", "true",
                  "limit", "1000",
                  "cursor", cursor);
      JsonNode body = client.request("conversations.list", params);
      String id = matchingId(body.path("channels"), wanted);
      if (id != null) {
        return id;
      }
      cursor = body.path("response_metadata").path("next_cursor").asText("");
    } while (!cursor.isEmpty());

    return null;
  }

  private static String lookupViaUserBoot(SlackApi client, String name)
      throws IOException, InterruptedException {
    JsonNode body = client.request("client.userBoot", Map.of());
    return matchingId(body.path("channels"), name.toLowerCase(Locale.ROOT));
  }

  private static String matchingId(JsonNode channels, String wanted) {
    if (!channels.isArray()) {
      return null;
    }
    for (JsonNode channel : channels) {
      String channelName = channel.path("name").asText(null);
      String id = channel.path("id").asText(null);
      if (channelName != null && id != null && channelName.toLowerCase(Locale.ROOT).equals(wanted)) {
        return id;
      }
    }
    return null;
  }
}

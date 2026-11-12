package io.github.slacknotifier.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelResolverTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void normalisesNamesIdsAndArchiveLinks() {
    assertEquals("alerts", ChannelResolver.normaliseChannelInput(" #alerts "));
    assertEquals("alerts", ChannelResolver.normaliseChannelInput("alerts"));
    assertEquals("C0123456", ChannelResolver.normaliseChannelInput("C0123456"));
    assertEquals(
        "C0123456",
        ChannelResolver.normaliseChannelInput("https://acme.slack.com/archives/C0123456/p170"));
    assertTrue(ChannelResolver.looksLikeChannelId("G0123456"));
    assertFalse(ChannelResolver.looksLikeChannelId("alerts"));
  }

  @Test
  void resolvesPagesCachesCaseInsensitivelyAndUsesIdsDirectly() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    ChannelResolver resolver = new ChannelResolver(store);
    StoredConfig config = config();
    List<String> methods = new ArrayList<>();
    List<Map<String, String>> params = new ArrayList<>();
    SlackApi client =
        (method, requestParams) -> {
          methods.add(method);
          params.add(requestParams);
          if (methods.size() == 1) {
            return json(
                """
                {"ok":true,"channels":[{"id":"C1","name":"general"}],
                 "response_metadata":{"next_cursor":"next"}}
                """);
          }
          return json("{\"ok\":true,\"channels\":[{\"id\":\"C999\",\"name\":\"alerts\"}]}");
        };

    assertEquals("C999", resolver.resolve(client, config, "#Alerts"));
    assertEquals("next", params.get(1).get("cursor"));
    assertEquals("public_channel,private_channel", params.getFirst().get("types"));
    assertEquals("C999", store.read().getChannelCache().get("alerts"));

    assertEquals("C999", resolver.resolve((method, requestParams) -> failIfCalled(), config, "alerts"));
    assertEquals("C0123456", resolver.resolve((method, requestParams) -> failIfCalled(), config, "C0123456"));
  }

  @Test
  void fallsBackToUserBootForEnterpriseGridRestrictions() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    ChannelResolver resolver = new ChannelResolver(store);
    StoredConfig config = config();
    List<String> methods = new ArrayList<>();
    SlackApi client =
        (method, params) -> {
          methods.add(method);
          if (method.equals("conversations.list")) {
            throw new SlackApiException("enterprise_is_restricted");
          }
          return json(
              "{\"ok\":true,\"channels\":[{\"id\":\"G1234567\",\"name\":\"private-updates\"}]}");
        };

    assertEquals("G1234567", resolver.resolve(client, config, "#private-updates"));
    assertEquals(List.of("conversations.list", "client.userBoot"), methods);
    assertEquals("G1234567", store.read().getChannelCache().get("private-updates"));
  }

  @Test
  void doesNotFallbackForUnrelatedErrorsAndGivesGuidanceForMissingChannels() {
    ChannelResolver resolver = new ChannelResolver(new ConfigStore(tempDir.resolve("config")));
    assertThrows(
        SlackApiException.class,
        () ->
            resolver.resolve(
                (method, params) -> {
                  throw new SlackApiException("ratelimited");
                },
                config(),
                "alerts"));
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve((method, params) -> json("{\"ok\":true,\"channels\":[]}"), config(), "missing"));
    assertTrue(missing.getMessage().contains("No channel named \"missing\""));
  }

  private static StoredConfig config() {
    return new StoredConfig(
        "https://acme.slack.com",
        "Acme Corp",
        "T111",
        null,
        new StoredCredentials("xoxc-one", "xoxd-one"),
        Map.of());
  }

  private static JsonNode json(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static JsonNode failIfCalled() {
    throw new AssertionError("Expected cached/direct channel ID to avoid the Slack API");
  }
}

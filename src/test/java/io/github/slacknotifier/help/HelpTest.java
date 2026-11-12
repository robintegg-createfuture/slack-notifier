package io.github.slacknotifier.help;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void definesEveryCommandAndItsPicocliRelevantOptions() {
        assertEquals(List.of("login", "send", "whoami", "logout"),
                Help.COMMANDS.stream().map(Help.CommandSpec::name).toList());

        Help.CommandSpec send = Help.findCommand("send").orElseThrow();
        assertEquals("c", send.options().getFirst().alias());
        assertEquals("<name>", send.options().getFirst().value());
        assertEquals(Boolean.TRUE, send.options().getFirst().required());
        assertEquals("m", send.options().get(1).alias());
        assertEquals("300", Help.findCommand("login").orElseThrow().options().get(2).defaultValue());
        assertTrue(Help.findCommand("logout").orElseThrow().options().stream()
                .anyMatch(option -> option.name().equals("keep-browser-session")));
    }

    @Test
    void validatesCommandsByReturningEmptyForUnknownOrMissingNames() {
        assertEquals(Optional.of("send"), Help.findCommand("send").map(Help.CommandSpec::name));
        assertTrue(Help.findCommand("not-a-command").isEmpty());
        assertTrue(Help.findCommand(null).isEmpty());
    }

    @Test
    void rendersRootHelpWithUsageCommandsEnvironmentAndNotes() {
        String help = Help.renderRootHelp();

        assertTrue(help.startsWith("slack-notifier — Publish a notification to a named Slack channel.\n\nUsage:"));
        assertTrue(help.contains("  slack-notifier help [<command>] [--json]"));
        for (Help.CommandSpec command : Help.COMMANDS) {
            assertTrue(help.contains(String.format("  %-6s  %s", command.name(), command.summary())));
            assertTrue(help.contains(command.usage()));
        }
        assertTrue(help.contains("Run \"slack-notifier <command> --help\""));
        assertTrue(help.contains("Run \"slack-notifier help --json\""));
        assertTrue(help.contains("SLACK_NOTIFIER_BROWSER"));
        assertTrue(help.contains("SLACK_NOTIFIER_CONFIG_DIR"));
        assertTrue(help.contains("Exit code is 0 on success and 1 on any failure"));
        assertTrue(help.contains("Token values are never printed, by any command."));
    }

    @Test
    void rendersCommandHelpWithOptionsExamplesJsonNotesAndErrors() {
        for (Help.CommandSpec command : Help.COMMANDS) {
            String rendered = Help.renderCommandHelp(command);
            assertTrue(rendered.contains(command.usage()));
            command.options().forEach(option -> assertTrue(rendered.contains("--" + option.name())));
            command.examples().forEach(example -> assertTrue(rendered.contains(example)));
            if (command.errors() != null) {
                command.errors().forEach(error -> assertTrue(rendered.contains(error.condition())));
            }
        }

        String sendHelp = Help.renderCommandHelp(Help.findCommand("send").orElseThrow());
        assertTrue(sendHelp.contains("Usage:\n  slack-notifier send --channel <name> [--message <text>] [--json]"));
        assertTrue(sendHelp.contains("-c, --channel <name>  Channel to post to"));
        assertTrue(sendHelp.contains("[required]"));
        assertTrue(sendHelp.contains("slack-notifier send -c alerts -m \"Build 42 failed\""));
        assertTrue(sendHelp.contains("With --json, stdout carries one object:"));
        assertTrue(sendHelp.contains("permalink: string|null"));
        assertTrue(sendHelp.contains("On Enterprise Grid"));
        assertTrue(sendHelp.contains("channel_not_found"));

        String loginHelp = Help.renderCommandHelp(Help.findCommand("login").orElseThrow());
        assertTrue(loginHelp.contains("[default 300]"));
        assertTrue(loginHelp.contains("No Chrome, Edge, Chromium or Brave installation was found"));
        assertTrue(loginHelp.contains("Run slack-notifier login again and sign in, or raise --timeout."));

        String logoutHelp = Help.renderCommandHelp(Help.findCommand("logout").orElseThrow());
        assertTrue(logoutHelp.contains("--keep-browser-session"));
        assertTrue(logoutHelp.contains("Succeeds whether or not anything was stored."));

        String whoamiHelp = Help.renderCommandHelp(Help.findCommand("whoami").orElseThrow());
        assertTrue(whoamiHelp.contains("workspace_id"));
        assertTrue(whoamiHelp.contains("The stored credentials were refused."));
    }

    @Test
    void serializesRootAndPerCommandSpecificationsWithOptionalFieldsOmitted() throws Exception {
        JsonNode root = objectMapper.readTree(Help.helpAsJson());
        assertEquals("slack-notifier", root.path("program").asText());
        List<String> commandNames = new java.util.ArrayList<>();
        root.path("commands").forEach(command -> commandNames.add(command.path("name").asText()));
        assertEquals(List.of("login", "send", "whoami", "logout"), commandNames);
        assertEquals("success", root.path("exit_codes").path("0").asText());
        assertEquals("failure", root.path("exit_codes").path("1").asText());
        assertEquals(List.of("program", "summary", "commands", "environment", "notes", "exit_codes"),
                fieldNames(root));

        JsonNode send = objectMapper.readTree(Help.helpAsJson(Help.findCommand("send").orElseThrow()));
        assertEquals("send", send.path("name").asText());
        assertTrue(send.path("options").get(0).path("required").asBoolean());
        assertEquals("c", send.path("options").get(0).path("alias").asText());
        assertEquals("string — the message timestamp, which identifies the message",
                send.path("jsonShape").path("ts").asText());
        assertFalse(send.path("options").get(0).has("default"));
        assertTrue(send.path("options").get(0).has("alias"));
        assertNotNull(objectMapper.readTree(Help.helpAsJson(null)).get("commands"));
        assertEquals(List.of("name", "summary", "description", "usage", "options", "examples", "jsonShape", "notes", "errors"),
                fieldNames(send));

        JsonNode login = objectMapper.readTree(Help.helpAsJson(Help.findCommand("login").orElseThrow()));
        JsonNode timeout = login.path("options").get(2);
        assertEquals("300", timeout.path("default").asText());
        assertEquals(List.of("name", "value", "description", "default"), fieldNames(timeout));

        JsonNode whoami = objectMapper.readTree(Help.helpAsJson(Help.findCommand("whoami").orElseThrow()));
        assertFalse(whoami.has("notes"));
    }

    @Test
    void returnsTheCommandSpecificUsageHint() {
        assertEquals("(run \"slack-notifier send --help\" for usage and examples)", Help.usageHint("send"));
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

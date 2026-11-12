package io.github.slacknotifier;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SkillContractTest {
    @Test void documentedCommandsAndOptionsMatchPicocli() throws Exception {
        JsonNode specification = new ObjectMapper().readTree(HelpOutput.json(null));
        Map<String, CommandLine> subcommands = new CommandLine(new Main()).getSubcommands();
        Set<String> documented = new HashSet<>();
        for (JsonNode command : specification.path("commands")) {
            String name = command.path("name").asText();
            documented.add(name);
            assertTrue(subcommands.containsKey(name), name);
            Set<String> options = new HashSet<>();
            subcommands.get(name).getCommandSpec().options().forEach(option ->
                options.addAll(java.util.List.of(option.names())));
            for (JsonNode option : command.path("options")) {
                assertTrue(options.contains("--" + option.path("name").asText()), name);
                if (option.has("alias")) assertTrue(options.contains("-" + option.path("alias").asText()), name);
            }
        }
        Set<String> actual = new HashSet<>(subcommands.keySet());
        actual.remove("help");
        assertEquals(documented, actual);
    }

    @Test void skillCommandsAndFlagsMatchTheCliSpecification() throws Exception {
        String skill = Files.readString(Path.of("skills", "slack-notifier", "SKILL.md"));
        JsonNode specification = new ObjectMapper().readTree(HelpOutput.json(null));
        Matcher frontmatter = Pattern.compile("\\A---\\nname: ([^\\n]+)\\ndescription: ([^\\n]+)\\n---\\n")
            .matcher(skill);
        assertTrue(frontmatter.find());
        assertEquals("slack-notifier", frontmatter.group(1));
        assertTrue(frontmatter.group(2).length() > 200);
        assertTrue(skill.lines().count() < 140);
        Map<String, Set<String>> commands = new HashMap<>();
        for (JsonNode command : specification.path("commands")) {
            assertTrue(skill.toLowerCase(java.util.Locale.ROOT).contains(command.path("name").asText()));
            Set<String> flags = new HashSet<>(Set.of("help"));
            for (JsonNode option : command.path("options")) {
                flags.add(option.path("name").asText());
                if (option.has("alias")) flags.add(option.path("alias").asText());
            }
            commands.put(command.path("name").asText(), flags);
        }
        commands.put("help", Set.of("json", "help"));

        Pattern invocation = Pattern.compile(
            "slack-notifier[^\\S\\n]+([a-z-]+)((?:[^\\S\\n]+(?:-{1,2}[a-z-]+|\"[^\"\\n]*\"|[^\\s|<>]+))*)");
        Pattern flag = Pattern.compile("(?:^|\\s)-{1,2}([a-z][a-z-]*)");
        Matcher matcher = invocation.matcher(skill);
        int count = 0;
        while (matcher.find()) {
            count++;
            String command = matcher.group(1);
            assertTrue(commands.containsKey(command), matcher.group());
            Matcher flagMatcher = flag.matcher(matcher.group(2));
            boolean hasChannel = false;
            boolean help = false;
            while (flagMatcher.find()) {
                String name = flagMatcher.group(1);
                assertTrue(commands.get(command).contains(name), matcher.group());
                hasChannel |= name.equals("channel") || name.equals("c");
                help |= name.equals("help");
            }
            if (command.equals("send") && !help) assertTrue(hasChannel, matcher.group());
        }
        assertTrue(count > 5);
        assertTrue(skill.contains("Always confirm before posting"));
        assertTrue(skill.contains("Never retry a failed send"));
        assertTrue(skill.contains("slack-notifier help --json"));
    }
}

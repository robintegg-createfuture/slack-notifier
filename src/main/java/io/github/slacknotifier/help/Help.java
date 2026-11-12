package io.github.slacknotifier.help;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The command specification, and the two ways it is rendered. */
public final class Help {
    public static final String PROGRAM = "slack-notifier";
    public static final String SUMMARY = "Publish a notification to a named Slack channel.";

    public static final List<String> GLOBAL_NOTES = List.of(
            "Exit code is 0 on success and 1 on any failure, so this drops straight into a script.",
            "Human-readable output goes to stdout; progress and errors go to stderr. With --json, stdout carries one JSON object and nothing else.",
            "Token values are never printed, by any command.");

    public static final List<EnvironmentEntry> ENVIRONMENT = List.of(
            new EnvironmentEntry("SLACK_NOTIFIER_BROWSER", "Path to a browser, if yours is not auto-detected."),
            new EnvironmentEntry("SLACK_NOTIFIER_CONFIG_DIR", "Override ~/.config/slack-notifier."));

    public static final List<CommandSpec> COMMANDS = List.of(
            new CommandSpec(
                    "login",
                    "Sign in by capturing the tokens of a Slack session in your browser.",
                    "Opens a browser on Slack and captures the session tokens once you sign in. "
                            + "No Slack app and no admin approval are needed. The first run needs you to "
                            + "sign in; the browser profile persists, so later runs can use --headless to "
                            + "refresh the tokens with no window and no prompt. Browser tokens expire with "
                            + "the web session, so a scheduled headless login keeps a long-lived notifier "
                            + "working. Requires Chrome, Edge, Chromium or Brave.",
                    PROGRAM + " login [--headless] [--workspace <name>] [--timeout <seconds>]",
                    List.of(
                            new OptionSpec("headless", null, null,
                                    "Sign in with no visible window. Only works once a normal login has signed the profile in.", null, null),
                            new OptionSpec("workspace", null, "<name>",
                                    "Pick a workspace by name, ID or URL when signed into several. Defaults to the first found.", null, null),
                            new OptionSpec("timeout", null, "<seconds>",
                                    "How long to wait for you to sign in.", "300", null)),
                    List.of(
                            PROGRAM + " login",
                            PROGRAM + " login --headless",
                            PROGRAM + " login --workspace \"Acme Corp\" --timeout 600"),
                    null,
                    List.of(
                            "The browser runs against its own profile in ~/.config/slack-notifier/browser-profile, not your everyday one: since Chrome 136 the browser refuses remote debugging against the default profile.",
                            "The browser is closed as soon as capture finishes, and also on Ctrl-C."),
                    List.of(
                            new ErrorSpec(
                                    "No Chrome, Edge, Chromium or Brave installation was found",
                                    "Install one, or set SLACK_NOTIFIER_BROWSER to the browser's path."),
                            new ErrorSpec(
                                    "Timed out waiting for a signed-in Slack session",
                                    "Run " + PROGRAM + " login again and sign in, or raise --timeout."),
                            new ErrorSpec(
                                    "Timed out with no visible window (--headless)",
                                    "Run " + PROGRAM + " login without --headless once, to sign the profile in."))),
            new CommandSpec(
                    "send",
                    "Post a message to a channel.",
                    "Posts a message to a channel. --channel takes a name, a #name, a channel ID, "
                            + "or a Slack archive link; a name is resolved once and cached, so repeat "
                            + "notifications cost a single API call. With no --message, the message is read "
                            + "from stdin, which is how you pipe build output into a channel.",
                    PROGRAM + " send --channel <name> [--message <text>] [--json]",
                    List.of(
                            new OptionSpec("channel", "c", "<name>",
                                    "Channel to post to: a name, a #name, a channel ID, or an archive link.", null, true),
                            new OptionSpec("message", "m", "<text>",
                                    "Message to post. Read from stdin when omitted.", null, null),
                            new OptionSpec("json", null, null,
                                    "Write the result to stdout as one JSON object.", null, null)),
                    List.of(
                            PROGRAM + " send --channel \"#alerts\" --message \"Deploy finished\"",
                            PROGRAM + " send -c alerts -m \"Build 42 failed\"",
                            PROGRAM + " send -c C0123456 -m \"By channel ID\"",
                            "./run-build.sh 2>&1 | tail -5 | " + PROGRAM + " send -c \"#alerts\"",
                            PROGRAM + " send -c \"#alerts\" -m \"Deploy finished\" --json"),
                    orderedMap(
                            "ok", "boolean — always true; a failure exits 1 and writes nothing to stdout",
                            "channel", "string — the resolved channel ID",
                            "ts", "string — the message timestamp, which identifies the message",
                            "permalink", "string|null — a link to the posted message"),
                    List.of(
                            "A send is never retried after a server or network error, because a write that failed that way may still have been applied and retrying could double-post. A rate limit (HTTP 429) is retried, since Slack rejected the call outright.",
                            "On Enterprise Grid, looking a channel up by name uses the client boot payload, which covers the channels you are a member of. Pass a channel ID or an archive link to skip name resolution entirely."),
                    List.of(
                            new ErrorSpec("Not signed in", "Run " + PROGRAM + " login."),
                            new ErrorSpec(
                                    "No channel named \"...\" is visible to this account",
                                    "Check the spelling, and that the account is a member of the channel. Or pass the channel ID."),
                            new ErrorSpec(
                                    "channel_not_found",
                                    "The channel ID does not exist, or this account cannot see it."),
                            new ErrorSpec(
                                    "invalid_auth, token_expired, token_revoked, not_authed, account_inactive",
                                    "The browser session behind the stored tokens has ended. Run " + PROGRAM + " login."))),
            new CommandSpec(
                    "whoami",
                    "Verify the stored credentials against Slack.",
                    "Answers which workspace and user the CLI is acting as, and whether the stored "
                            + "credentials still work. It makes one call to Slack, so there is no offline "
                            + "mode — the check is the point. Use it as a gate in a script before sending.",
                    PROGRAM + " whoami [--json]",
                    List.of(new OptionSpec("json", null, null,
                            "Write the result to stdout as one JSON object.", null, null)),
                    List.of(PROGRAM + " whoami", PROGRAM + " whoami --json"),
                    orderedMap(
                            "status", "string — \"ok\"; a failed check exits 1 and writes nothing to stdout",
                            "workspace_name", "string — the workspace or organisation name",
                            "workspace_id", "string — the Slack team or enterprise ID",
                            "workspace_url", "string — the host requests are sent to",
                            "user", "string — the signed-in user as Slack reports it",
                            "user_id", "string — that user's Slack ID"),
                    null,
                    List.of(
                            new ErrorSpec("Not signed in", "Run " + PROGRAM + " login."),
                            new ErrorSpec(
                                    "invalid_auth, token_expired, token_revoked, not_authed, account_inactive",
                                    "The stored credentials were refused. Run " + PROGRAM + " login."))),
            new CommandSpec(
                    "logout",
                    "Delete the stored credentials and the browser profile.",
                    "Removes the stored credentials. The browser profile goes too, because while "
                            + "it exists a login can re-mint working tokens with no prompt — it is itself a "
                            + "credential store. Pass --keep-browser-session to leave it signed in.",
                    PROGRAM + " logout [--keep-browser-session]",
                    List.of(new OptionSpec("keep-browser-session", null, null,
                            "Leave the browser profile in place, so a later login needs no sign-in.", null, null)),
                    List.of(PROGRAM + " logout", PROGRAM + " logout --keep-browser-session"),
                    null,
                    List.of("Succeeds whether or not anything was stored."),
                    null));

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Help() {
    }

    /** Finds a documented command, returning empty when the name is unknown or absent. */
    public static Optional<CommandSpec> findCommand(String name) {
        return COMMANDS.stream().filter(command -> command.name().equals(name)).findFirst();
    }

    /** The hint printed after a usage error, pointing at the command's own help. */
    public static String usageHint(String command) {
        return "(run \"" + PROGRAM + " " + command + " --help\" for usage and examples)";
    }

    public static String renderRootHelp() {
        List<String> lines = new ArrayList<>(List.of(PROGRAM + " — " + SUMMARY, "", "Usage:"));
        for (CommandSpec command : COMMANDS) {
            lines.add("  " + command.usage());
        }
        lines.add("  " + PROGRAM + " help [<command>] [--json]");

        lines.add("");
        lines.add("Commands:");
        int width = COMMANDS.stream().mapToInt(command -> command.name().length()).max().orElse(0);
        for (CommandSpec command : COMMANDS) {
            lines.add("  " + padRight(command.name(), width) + "  " + command.summary());
        }

        lines.add("");
        lines.add("Run \"" + PROGRAM + " <command> --help\" for that command's options, examples and errors.");
        lines.add("Run \"" + PROGRAM + " help --json\" for the whole specification as JSON.");

        lines.add("");
        lines.add("Environment:");
        int envWidth = ENVIRONMENT.stream().mapToInt(entry -> entry.name().length()).max().orElse(0);
        for (EnvironmentEntry entry : ENVIRONMENT) {
            lines.add("  " + padRight(entry.name(), envWidth) + "  " + entry.description());
        }

        lines.add("");
        lines.add("Notes:");
        for (String note : GLOBAL_NOTES) {
            lines.addAll(wrapBullet(note));
        }

        return String.join("\n", lines);
    }

    public static String renderCommandHelp(CommandSpec command) {
        List<String> lines = new ArrayList<>(List.of(
                PROGRAM + " " + command.name() + " — " + command.summary(), ""));
        lines.addAll(wrap(command.description(), 78));
        lines.add("");
        lines.add("Usage:");
        lines.add("  " + command.usage());

        if (!command.options().isEmpty()) {
            lines.add("");
            lines.add("Options:");
            List<RenderedOption> rendered = command.options().stream()
                    .map(option -> new RenderedOption(renderOptionFlags(option), option))
                    .toList();
            int width = rendered.stream().mapToInt(option -> option.left().length()).max().orElse(0);
            for (RenderedOption item : rendered) {
                List<String> extras = new ArrayList<>();
                if (Boolean.TRUE.equals(item.option().required())) {
                    extras.add("required");
                }
                if (item.option().defaultValue() != null) {
                    extras.add("default " + item.option().defaultValue());
                }
                String suffix = extras.isEmpty() ? "" : " [" + String.join(", ", extras) + "]";
                lines.add("  " + padRight(item.left(), width) + "  " + item.option().description() + suffix);
            }
        }

        lines.add("");
        lines.add("Examples:");
        for (String example : command.examples()) {
            lines.add("  " + example);
        }

        if (command.jsonShape() != null) {
            lines.add("");
            lines.add("With --json, stdout carries one object:");
            for (Map.Entry<String, String> entry : command.jsonShape().entrySet()) {
                lines.add("  " + entry.getKey() + ": " + entry.getValue());
            }
        }

        if (command.notes() != null && !command.notes().isEmpty()) {
            lines.add("");
            lines.add("Notes:");
            for (String note : command.notes()) {
                lines.addAll(wrapBullet(note));
            }
        }

        if (command.errors() != null && !command.errors().isEmpty()) {
            lines.add("");
            lines.add("Errors:");
            for (ErrorSpec error : command.errors()) {
                lines.add("  " + error.condition());
                for (String line : wrap(error.fix(), 72)) {
                    lines.add("      " + line);
                }
            }
        }

        return String.join("\n", lines);
    }

    /** Returns the complete CLI specification as pretty-printed JSON. */
    public static String helpAsJson() {
        Map<String, Object> specification = orderedMap(
                "program", PROGRAM,
                "summary", SUMMARY,
                "commands", COMMANDS,
                "environment", ENVIRONMENT,
                "notes", GLOBAL_NOTES,
                "exit_codes", orderedMap("0", "success", "1", "failure"));
        return toJson(specification);
    }

    /** Returns one command's specification, or the whole specification when command is null. */
    public static String helpAsJson(CommandSpec command) {
        return command == null ? helpAsJson() : toJson(command);
    }

    private static String renderOptionFlags(OptionSpec option) {
        String longFlag = "--" + option.name() + (option.value() == null ? "" : " " + option.value());
        return option.alias() == null ? "    " + longFlag : "-" + option.alias() + ", " + longFlag;
    }

    private static List<String> wrapBullet(String text) {
        List<String> wrapped = wrap(text, 74);
        List<String> lines = new ArrayList<>(wrapped.size());
        lines.add("  - " + wrapped.getFirst());
        for (int i = 1; i < wrapped.size(); i++) {
            lines.add("    " + wrapped.get(i));
        }
        return lines;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String current = "";
        for (String word : text.split(" ")) {
            if (current.isEmpty()) {
                current = word;
            } else if (current.length() + 1 + word.length() <= width) {
                current += " " + word;
            } else {
                lines.add(current);
                current = word;
            }
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        return lines;
    }

    private static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String toJson(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize help specification", exception);
        }
    }

    private static <V> Map<String, V> orderedMap(Object... entries) {
        Map<String, V> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            @SuppressWarnings("unchecked")
            V value = (V) entries[i + 1];
            result.put((String) entries[i], value);
        }
        return Collections.unmodifiableMap(result);
    }

    private record RenderedOption(String left, OptionSpec option) {
    }

    @JsonPropertyOrder({"name", "summary", "description", "usage", "options", "examples", "jsonShape", "notes", "errors"})
    public record CommandSpec(
            String name,
            String summary,
            String description,
            String usage,
            List<OptionSpec> options,
            List<String> examples,
            Map<String, String> jsonShape,
            List<String> notes,
            List<ErrorSpec> errors) {
        public CommandSpec {
            options = List.copyOf(options);
            examples = List.copyOf(examples);
            jsonShape = jsonShape == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(jsonShape));
            notes = notes == null ? null : List.copyOf(notes);
            errors = errors == null ? null : List.copyOf(errors);
        }
    }

    @JsonPropertyOrder({"name", "alias", "value", "description", "default", "required"})
    public record OptionSpec(
            String name,
            String alias,
            String value,
            String description,
            @JsonProperty("default") String defaultValue,
            Boolean required) {
    }

    @JsonPropertyOrder({"condition", "fix"})
    public record ErrorSpec(String condition, String fix) {
    }

    @JsonPropertyOrder({"name", "description"})
    public record EnvironmentEntry(String name, String description) {
    }
}

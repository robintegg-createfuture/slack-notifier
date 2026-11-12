package io.github.slacknotifier;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "slack-notifier", subcommands = {
    Main.Login.class, Main.Send.class, Main.Whoami.class, Main.Logout.class, Main.HelpCommand.class
})
public final class Main implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Option(names = {"-h", "--help"}) boolean help;
    @Option(names = "--json") boolean json;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
            .setExitCodeExceptionMapper(error -> 1)
            .setExecutionExceptionHandler((error, commandLine, parseResult) -> {
                commandLine.getErr().println(error.getMessage());
                return 1;
            })
            .setParameterExceptionHandler((error, arguments) -> {
                CommandLine command = error.getCommandLine();
                if (error instanceof CommandLine.UnmatchedArgumentException unmatched
                    && command.getCommand() instanceof Main && !unmatched.getUnmatched().isEmpty()) {
                    unknownCommand(command, unmatched.getUnmatched().getFirst());
                } else {
                    command.getErr().println(error.getMessage());
                    if (!(command.getCommand() instanceof Main)) {
                        command.getErr().println("(run \"slack-notifier " + command.getCommandName()
                            + " --help\" for usage and examples)");
                    }
                }
                return 1;
            })
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        spec.commandLine().getOut().println(json ? HelpOutput.json(null) : HelpOutput.root());
        return help ? 0 : 1;
    }

    private static boolean printHelp(CommandSpec spec, String command, boolean help, boolean json)
        throws Exception {
        if (!help) return false;
        spec.commandLine().getOut().println(json ? HelpOutput.json(command) : HelpOutput.command(command));
        return true;
    }

    private static void unknownCommand(CommandLine command, String name) {
        command.getErr().println("Unknown command \"" + name + "\".");
        command.getErr().println("Commands: login, send, whoami, logout.");
        command.getErr().println("Run \"slack-notifier --help\" for usage.");
    }

    @Command(name = "login")
    static final class Login implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = {"-h", "--help"}) boolean help;
        @Option(names = "--json") boolean json;
        @Option(names = "--headless") boolean headless;
        @Option(names = "--workspace") String workspace;
        @Option(names = "--timeout", defaultValue = "300") String timeout;

        @Override public Integer call() throws Exception {
            if (printHelp(spec, "login", help || parent.help, json || parent.json)) return 0;
            return Actions.login(headless, workspace, timeout);
        }
    }

    @Command(name = "send")
    static final class Send implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = {"-h", "--help"}) boolean help;
        @Option(names = "--json") boolean json;
        @Option(names = {"-c", "--channel"}) String channel;
        @Option(names = {"-m", "--message"}) String message;

        @Override public Integer call() throws Exception {
            if (printHelp(spec, "send", help || parent.help, json || parent.json)) return 0;
            return Actions.send(channel, message, json || parent.json);
        }
    }

    @Command(name = "whoami")
    static final class Whoami implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = {"-h", "--help"}) boolean help;
        @Option(names = "--json") boolean json;

        @Override public Integer call() throws Exception {
            if (printHelp(spec, "whoami", help || parent.help, json || parent.json)) return 0;
            return Actions.whoami(json || parent.json);
        }
    }

    @Command(name = "logout")
    static final class Logout implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = {"-h", "--help"}) boolean help;
        @Option(names = "--json") boolean json;
        @Option(names = "--keep-browser-session") boolean keepBrowserSession;

        @Override public Integer call() throws Exception {
            if (printHelp(spec, "logout", help || parent.help, json || parent.json)) return 0;
            return Actions.logout(keepBrowserSession);
        }
    }

    @Command(name = "help")
    static final class HelpCommand implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = "--json") boolean json;
        @Option(names = {"-h", "--help"}) boolean help;
        @Parameters(index = "0", arity = "0..1") String topic;

        @Override public Integer call() throws Exception {
            if (topic != null && !HelpOutput.known(topic)) {
                unknownCommand(spec.commandLine(), topic);
                return 1;
            }
            spec.commandLine().getOut().println(json || parent.json
                ? HelpOutput.json(topic)
                : topic == null ? HelpOutput.root() : HelpOutput.command(topic));
            return 0;
        }
    }
}

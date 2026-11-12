package io.github.slacknotifier;

import io.github.slacknotifier.help.Help;

final class HelpOutput {
    private HelpOutput() {}

    static boolean known(String command) {
        return Help.findCommand(command).isPresent();
    }

    static String root() {
        return Help.renderRootHelp();
    }

    static String command(String name) {
        return Help.renderCommandHelp(Help.findCommand(name).orElseThrow());
    }

    static String json(String name) throws Exception {
        return name == null ? Help.helpAsJson() : Help.helpAsJson(Help.findCommand(name).orElseThrow());
    }
}

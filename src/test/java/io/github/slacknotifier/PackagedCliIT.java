package io.github.slacknotifier;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliIT {
    @TempDir Path config;

    record Result(int exit, String out, String err) {}

    private Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(Path.of("target", "slack-notifier.jar").toAbsolutePath().toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SLACK_NOTIFIER_CONFIG_DIR", config.toString());
        builder.environment().put("SLACK_NOTIFIER_BROWSER", config.resolve("missing-browser").toString());
        Process process = builder.start();
        process.getOutputStream().close();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI did not exit");
        return new Result(process.exitValue(),
            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void packagedHelpJsonDescribesAllCommands() throws Exception {
        Result result = run("help", "--json");
        assertEquals(0, result.exit());
        assertEquals("", result.err());
        JsonNode json = new ObjectMapper().readTree(result.out());
        assertEquals("slack-notifier", json.path("program").asText());
        assertEquals(4, json.path("commands").size());
        assertEquals("success", json.path("exit_codes").path("0").asText());
    }

    @Test void commandHelpDoesNotStartBrowser() throws Exception {
        Result result = run("login", "--help");
        assertEquals(0, result.exit());
        assertTrue(result.out().contains("--headless"));
        assertEquals("", result.err());
    }

    @Test void invalidTimeoutFailsBeforeLaunchingBrowser() throws Exception {
        Result result = run("login", "--timeout", "0");
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("--timeout must be a positive number"));
        assertEquals("", result.out());
    }

    @Test void failedJsonSendDoesNotWriteToStdout() throws Exception {
        Result result = run("send", "--channel", "#alerts", "--message", "message with spaces", "--json");
        assertEquals(1, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().contains("Not signed in"));
    }

    @Test void unknownHelpTopicFails() throws Exception {
        Result result = run("help", "missing");
        assertEquals(1, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().contains("Unknown command"));
    }

    @Test void noArgumentsAreAUsageErrorButHelpSucceeds() throws Exception {
        Result noArguments = run();
        assertEquals(1, noArguments.exit());
        assertTrue(noArguments.out().contains("Usage:"));
        Result help = run("--help");
        assertEquals(0, help.exit());
        assertTrue(help.out().contains("Usage:"));
    }

    @Test void logoutRespectsTheBrowserProfileOption() throws Exception {
        Path profile = java.nio.file.Files.createDirectories(config.resolve("browser-profile"));
        java.nio.file.Files.writeString(profile.resolve("session"), "test");
        java.nio.file.Files.writeString(config.resolve("config.json"), "{}");
        Result keep = run("logout", "--keep-browser-session");
        assertEquals(0, keep.exit());
        assertFalse(java.nio.file.Files.exists(config.resolve("config.json")));
        assertTrue(java.nio.file.Files.exists(profile.resolve("session")));
        Result remove = run("logout");
        assertEquals(0, remove.exit());
        assertFalse(java.nio.file.Files.exists(profile));
    }

    @Test void documentedShellLaunchersForwardArguments() throws Exception {
        Path install = Files.createDirectories(config.resolve(".local/share/slack-notifier"));
        Files.copy(Path.of("target/slack-notifier.jar"), install.resolve("slack-notifier.jar"));
        Path bin = Files.createDirectories(config.resolve(".local/bin"));
        Path launcher = bin.resolve("slack-notifier");
        Files.writeString(launcher, "#!/usr/bin/env bash\nexec java -jar \"$HOME/.local/share/slack-notifier/slack-notifier.jar\" \"$@\"\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<String> commands = List.of(
            "shopt -s expand_aliases\nalias slack-notifier='java -jar \"$HOME/.local/share/slack-notifier/slack-notifier.jar\"'\nslack-notifier help send --json",
            "slack-notifier() { java -jar \"$HOME/.local/share/slack-notifier/slack-notifier.jar\" \"$@\"; }\nslack-notifier help send --json",
            "\"$HOME/.local/bin/slack-notifier\" help send --json");
        for (String command : commands) {
            ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
            builder.environment().put("HOME", config.toString());
            Process process = builder.start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), stderr);
            assertEquals("send", new ObjectMapper().readTree(stdout).path("name").asText());
        }
    }

    @Test void terminationClosesTheBrowserProcess() throws Exception {
        Path browser = config.resolve("fake-browser");
        Files.writeString(browser, "#!/bin/sh\nexec sleep 60\n");
        Files.setPosixFilePermissions(browser, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", Path.of("target/slack-notifier.jar").toAbsolutePath().toString(), "login");
        builder.environment().put("SLACK_NOTIFIER_CONFIG_DIR", config.resolve("profile").toString());
        builder.environment().put("SLACK_NOTIFIER_BROWSER", browser.toString());
        Process cli = builder.start();
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child == null && System.nanoTime() < deadline) {
                child = cli.toHandle().children().findFirst().orElse(null);
                if (child == null) Thread.sleep(25);
            }
            assertNotNull(child, "browser process was not launched");
            Thread.sleep(200);
            cli.destroy();
            assertTrue(cli.waitFor(10, TimeUnit.SECONDS), "CLI did not exit after termination");
            child.onExit().get(10, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
        } finally {
            if (cli.isAlive()) cli.destroyForcibly();
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }
}

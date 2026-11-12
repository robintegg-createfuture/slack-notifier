package io.github.slacknotifier.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Browser implements AutoCloseable {
    private static final List<String> CANDIDATES = List.of(
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium",
        "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge", "/usr/bin/brave-browser",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");

    private final Process process;
    private final CdpClient session;
    private final Thread shutdownHook;

    private Browser(Process process, CdpClient session, Thread shutdownHook) {
        this.process = process;
        this.session = session;
        this.shutdownHook = shutdownHook;
    }

    public CdpClient session() {
        return session;
    }

    public static Browser launch(Path profile, boolean headless) throws Exception {
        String binary = System.getenv("SLACK_NOTIFIER_BROWSER");
        if (binary == null || binary.isEmpty()) {
            binary = CANDIDATES.stream().filter(path -> Files.exists(Path.of(path))).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No Chrome, Edge, Chromium or Brave installation was found.\n" +
                    "   Install one, or set SLACK_NOTIFIER_BROWSER to the browser's path."));
        }
        Files.createDirectories(profile);
        if (Files.getFileStore(profile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"));
        }
        Files.deleteIfExists(profile.resolve("DevToolsActivePort"));
        List<String> args = new ArrayList<>(List.of(binary, "--remote-debugging-port=0",
            "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check",
            "--disable-background-networking"));
        if (headless) args.add("--headless=new");
        args.add("https://app.slack.com/client");
        Process process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread shutdownHook = new Thread(() -> {
            try {
                terminate(process);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }, "slack-notifier-browser-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            int port = waitForPort(profile, process);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/json/version")).timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("The browser did not report a debugging endpoint.");
            }
            JsonNode info = new ObjectMapper().readTree(response.body());
            String url = info.path("webSocketDebuggerUrl").asText();
            if (url.isEmpty()) throw new IOException("The browser did not report a debugging endpoint.");
            URI ws = URI.create(url);
            if (!"ws".equals(ws.getScheme()) || !"127.0.0.1".equals(ws.getHost()) || ws.getPort() != port) {
                throw new IOException("The browser reported an invalid debugging endpoint.");
            }
            return new Browser(process, CdpClient.connect(ws), shutdownHook);
        } catch (Exception e) {
            try {
                terminate(process);
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
            throw e;
        }
    }

    private static int waitForPort(Path profile, Process process) throws Exception {
        Path file = profile.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IOException("The browser exited before opening a debugging port.");
            if (Files.exists(file)) {
                try {
                    int port = Integer.parseInt(Files.readAllLines(file).getFirst());
                    if (port > 0 && port <= 65535) return port;
                } catch (IOException | NumberFormatException | java.util.NoSuchElementException ignored) {
                    // The browser may still be writing the port file.
                }
            }
            Thread.sleep(100);
        }
        throw new IOException("The browser did not open a debugging port.");
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    @Override public void close() throws InterruptedException {
        try {
            session.close();
        } finally {
            try {
                terminate(process);
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        }
    }
}

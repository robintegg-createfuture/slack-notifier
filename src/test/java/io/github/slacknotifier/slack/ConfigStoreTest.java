package io.github.slacknotifier.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
  @TempDir Path tempDir;

  @Test
  void roundTripsTheSnakeCaseConfigAndRestrictsPermissions() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    StoredConfig config =
        new StoredConfig(
            "https://acme.slack.com",
            "Acme Corp",
            "T111",
            "U111",
            new StoredCredentials("xoxc-one", "xoxd-one"),
            Map.of("alerts", "C123"));

    store.write(config);

    assertEquals("Acme Corp", store.read().getWorkspaceName());
    assertEquals("C123", store.read().getChannelCache().get("alerts"));
    assertTrue(Files.readString(store.configPath()).contains("\"workspace_url\""));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.configDir())));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.configPath())));
  }

  @Test
  void missingCorruptAndIncompleteConfigsAreAbsent() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    assertNull(store.read());

    Files.createDirectories(store.configDir());
    Files.writeString(store.configPath(), "{ not json");
    assertNull(store.read());

    Files.writeString(store.configPath(), "{\"workspace_url\":\"x\"}");
    assertNull(store.read());

    Files.writeString(
        store.configPath(), "{\"credentials\":{\"xoxc\":\"xoxc-one\",\"xoxd\":\"xoxd-one\"}}");
    assertTrue(store.read().getChannelCache().isEmpty());
  }

  @Test
  void reportsFilesystemErrorsInsteadOfTreatingThemAsMissingCredentials() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    Files.createDirectories(store.configPath());
    assertThrows(java.io.IOException.class, store::read);
  }

  @Test
  void clearRemovesTokensAndOptionallyRemovesBrowserProfile() throws Exception {
    ConfigStore store = new ConfigStore(tempDir.resolve("config"));
    store.write(
        new StoredConfig(
            "https://acme.slack.com",
            "Acme Corp",
            "T111",
            null,
            new StoredCredentials("xoxc-one", "xoxd-one"),
            Map.of()));
    Files.createDirectories(store.browserProfileDir());
    Files.writeString(store.browserProfileDir().resolve("profile"), "session");

    store.clear(true);
    assertNull(store.read());
    assertTrue(Files.isDirectory(store.browserProfileDir()));

    store.clear(false);
    assertFalse(Files.exists(store.browserProfileDir()));
  }
}

package io.github.slacknotifier.slack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Reads and writes the workspace configuration with owner-only filesystem permissions. */
public final class ConfigStore {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final Path configDir;

  public ConfigStore() {
    this(defaultConfigDir());
  }

  public ConfigStore(Path configDir) {
    this.configDir = configDir;
  }

  public Path configDir() {
    return configDir;
  }

  public Path configPath() {
    return configDir.resolve("config.json");
  }

  public Path browserProfileDir() {
    return configDir.resolve("browser-profile");
  }

  /** Returns {@code null} when no usable configuration is present. */
  public StoredConfig read() throws IOException {
    Path path = configPath();
    if (!Files.exists(path)) {
      return null;
    }
    String content = Files.readString(path, StandardCharsets.UTF_8);
    try {
      StoredConfig config = JSON.readValue(content, StoredConfig.class);
      if (config == null
          || config.getCredentials() == null
          || isBlank(config.getCredentials().xoxc())
          || isBlank(config.getCredentials().xoxd())) {
        return null;
      }
      if (config.getChannelCache() == null) {
        config.setChannelCache(null);
      }
      return config;
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      return null;
    }
  }

  public void write(StoredConfig config) throws IOException {
    Files.createDirectories(configDir, PosixFilePermissions.asFileAttribute(ownerDirectoryPermissions()));
    setPosixPermissions(configDir, ownerDirectoryPermissions());
    Path path = configPath();
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Refusing to write Slack credentials through a symbolic link.");
    }
    try {
      Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerFilePermissions()));
    } catch (FileAlreadyExistsException ignored) {
      setPosixPermissions(path, ownerFilePermissions());
    }
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config) + "\n",
        StandardCharsets.UTF_8);
    setPosixPermissions(path, ownerFilePermissions());
  }

  /**
   * Removes configuration and, unless requested otherwise, the browser profile that can re-mint
   * credentials.
   */
  public void clear(boolean keepBrowserProfile) throws IOException {
    Files.deleteIfExists(configPath());
    if (!keepBrowserProfile) {
      deleteTree(browserProfileDir());
    }
  }

  private static Path defaultConfigDir() {
    String override = System.getenv("SLACK_NOTIFIER_CONFIG_DIR");
    if (override != null && !override.isEmpty()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home"), ".config", "slack-notifier");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isEmpty();
  }

  private static Set<PosixFilePermission> ownerDirectoryPermissions() {
    return PosixFilePermissions.fromString("rwx------");
  }

  private static Set<PosixFilePermission> ownerFilePermissions() {
    return PosixFilePermissions.fromString("rw-------");
  }

  private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    Files.setPosixFilePermissions(path, permissions);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}

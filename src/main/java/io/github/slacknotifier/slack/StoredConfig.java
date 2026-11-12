package io.github.slacknotifier.slack;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted workspace configuration. Field names map to the existing snake_case JSON schema. */
public final class StoredConfig {
  @JsonProperty("workspace_url")
  private String workspaceUrl;

  @JsonProperty("workspace_name")
  private String workspaceName;

  @JsonProperty("workspace_id")
  private String workspaceId;

  @JsonProperty("user_id")
  private String userId;

  @JsonProperty("credentials")
  private StoredCredentials credentials;

  @JsonProperty("channel_cache")
  private Map<String, String> channelCache = new LinkedHashMap<>();

  public StoredConfig() {}

  public StoredConfig(
      String workspaceUrl,
      String workspaceName,
      String workspaceId,
      String userId,
      StoredCredentials credentials,
      Map<String, String> channelCache) {
    this.workspaceUrl = workspaceUrl;
    this.workspaceName = workspaceName;
    this.workspaceId = workspaceId;
    this.userId = userId;
    this.credentials = credentials;
    setChannelCache(channelCache);
  }

  public String getWorkspaceUrl() {
    return workspaceUrl;
  }

  public void setWorkspaceUrl(String workspaceUrl) {
    this.workspaceUrl = workspaceUrl;
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  public void setWorkspaceName(String workspaceName) {
    this.workspaceName = workspaceName;
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = workspaceId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public StoredCredentials getCredentials() {
    return credentials;
  }

  public void setCredentials(StoredCredentials credentials) {
    this.credentials = credentials;
  }

  public Map<String, String> getChannelCache() {
    return channelCache;
  }

  public void setChannelCache(Map<String, String> channelCache) {
    this.channelCache =
        channelCache == null ? new LinkedHashMap<>() : new LinkedHashMap<>(channelCache);
  }
}

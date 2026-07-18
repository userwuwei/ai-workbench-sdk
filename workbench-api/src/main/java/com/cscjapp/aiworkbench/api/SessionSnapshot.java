package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class SessionSnapshot {
  private final int schemaVersion;
  private final String sessionId, definitionId, workspaceId;
  private final Map<String, Object> state;

  public SessionSnapshot(
      int schemaVersion,
      String sessionId,
      String definitionId,
      String workspaceId,
      Map<String, ?> state) {
    this.schemaVersion = schemaVersion;
    this.sessionId = sessionId;
    this.definitionId = definitionId;
    this.workspaceId = workspaceId;
    this.state = new LinkedHashMap<>();
    if (state != null) this.state.putAll(state);
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public String sessionId() {
    return sessionId;
  }

  public String definitionId() {
    return definitionId;
  }

  public String workspaceId() {
    return workspaceId;
  }

  public Map<String, Object> state() {
    return Collections.unmodifiableMap(state);
  }
}

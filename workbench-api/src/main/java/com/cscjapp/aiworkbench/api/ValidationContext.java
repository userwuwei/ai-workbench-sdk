package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ValidationContext {
  private final String sessionId, workspaceId, demand;
  private final List<ToolResult> evidence;

  public ValidationContext(
      String sessionId, String workspaceId, String demand, List<ToolResult> evidence) {
    this.sessionId = sessionId;
    this.workspaceId = workspaceId;
    this.demand = demand;
    this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
  }

  public String sessionId() {
    return sessionId;
  }

  public String workspaceId() {
    return workspaceId;
  }

  public String demand() {
    return demand;
  }

  public List<ToolResult> evidence() {
    return evidence;
  }
}

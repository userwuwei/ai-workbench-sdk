package com.cscjapp.aiworkbench.api;

/** Immutable identity and demand for one Agent submission. */
public final class AgentRunContext {
  private final long runId;
  private final String sessionId;
  private final String workspaceId;
  private final String demand;

  public AgentRunContext(long runId, String sessionId, String workspaceId, String demand) {
    this.runId = runId;
    this.sessionId = safe(sessionId);
    this.workspaceId = safe(workspaceId);
    this.demand = safe(demand);
  }

  public long runId() {
    return runId;
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

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}

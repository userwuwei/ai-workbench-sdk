package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.concurrent.Executor;

public final class DefaultToolContext implements ToolContext {
  private final String sessionId, workspaceId;
  private final Executor executor;
  private final UserDecisionService decisions;
  private final WorkbenchHost host;

  public DefaultToolContext(
      String s, String w, Executor e, UserDecisionService d, WorkbenchHost h) {
    sessionId = s;
    workspaceId = w;
    executor = e;
    decisions = d;
    host = h;
  }

  public String sessionId() {
    return sessionId;
  }

  public String workspaceId() {
    return workspaceId;
  }

  public Executor backgroundExecutor() {
    return executor;
  }

  public UserDecisionService userDecisions() {
    return decisions;
  }

  public WorkbenchHost host() {
    return host;
  }
}

package com.cscjapp.aiworkbench.api;

import java.util.concurrent.Executor;

public interface ToolContext {
  String sessionId();

  String workspaceId();

  Executor backgroundExecutor();

  UserDecisionService userDecisions();

  WorkbenchHost host();
}

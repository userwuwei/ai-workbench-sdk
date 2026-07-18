package com.cscjapp.aiworkbench.api;

/**
 * Optional lifecycle implemented by stateful tool policies.
 *
 * <p>Callbacks are scoped by {@link AgentRunContext#runId()} so a late callback from an older
 * request cannot contaminate the next task.
 */
public interface AgentRunLifecycle {
  default void onRunStarted(AgentRunContext context) {}

  default void onToolCompleted(
      AgentRunContext context, ToolInvocation invocation, ToolResult result) {}

  default void onRunFinished(AgentRunContext context, String state) {}
}

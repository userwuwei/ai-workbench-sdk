package com.cscjapp.aiworkbench.api;

public interface AgentTool {
  ToolSpec spec();

  default boolean requestsFinalize() {
    return false;
  }

  Cancellable execute(ToolContext context, ToolArguments arguments, ToolCallback callback);
}

package com.cscjapp.aiworkbench.api;

public final class ToolInvocation {
  private final String callId;
  private final AgentTool tool;
  private final ToolArguments arguments;

  public ToolInvocation(String callId, AgentTool tool, ToolArguments arguments) {
    this.callId = callId;
    this.tool = tool;
    this.arguments = arguments;
  }

  public String callId() {
    return callId;
  }

  public AgentTool tool() {
    return tool;
  }

  public ToolArguments arguments() {
    return arguments;
  }
}

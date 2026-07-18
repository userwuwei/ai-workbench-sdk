package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ToolArguments;

public final class AgentToolCall {
  private final String id, name;
  private final ToolArguments arguments;

  public AgentToolCall(String id, String name, ToolArguments arguments) {
    this.id = id;
    this.name = name;
    this.arguments = arguments;
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public ToolArguments arguments() {
    return arguments;
  }
}

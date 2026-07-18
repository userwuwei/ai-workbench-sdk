package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ModelRequest {
  private final ModelEndpoint endpoint;
  private final List<AgentMessage> messages;
  private final List<ToolSpec> tools;
  private final boolean deepThinking;

  public ModelRequest(
      ModelEndpoint endpoint,
      List<AgentMessage> messages,
      List<ToolSpec> tools,
      boolean deepThinking) {
    this.endpoint = endpoint;
    this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    this.deepThinking = deepThinking;
  }

  public ModelEndpoint endpoint() {
    return endpoint;
  }

  public List<AgentMessage> messages() {
    return messages;
  }

  public List<ToolSpec> tools() {
    return tools;
  }

  public boolean deepThinking() {
    return deepThinking;
  }
}

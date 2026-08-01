package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ModelRequest {
  private final ModelEndpoint endpoint;
  private final List<AgentMessage> messages;
  private final List<ToolSpec> tools;
  private final boolean deepThinking;
  private final boolean allowMultipleToolCalls;
  private final String requiredToolName;

  public ModelRequest(
      ModelEndpoint endpoint,
      List<AgentMessage> messages,
      List<ToolSpec> tools,
      boolean deepThinking) {
    this(endpoint, messages, tools, deepThinking, false, "");
  }

  public ModelRequest(
      ModelEndpoint endpoint,
      List<AgentMessage> messages,
      List<ToolSpec> tools,
      boolean deepThinking,
      boolean allowMultipleToolCalls) {
    this(endpoint, messages, tools, deepThinking, allowMultipleToolCalls, "");
  }

  public ModelRequest(
      ModelEndpoint endpoint,
      List<AgentMessage> messages,
      List<ToolSpec> tools,
      boolean deepThinking,
      boolean allowMultipleToolCalls,
      String requiredToolName) {
    this.endpoint = endpoint;
    this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    this.deepThinking = deepThinking;
    this.allowMultipleToolCalls = allowMultipleToolCalls;
    this.requiredToolName = requiredToolName == null ? "" : requiredToolName.trim();
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

  public boolean allowMultipleToolCalls() {
    return allowMultipleToolCalls;
  }

  public String requiredToolName() {
    return requiredToolName;
  }
}

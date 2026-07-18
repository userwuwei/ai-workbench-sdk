package com.cscjapp.aiworkbench.core;

import java.util.*;

public final class ModelResponse {
  private final String content, finishReason;
  private final List<AgentToolCall> toolCalls;

  public ModelResponse(String content, String finishReason, List<AgentToolCall> toolCalls) {
    this.content = content == null ? "" : content;
    this.finishReason = finishReason == null ? "" : finishReason;
    this.toolCalls =
        toolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(toolCalls));
  }

  public String content() {
    return content;
  }

  public String finishReason() {
    return finishReason;
  }

  public List<AgentToolCall> toolCalls() {
    return toolCalls;
  }
}

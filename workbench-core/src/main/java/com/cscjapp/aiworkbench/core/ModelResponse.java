package com.cscjapp.aiworkbench.core;

import java.util.*;

public final class ModelResponse {
  private final String content, finishReason;
  private final List<AgentToolCall> toolCalls;
  private final List<InvalidToolCall> invalidToolCalls;
  private final ModelUsage usage;

  public ModelResponse(String content, String finishReason, List<AgentToolCall> toolCalls) {
    this(
        content,
        finishReason,
        toolCalls,
        Collections.emptyList(),
        ModelUsage.UNKNOWN);
  }

  public ModelResponse(
      String content, String finishReason, List<AgentToolCall> toolCalls, ModelUsage usage) {
    this(content, finishReason, toolCalls, Collections.emptyList(), usage);
  }

  public ModelResponse(
      String content,
      String finishReason,
      List<AgentToolCall> toolCalls,
      List<InvalidToolCall> invalidToolCalls) {
    this(content, finishReason, toolCalls, invalidToolCalls, ModelUsage.UNKNOWN);
  }

  public ModelResponse(
      String content,
      String finishReason,
      List<AgentToolCall> toolCalls,
      List<InvalidToolCall> invalidToolCalls,
      ModelUsage usage) {
    this.content = content == null ? "" : content;
    this.finishReason = finishReason == null ? "" : finishReason;
    this.toolCalls =
        toolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    this.invalidToolCalls =
        invalidToolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(invalidToolCalls));
    this.usage = usage == null ? ModelUsage.UNKNOWN : usage;
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

  public List<InvalidToolCall> invalidToolCalls() {
    return invalidToolCalls;
  }

  public ModelUsage usage() {
    return usage;
  }
}

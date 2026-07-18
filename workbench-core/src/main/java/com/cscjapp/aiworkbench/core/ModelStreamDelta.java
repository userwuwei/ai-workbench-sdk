package com.cscjapp.aiworkbench.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured model-stream delta, including native tool-call argument fragments. */
public final class ModelStreamDelta {
  private final String content;
  private final String reasoning;
  private final List<ToolCallStreamDelta> toolCalls;

  public ModelStreamDelta(
      String content, String reasoning, List<ToolCallStreamDelta> toolCalls) {
    this.content = content == null ? "" : content;
    this.reasoning = reasoning == null ? "" : reasoning;
    this.toolCalls =
        toolCalls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(toolCalls));
  }

  public static ModelStreamDelta text(String content, String reasoning) {
    return new ModelStreamDelta(content, reasoning, Collections.emptyList());
  }

  public String content() {
    return content;
  }

  public String reasoning() {
    return reasoning;
  }

  public List<ToolCallStreamDelta> toolCalls() {
    return toolCalls;
  }

  public boolean isEmpty() {
    return content.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty();
  }
}

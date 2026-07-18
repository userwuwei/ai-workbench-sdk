package com.cscjapp.aiworkbench.api;

public final class PromptSection {
  private final PromptPhase phase;
  private final int priority, budgetChars;
  private final String id, content;

  public PromptSection(
      String id, PromptPhase phase, int priority, int budgetChars, String content) {
    this.id = id;
    this.phase = phase;
    this.priority = priority;
    this.budgetChars = budgetChars;
    this.content = content == null ? "" : content;
  }

  public String id() {
    return id;
  }

  public PromptPhase phase() {
    return phase;
  }

  public int priority() {
    return priority;
  }

  public int budgetChars() {
    return budgetChars;
  }

  public String content() {
    return content;
  }
}

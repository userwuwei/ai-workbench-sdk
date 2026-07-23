package com.cscjapp.aiworkbench.api;

/** Immutable identity for one model round within an Agent run. */
public final class AgentRoundContext {
  private final AgentRunContext runContext;
  private final int round;

  public AgentRoundContext(AgentRunContext runContext, int round) {
    if (runContext == null) throw new IllegalArgumentException("runContext required");
    this.runContext = runContext;
    this.round = Math.max(0, round);
  }

  public AgentRunContext runContext() {
    return runContext;
  }

  public int round() {
    return round;
  }
}

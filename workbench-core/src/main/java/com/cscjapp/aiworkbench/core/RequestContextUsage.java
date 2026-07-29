package com.cscjapp.aiworkbench.core;

/** Snapshot of the exact request projection selected for the latest model request. */
public final class RequestContextUsage {
  private final long inputTokens;
  private final long messageTokens;
  private final long toolTokens;
  private final long maximumInputTokens;
  private final String projectionMode;
  private final ModelUsage providerUsage;

  RequestContextUsage(
      long inputTokens,
      long messageTokens,
      long toolTokens,
      long maximumInputTokens,
      String projectionMode,
      ModelUsage providerUsage) {
    this.inputTokens = Math.max(0L, inputTokens);
    this.messageTokens = Math.max(0L, messageTokens);
    this.toolTokens = Math.max(0L, toolTokens);
    this.maximumInputTokens = Math.max(1L, maximumInputTokens);
    this.projectionMode = projectionMode == null ? "append_only" : projectionMode;
    this.providerUsage = providerUsage == null ? ModelUsage.UNKNOWN : providerUsage;
  }

  public long inputTokens() { return inputTokens; }
  public long messageTokens() { return messageTokens; }
  public long toolTokens() { return toolTokens; }
  public long maximumInputTokens() { return maximumInputTokens; }
  public String projectionMode() { return projectionMode; }
  public ModelUsage providerUsage() { return providerUsage; }
}

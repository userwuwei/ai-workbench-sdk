package com.cscjapp.aiworkbench.core;

/** Optional provider-reported token usage for one model response. */
public final class ModelUsage {
  public static final ModelUsage UNKNOWN = new ModelUsage(-1L, -1L, -1L, -1L);

  private final long inputTokens;
  private final long cachedTokens;
  private final long outputTokens;
  private final long totalTokens;

  public ModelUsage(long inputTokens, long cachedTokens, long outputTokens, long totalTokens) {
    this.inputTokens = inputTokens;
    this.cachedTokens = cachedTokens;
    this.outputTokens = outputTokens;
    this.totalTokens = totalTokens;
  }

  public long inputTokens() {
    return inputTokens;
  }

  public long cachedTokens() {
    return cachedTokens;
  }

  public long uncachedTokens() {
    return inputTokens >= 0L && cachedTokens >= 0L
        ? Math.max(0L, inputTokens - cachedTokens) : -1L;
  }

  public long outputTokens() {
    return outputTokens;
  }

  public long totalTokens() {
    return totalTokens;
  }

  public int cachedPercent() {
    return inputTokens > 0L && cachedTokens >= 0L
        ? (int) Math.round(cachedTokens * 100.0d / inputTokens) : -1;
  }

  public boolean known() {
    return inputTokens >= 0L || cachedTokens >= 0L || outputTokens >= 0L || totalTokens >= 0L;
  }
}

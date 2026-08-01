package com.cscjapp.aiworkbench.api;

/** Optional round policy for allowing one model response to contain multiple tool calls. */
public interface MultipleToolCallPolicy {
  boolean allowMultipleToolCalls(AgentRoundContext context);
}
